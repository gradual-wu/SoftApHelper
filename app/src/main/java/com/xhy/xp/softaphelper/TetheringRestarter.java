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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 跑在 system_server 里的那部分模块代码。
 *
 * <p>界面保存网段后发一条广播过来，这里把对应类型的共享「关掉再打开」一次，
 * 让新网段立刻生效，省得用户自己去点快捷开关。
 *
 * <p>只重启「当前确实在共享」的类型；没在共享的直接跳过，下次开启时自然用新配置。
 *
 * <p>权限说明：这里调的是 {@link TetheringManager} 的公开 API，需要调用方持有
 * {@code TETHER_PRIVILEGED}。模块的这段代码运行在 system_server（uid 1000），
 * 框架的 {@code ActivityManager.checkComponentPermission} 对 core uid 直接放行，
 * 所以不需要额外授权。广播接收器要求发送方持有本模块定义的 signature 权限，
 * 避免别的应用也能触发重启。
 */
public class TetheringRestarter {

    /** TetheringRequest 的公开 API 从 Android 14 起才有。 */
    private static final int MIN_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_INTERVAL_MS = 500;
    /** 等 Wi-Fi 真正把 AP 关掉再重新开，见 {@link #startAfterStop}。 */
    private static final long START_DELAY_MS = 2500;
    private static final int MAX_START_ATTEMPTS = 4;

    private static final Executor DIRECT_EXECUTOR = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static final Set<Integer> sActiveTypes = new HashSet<>();
    private static volatile TetheringManager sTetheringManager;
    private static volatile Handler sHandler;
    private static BroadcastReceiver sReceiver;
    private static boolean sCallbackRegistered;
    private static boolean sInstalled;

    private static void log(String message) {
        XposedBridge.log("[" + MainHook.TAG + "] " + message);
    }

    public static synchronized void install(final ClassLoader classLoader) {
        if (sInstalled) return;
        sInstalled = true;

        if (Build.VERSION.SDK_INT < MIN_SDK) {
            log("[Skip]: auto restart tethering needs Android 14+.");
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

        if (sReceiver == null) {
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

        return null;
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
                            startAfterStop(manager, handler, request, type);
                        }

                        @Override
                        public void onStopTetheringFailed(int error) {
                            log("[Warning]: stop tethering failed: " + error);
                            startAfterStop(manager, handler, request, type);
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
    private static void startAfterStop(final TetheringManager manager, final Handler handler,
                                       final TetheringManager.TetheringRequest request,
                                       final int type) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startTethering(manager, handler, request, type, 1);
            }
        }, START_DELAY_MS);
    }

    private static void startTethering(final TetheringManager manager, final Handler handler,
                                       final TetheringManager.TetheringRequest request,
                                       final int type, final int attempt) {
        try {
            manager.startTethering(request, DIRECT_EXECUTOR,
                    new TetheringManager.StartTetheringCallback() {
                        @Override
                        public void onTetheringStarted() {
                            log("[Success]: tethering restarted, new address takes effect.");
                        }

                        @Override
                        public void onTetheringFailed(int error) {
                            if (attempt < MAX_START_ATTEMPTS) {
                                log("[Warning]: start attempt " + attempt + " failed (" + error
                                        + "), retrying...");
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        startTethering(manager, handler, request, type, attempt + 1);
                                    }
                                }, START_DELAY_MS);
                            } else {
                                log("[Error]: restart tethering failed after " + attempt
                                        + " attempts: " + error);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            log("[Error]: start tethering: " + throwable);
        }
    }
}
