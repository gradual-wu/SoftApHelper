package com.xhy.xp.softaphelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 跑在 system_server 里的那部分模块代码，管两件事：
 *
 * <ul>
 *   <li>界面保存配置后发一条广播过来，把对应类型的共享「关掉再打开」一次，让新网段立刻生效；
 *       只重启「当前确实在共享」的类型，没在共享的直接跳过。</li>
 *   <li>开机自动开启：开机完成后把界面上勾了「开机自动开启」的共享直接打开。</li>
 * </ul>
 *
 * <p>权限说明：这里调的是 {@link TetheringManager} 的接口，需要调用方持有
 * {@code TETHER_PRIVILEGED}。模块的这段代码运行在 system_server（uid 1000），
 * 框架的 {@code ActivityManager.checkComponentPermission} 对 core uid 直接放行，
 * 所以不需要额外授权。重启用的广播接收器要求发送方持有本模块定义的 signature 权限，
 * 避免别的应用也能触发重启。
 *
 * <p>下面这些静态字段和方法都跑在同一个 {@link HandlerThread} 上（setup 的重试、
 * 广播接收器、开机自启的延时任务都在这个 Handler 里），所以只有
 * {@link #sActiveTypes} 需要加锁 —— 它会被 Tethering 的回调线程写。
 */
public class TetheringRestarter {

    /** TetheringManager 从 Android 11（API 30）起才有，开机自启要用它开共享。 */
    private static final int MIN_SDK = Build.VERSION_CODES.R;
    /**
     * 带回调的 {@code startTethering/stopTethering(TetheringRequest, ...)} 从 Android 14 起才有。
     * 「重启共享」要靠回调才能知道停没停干净，所以那个功能仍然是 Android 14 起；
     * 低版本只是不注册它的广播接收器，类里那几个 API 34 的类型不会被解析到。
     */
    private static final int MIN_SDK_REQUEST = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_INTERVAL_MS = 500;
    /** 等 Wi-Fi 真正把 AP 关掉再重新开，见 {@link #startAfterStop}。 */
    private static final long START_DELAY_MS = 2500;
    private static final int MAX_START_ATTEMPTS = 4;

    /** 开机后等系统把 Wi-Fi / Tethering 都拉起来再开热点（见 {@link #startOnBoot}）。 */
    private static final long BOOT_DELAY_MS = 5000;
    /** 开机时开共享失败的重试间隔和次数：约 2 分钟。 */
    private static final long BOOT_RETRY_INTERVAL_MS = 5000;
    private static final int BOOT_MAX_START_ATTEMPTS = 24;
    /** 兜底的 {@code sys.boot_completed} 轮询：10 秒一次，最多 10 分钟。 */
    private static final long BOOT_POLL_INTERVAL_MS = 10000;
    private static final int BOOT_POLL_MAX = 60;

    private static final Executor DIRECT_EXECUTOR = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static final Set<Integer> sActiveTypes = new HashSet<>();
    private static volatile TetheringManager sTetheringManager;
    private static volatile Handler sHandler;
    /** 界面保存配置后的「重启共享」广播。 */
    private static BroadcastReceiver sReceiver;
    /** 开机广播。 */
    private static BroadcastReceiver sBootReceiver;
    private static boolean sCallbackRegistered;
    private static boolean sInstalled;
    /** 这次开机已经处理过自动开启了（开机广播和兜底轮询只让其中一个生效）。 */
    private static boolean sBootHandled;
    /** 兜底轮询已经装上了，别重复装。 */
    private static boolean sBootWatcherStarted;

    private static void log(String message) {
        XposedBridge.log("[" + MainHook.TAG + "] " + message);
    }

    public static synchronized void install(final ClassLoader classLoader) {
        if (sInstalled) return;
        sInstalled = true;

        if (Build.VERSION.SDK_INT < MIN_SDK) {
            log("[Skip]: restarting/auto starting tethering needs Android 11+.");
            return;
        }

        try {
            HandlerThread thread = new HandlerThread("SoftApHelper");
            thread.start();
            final Handler handler = new Handler(thread.getLooper());
            sHandler = handler;
            // 模块在 system_server 里加载得很早，此时系统 Context / ActivityManager 可能还没就绪，
            // 隔一会儿重试，最多试 40 次（约 20 秒）
            handler.postDelayed(new Runnable() {
                private int attempts;

                @Override
                public void run() {
                    String error = setup(classLoader, handler);
                    if (error == null) {
                        log("[Success]: auto restart tethering enabled in system_server.");
                        return;
                    }
                    if (++attempts >= MAX_ATTEMPTS) {
                        log("[Error]: auto restart tethering disabled: " + error);
                        return;
                    }
                    if (attempts == 1) {
                        log("[Warning]: waiting for system services to start (" + error + ")");
                    }
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                }
            }, RETRY_INTERVAL_MS);
        } catch (Throwable throwable) {
            log("[Error]: install tethering restarter: " + throwable);
        }
    }

    /**
     * 装好回调与广播接收器，返回 null 表示成功，否则返回失败原因（可以重试）。
     *
     * <p>注册分两步，各自可能因为系统服务还没起来而失败，所以拆开，成功过的步骤不重复做。
     */
    private static String setup(ClassLoader classLoader, Handler handler) {
        Context context;
        try {
            context = getSystemContext(classLoader);
        } catch (Throwable throwable) {
            return "system context: " + throwable;
        }
        if (context == null) return "system context not ready";

        if (!sCallbackRegistered) {
            try {
                TetheringManager manager = context.getSystemService(TetheringManager.class);
                if (manager == null) return "TetheringManager not ready";
                sTetheringManager = manager;
                manager.registerTetheringEventCallback(DIRECT_EXECUTOR,
                        new TetheringManager.TetheringEventCallback() {
                    @Override
                    public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
                        Set<Integer> types = new HashSet<>();
                        for (TetheringInterface tetheringInterface : interfaces) {
                            types.add(tetheringInterface.getType());
                        }
                        synchronized (sActiveTypes) {
                            sActiveTypes.clear();
                            sActiveTypes.addAll(types);
                        }
                    }
                });
                sCallbackRegistered = true;
            } catch (Throwable throwable) {
                return "tethering callback: " + throwable;
            }
        }

        if (sReceiver == null && Build.VERSION.SDK_INT >= MIN_SDK_REQUEST) {
            try {
                sReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        restart(intent.getIntExtra(AppSettings.EXTRA_TETHERING_TYPE, -1));
                    }
                };
                // 等 ActivityManager 就绪，这一步在 system_server 里可能比 Tethering 还晚
                IntentFilter filter = new IntentFilter(AppSettings.ACTION_RESTART_TETHERING);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 发送方是本模块自己的应用进程（另一个 uid），所以要 EXPORTED；
                    // 加上 broadcastPermission，只有持有本模块 signature 权限的发送方送得进来
                    context.registerReceiver(sReceiver, filter,
                            AppSettings.PERMISSION_RESTART_TETHERING, handler,
                            Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(sReceiver, filter,
                            AppSettings.PERMISSION_RESTART_TETHERING, handler);
                }
            } catch (Throwable throwable) {
                sReceiver = null;
                return "register receiver: " + throwable;
            }
        }

        String bootError = null;
        if (sBootReceiver == null) {
            try {
                sBootReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                            // 收到广播不代表这次就是它触发的（兜底轮询可能已经先动手了），
                            // 这行只是用来说明广播这条通路是通的
                            log("[Boot]: BOOT_COMPLETED received.");
                            startOnBoot("BOOT_COMPLETED");
                        }
                    }
                };
                // BOOT_COMPLETED 是系统自己发的保护广播，别的应用发不出来，
                // 所以这里 EXPORTED 也不会被谁冒充（系统 Context 注册时本来也不看这个标志）
                IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(sBootReceiver, filter, null, handler,
                            Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(sBootReceiver, filter, null, handler);
                }
            } catch (Throwable throwable) {
                sBootReceiver = null;
                bootError = "register boot receiver: " + throwable;
            }
        }

        // 兜底轮询独立于广播接收器：接收器注册不上（或者广播已经发完了）时它是唯一的指望，
        // 所以就算上面失败了也要装（只装一次，重试 setup 时不重复装）
        if (!sBootWatcherStarted) {
            sBootWatcherStarted = true;
            watchBootCompleted(handler);
        }

        return bootError;
    }

    /** system_server 里的「系统 Context」。 */
    private static Context getSystemContext(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = classLoader.loadClass("android.app.ActivityThread");
            Object activityThread =
                    XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            if (activityThread == null) return null;
            return (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static void restart(final int type) {
        final TetheringManager manager = sTetheringManager;
        final Handler handler = sHandler;
        if (manager == null || handler == null || type < 0) return;

        boolean active;
        synchronized (sActiveTypes) {
            active = sActiveTypes.contains(type);
        }
        if (!active) {
            log("[Skip]: type " + type + " is not tethering, nothing to restart.");
            return;
        }

        log("[Restart]: type " + type + " ...");
        final TetheringManager.TetheringRequest request =
                new TetheringManager.TetheringRequest.Builder(type).build();
        try {
            manager.stopTethering(request, DIRECT_EXECUTOR,
                    new TetheringManager.StopTetheringCallback() {
                        @Override
                        public void onStopTetheringSucceeded() {
                            startAfterStop(handler, type);
                        }

                        @Override
                        public void onStopTetheringFailed(int error) {
                            log("[Warning]: stop tethering failed: " + error);
                            startAfterStop(handler, type);
                        }
                    });
        } catch (Throwable throwable) {
            log("[Error]: stop tethering: " + throwable);
        }
    }

    /**
     * 停止回调返回时，Wi-Fi 那边其实还在关 AP（{@code stopSoftAp()} 是异步的），
     * 这时候立刻 start 会撞上 Wi-Fi 模块的 "Tethering is already active or activating"，
     * 最终报 {@code TETHER_ERROR_INTERNAL_ERROR}。所以等一下再开，开不起来就再试几次。
     */
    private static void startAfterStop(final Handler handler, final int type) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startTethering(type, 1, MAX_START_ATTEMPTS, START_DELAY_MS);
            }
        }, START_DELAY_MS);
    }

    /**
     * 打开某种共享，失败就隔一会儿再试。
     *
     * <p>Android 14+ 用带回调的 {@code startTethering(TetheringRequest, ...)}；
     * 11~13 只有 {@code startTethering(int, Executor, StartTetheringCallback)} 这个
     * SystemApi（公开 SDK 里没有，只能反射调，Android 14 起它已经被标记废弃）。
     *
     * <p>开机时系统可能还没把 Wi-Fi / Tethering 拉起来，{@code onTetheringFailed} 会
     * 拿到错误码，所以重试次数给得比「重启共享」多（见 {@link #BOOT_MAX_START_ATTEMPTS}）。
     */
    private static void startTethering(final int type, final int attempt, final int maxAttempts,
                                       final long retryIntervalMs) {
        final TetheringManager manager = sTetheringManager;
        final Handler handler = sHandler;
        if (manager == null || handler == null) return;

        TetheringManager.StartTetheringCallback callback =
                new TetheringManager.StartTetheringCallback() {
                    @Override
                    public void onTetheringStarted() {
                        log("[Success]: tethering started (type " + type + ").");
                    }

                    @Override
                    public void onTetheringFailed(int error) {
                        if (attempt >= maxAttempts) {
                            log("[Error]: start tethering (type " + type + ") failed after "
                                    + attempt + " attempts: " + error);
                            return;
                        }
                        log("[Warning]: start tethering (type " + type + ") attempt " + attempt
                                + " failed (" + error + "), retrying...");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startTethering(type, attempt + 1, maxAttempts, retryIntervalMs);
                            }
                        }, retryIntervalMs);
                    }
                };

        try {
            if (Build.VERSION.SDK_INT >= MIN_SDK_REQUEST) {
                manager.startTethering(
                        new TetheringManager.TetheringRequest.Builder(type).build(),
                        DIRECT_EXECUTOR, callback);
            } else {
                Method method = TetheringManager.class.getMethod("startTethering", int.class,
                        Executor.class, TetheringManager.StartTetheringCallback.class);
                method.invoke(manager, type, DIRECT_EXECUTOR, callback);
            }
        } catch (Throwable throwable) {
            log("[Error]: start tethering (type " + type + "): " + throwable);
        }
    }

    // ---------------- 开机自动开启 ----------------

    /**
     * 开机完成：把界面上勾了「开机自动开启」的共享打开。
     *
     * <p>开机广播和 {@link #watchBootCompleted} 的兜底轮询都会调到这，靠
     * {@link #sBootHandled} 保证只跑一次，谁先到谁生效。
     *
     * @param trigger 谁发现的「开机完成」，只用来打日志：{@code BOOT_COMPLETED}
     *                或者兜底轮询读到的 {@code sys.boot_completed}
     */
    private static void startOnBoot(String trigger) {
        if (sBootHandled) return;
        sBootHandled = true;

        final Handler handler = sHandler;
        if (handler == null) return;

        log("[Boot]: detected by " + trigger + ", auto start in " + BOOT_DELAY_MS / 1000 + "s.");

        // 开机广播发出来的时候 Wi-Fi / Tethering 还在初始化，等一会儿再开，
        // 开不起来还会按 BOOT_RETRY_INTERVAL_MS 重试
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (int type : AppSettings.types()) {
                    if (!isAutoStartEnabled(type)) continue;

                    boolean active;
                    synchronized (sActiveTypes) {
                        active = sActiveTypes.contains(type);
                    }
                    if (active) {
                        log("[Skip]: type " + type + " is already tethering.");
                        continue;
                    }

                    log("[Boot]: auto start tethering, type " + type + " ...");
                    startTethering(type, 1, BOOT_MAX_START_ATTEMPTS, BOOT_RETRY_INTERVAL_MS);
                }
            }
        }, BOOT_DELAY_MS);
    }

    /**
     * 兜底：动态注册的接收器收 {@code BOOT_COMPLETED} 万一没生效（个别 ROM），
     * 或者模块加载得比广播还晚，就盯着 {@code sys.boot_completed} 看。
     *
     * <p>看到 1 就当作开机完成（正常情况广播会先到，那之后这里每 10 秒进来一次、
     * 看到 {@link #sBootHandled} 就直接结束）；看满 {@link #BOOT_POLL_MAX} 次还没有就放弃。
     *
     * <p>顺带一提，system_server 自己重启（不是整机重启）时这个属性已经是 1，
     * 这时模块会按「开机」处理，把共享重新打开。
     */
    private static void watchBootCompleted(final Handler handler) {
        handler.postDelayed(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (sBootHandled) return;
                if (isBootCompleted()) {
                    startOnBoot("sys.boot_completed");
                    return;
                }
                if (++attempts < BOOT_POLL_MAX) handler.postDelayed(this, BOOT_POLL_INTERVAL_MS);
            }
        }, BOOT_POLL_INTERVAL_MS);
    }

    /** {@code sys.boot_completed} 是 1 表示系统已经启动完成。 */
    private static boolean isBootCompleted() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = XposedHelpers.callStaticMethod(systemProperties, "get",
                    "sys.boot_completed", "");
            return "1".equals(value);
        } catch (Throwable throwable) {
            log("[Warning]: [sys.boot_completed] " + throwable);
            return false;
        }
    }

    /** 界面里给这个共享方式勾了「开机自动开启」没有。 */
    private static boolean isAutoStartEnabled(int type) {
        try {
            return MainHook.preferences().getBoolean(AppSettings.autoStartKey(type), false);
        } catch (Throwable throwable) {
            log("[Warning]: [auto start] " + throwable);
            return false;
        }
    }
}
