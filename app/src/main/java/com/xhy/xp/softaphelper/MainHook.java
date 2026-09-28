package com.xhy.xp.softaphelper;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.os.Build;
import android.util.SparseIntArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "SoftApHelper";

    private static final String className_P = "com.android.server.connectivity.tethering.TetherInterfaceStateMachine";
    private static final String className_Q = "android.net.ip.IpServer";

    private static final String methodName_P_Q = "getRandomWifiIPv4Address";
    private static final String methodName_R = "requestIpv4Address";

    private static final String callerMethodName_Q = "configureIPv4";

    // TetheringType
    public static final int TETHERING_INVALID = -1;
    public static final int TETHERING_WIFI = 0;
    public static final int TETHERING_USB = 1;
    public static final int TETHERING_BLUETOOTH = 2;
    public static final int TETHERING_WIFI_P2P = 3;
    public static final int TETHERING_NCM = 4;
    public static final int TETHERING_ETHERNET = 5;
    public static final int TETHERING_WIGIG = 6;

    // staticBSSID Switch
    private static final boolean shouldStaticBSSID = false;

    private static volatile XSharedPreferences sPreferences;

    public static final int CHANNEL_WIDTH_320MHZ = 11;
    // channel: 149,153,157,161,165
    // freq:    5745,5765,5785,5805,5825
    private static HashSet<Integer> AvailableChannelSet_LOW = new HashSet<>(Arrays.asList(36, 40, 44));
    private static HashSet<Integer> AvailableChannelSet_HIGH = new HashSet<>(Arrays.asList(149, 153, 157, 161, 165));
//    private static HashSet<Integer> AvailableChannelFreqSet = new HashSet<>(Arrays.asList(5745, 5765, 5785, 5805, 5825));

    // SoftApConfiguration 构造函数的下标。Android 13~16 这几个参数的位置没有变过，
    // 新版本新增的字段一律追加在末尾（Android 16 新增了最后两个 boolean）。
    private static final int ARG_INDEX_BSSID = 1;
    private static final int ARG_INDEX_CHANNELS = 4;
    private static final int ARG_INDEX_ALLOWED_ACS_CHANNELS_5G = 21;
    private static final int ARG_INDEX_MAX_CHANNEL_BANDWIDTH = 23;
    // 除主构造函数外，SoftApConfiguration 还有参数更少的重载，下标会越界，需要跳过
    private static final int MIN_SOFT_AP_CONFIGURATION_ARGS = ARG_INDEX_MAX_CHANNEL_BANDWIDTH + 1;

    private static void log(String message) {
        XposedBridge.log("[" + TAG + "] " + message);
    }

    /**
     * 读界面里保存的配置（{@link TetheringRestarter} 也用它读「开机自动开启」的开关）。
     *
     * <p>每次调用都 reload()：文件变了（用户在界面里保存过）下次开热点就用新值，
     * 不用等进程重启。reload() 内部先 stat 比对时间戳，没变就不会重新解析。
     */
    static XSharedPreferences preferences() {
        XSharedPreferences preferences = sPreferences;
        if (preferences == null) {
            preferences = new XSharedPreferences(
                    MainHook.class.getPackage().getName(), AppSettings.PREFS_NAME);
            sPreferences = preferences;
        }
        preferences.reload();
        return preferences;
    }

    /** 取某个共享方式要用的网段，没配过就用默认值。 */
    private static String resolveAddress(int interfaceType) {
        String defaultAddress = AppSettings.defaultAddress(interfaceType);
        if (defaultAddress == null) return null;
        try {
            String configured = preferences().getString(AppSettings.key(interfaceType), defaultAddress);
            String normalized = CidrUtils.normalize(configured);
            return normalized != null ? normalized : defaultAddress;
        } catch (Throwable throwable) {
            log("[Warning]: [preferences] " + throwable);
            return defaultAddress;
        }
    }

    /** 界面里把这个共享方式的 IPv6 中继关掉了没有。 */
    private static boolean isIpv6RelayDisabled(int interfaceType) {
        try {
            return preferences().getInt(AppSettings.ipv6RelayKey(interfaceType),
                    AppSettings.IPV6_RELAY_FOLLOW_SYSTEM) == AppSettings.IPV6_RELAY_DISABLED;
        } catch (Throwable throwable) {
            log("[Warning]: [ipv6 relay] " + throwable);
            return false;
        }
    }

    /** 用户选了 5G + 自动信道时，把 ACS 锁到 149~165（和文档里承诺的一致）。 */
    private static boolean shouldLock5gAcs() {
        try {
            XSharedPreferences preferences = preferences();
            if (preferences.getInt(AppSettings.KEY_WIFI_BAND, AppSettings.BAND_FOLLOW_SYSTEM)
                    != AppSettings.BAND_5GHZ) {
                return false;
            }
            return preferences.getInt(AppSettings.KEY_WIFI_CHANNEL, AppSettings.CHANNEL_AUTO)
                    == AppSettings.CHANNEL_AUTO;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * 频段/信道覆盖。
     *
     * <p>SoftApConfiguration 构造一次之后由 WifiApConfigStore 缓存复用，改完界面再开热点，
     * 用的还是启动时那个对象，所以只改构造函数参数是不够的，必须 hook getter：
     * Wi-Fi 模块在 {@code HostapdHalAidlImp.prepareNetworkParams} 里就是通过
     * {@code getChannels()} / {@code getAllowedAcsChannels()} / {@code getMaxChannelBandwidth()}
     * 拿频段和信道的。
     *
     * <p>这几个方法都是 @hide，所以用反射找，找不到就只记日志。
     */
    private void hookSoftApBand() {
        try {
            Method getChannels = SoftApConfiguration.class.getDeclaredMethod("getChannels");
            XposedBridge.hookMethod(getChannels, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    SparseIntArray configured = resolveChannels();
                    if (configured != null) param.setResult(configured);
                }
            });
            log("[Success]: [SoftApConfiguration.getChannels] hooked");
        } catch (Throwable throwable) {
            log("[Error]: [hook getChannels] " + throwable);
        }

        try {
            Method getAllowedAcsChannels =
                    SoftApConfiguration.class.getDeclaredMethod("getAllowedAcsChannels", int.class);
            XposedBridge.hookMethod(getAllowedAcsChannels, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ((int) param.args[0] != AppSettings.BAND_5GHZ) return;
                    if (!shouldLock5gAcs()) return;
                    param.setResult(new int[]{149, 153, 157, 161, 165});
                }
            });
        } catch (Throwable throwable) {
            log("[Error]: [hook getAllowedAcsChannels] " + throwable);
        }

        try {
            Method getMaxChannelBandwidth =
                    SoftApConfiguration.class.getDeclaredMethod("getMaxChannelBandwidth");
            XposedBridge.hookMethod(getMaxChannelBandwidth, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (shouldLock5gAcs()) param.setResult(CHANNEL_WIDTH_320MHZ);
                }
            });
        } catch (Throwable throwable) {
            log("[Error]: [hook getMaxChannelBandwidth] " + throwable);
        }
    }

    /**
     * 界面上配置的频段/信道，用来改写 SoftApConfiguration 的 channels 参数。
     *
     * <p>返回 null 表示不改写（跟随系统）。选了 5G 之后 channels 的 key 就等于
     * {@code BAND_5GHZ}，下面锁定 ACS 频段那段逻辑才能命中——这正是双频"自动"
     * （key 是 {@code BAND_2GHZ|BAND_5GHZ}）时锁不上的原因。
     */
    private static SparseIntArray resolveChannels() {
        try {
            XSharedPreferences preferences = preferences();
            int band = preferences.getInt(AppSettings.KEY_WIFI_BAND, AppSettings.BAND_FOLLOW_SYSTEM);
            if (band != AppSettings.BAND_2GHZ && band != AppSettings.BAND_5GHZ
                    && band != AppSettings.BAND_2GHZ_5GHZ) {
                return null;
            }

            SparseIntArray channels = new SparseIntArray(1);
            if (band == AppSettings.BAND_2GHZ_5GHZ) {
                // 双频由系统 ACS 选信道，和系统默认配置一致（key = 1|2 = 3）
                channels.put(AppSettings.BAND_2GHZ_5GHZ, AppSettings.CHANNEL_AUTO);
                return channels;
            }

            int channel = preferences.getInt(AppSettings.KEY_WIFI_CHANNEL, AppSettings.CHANNEL_AUTO);
            if (channel != AppSettings.CHANNEL_AUTO && !AppSettings.isChannelInBand(band, channel)) {
                log("[Warning]: channel " + channel + " is not in band " + band + ", fallback to auto.");
                channel = AppSettings.CHANNEL_AUTO;
            }
            channels.put(band, channel);
            return channels;
        } catch (Throwable throwable) {
            log("[Warning]: [channels] " + throwable);
            return null;
        }
    }

    private boolean isConflictPrefix(Class<?> klass, Object thiz, IpPrefix prefix) throws Exception {
        Field field_mPrivateAddressCoordinator = ReflectUtils.findField(klass, "mPrivateAddressCoordinator");
        // Android 16 起 IpServer 不再持有 PrivateAddressCoordinator（地址改由 RoutingCoordinator 分配），
        // 取不到就当作不冲突
        if (field_mPrivateAddressCoordinator == null) {
            log("[Warning]: [" + prefix + "] field_mPrivateAddressCoordinator not found, skip conflict check.");
            return false;
        }
        Object mPrivateAddressCoordinator = field_mPrivateAddressCoordinator.get(thiz);
        Class<?> privateAddressCoordinator = mPrivateAddressCoordinator.getClass();
        // Android 12+
        Method m_getConflictPrefix = ReflectUtils.findMethod(privateAddressCoordinator, "getConflictPrefix");
        if (m_getConflictPrefix != null) {
            return m_getConflictPrefix.invoke(mPrivateAddressCoordinator, prefix) != null;
        }

        // Android 11
        Method m_isDownstreamPrefixInUse = ReflectUtils.findMethod(privateAddressCoordinator, "isDownstreamPrefixInUse");
        Method m_isConflictWithUpstream = ReflectUtils.findMethod(privateAddressCoordinator, "isConflictWithUpstream");
        if (m_isDownstreamPrefixInUse != null && m_isConflictWithUpstream != null) {
            return (boolean) m_isDownstreamPrefixInUse.invoke(mPrivateAddressCoordinator, prefix) ||
                    (boolean) m_isConflictWithUpstream.invoke(mPrivateAddressCoordinator, prefix);
        }

        log("[Error]: [isConflictPrefix] method not found.");
        return false;
    }

    private void hookRequestIpv4Address(final Class<?> klass, Method method,
                                        final Constructor<?> ctor_LinkAddress,
                                        final Constructor<?> ctor_IpPrefix,
                                        final String className) {
        XposedBridge.hookMethod(method,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);

//                        log("[Success Hook]: [" + className + "] " + StackUtils.getStackTraceString());

                        Field field_mInterfaceType = ReflectUtils.findField(klass, "mInterfaceType");
                        int mInterfaceType = 0;
                        if (field_mInterfaceType == null) {
                            // avoid exception
                            log("[Warning]: field_mInterfaceType not found, assuming TETHERING_WIFI.");
                        } else {
                            mInterfaceType = field_mInterfaceType.getInt(param.thisObject);
                        }

                        String address = resolveAddress(mInterfaceType);

                        // 只在 configureIPv4 里替换，换前缀(handleNewPrefixRequest)时不动
                        if (address != null && StackUtils.isCallingFrom(className, callerMethodName_Q)) {
                            final LinkAddress mLinkAddress = (LinkAddress) ctor_LinkAddress.newInstance(address);
                            final IpPrefix prefix = (IpPrefix) ctor_IpPrefix.newInstance(address);

                            if (isConflictPrefix(klass, param.thisObject, prefix)) {
                                log("[Warning]: [" + address + "] isConflictPrefix! do not replace.");
                            } else {
                                log("[Success Edit]: " + address
                                        + " (interfaceType " + mInterfaceType + ")");
                                param.setResult(mLinkAddress);
                            }
                        }
                    }
                });
    }

    /**
     * 关掉 IPv6 中继。
     *
     * <p>IpServer 拿这个方法收到的「上游 IPv6-only LinkProperties」构造 RaParams，交给
     * RouterAdvertisementDaemon 发 RA —— 客户端的全局 IPv6 就是这么来的；IPv6 的转发规则
     * 也是在这一步按上游前缀下发的。把参数置成 null 就切到「上游没有 IPv6」这条路径：
     * RA 里不带前缀、不带默认路由，已经发出去过的前缀还会按 lifetime 0 作废，
     * 转发规则（BPF offload / ip6tables）也一并清掉，客户端只剩 link-local。
     *
     * <p>这条路径是 AOSP 自己在用的正常状态，不是硬造出来的中间态：上游 IPv6 掉线时
     * {@code IPv6TetheringCoordinator.stopIPv6TetheringOn()} 发的就是 null。
     *
     * <p>方法名安卓 9~16 没变过，只有参数个数不同（9/10 是 {@code (LinkProperties)}，
     * 11+ 多一个 {@code ttlAdjustment}），所以按名字找、只改 {@code args[0]} 就够了。
     */
    private void hookUpstreamIpv6(final Class<?> klass, String className, String processName) {
        Method method = ReflectUtils.findMethod(klass, "updateUpstreamIPv6LinkProperties");
        if (method == null) {
            log("[Error]: [updateUpstreamIPv6LinkProperties] not found in class " + klass.getName());
            return;
        }

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (param.args.length == 0 || param.args[0] == null) return;

                Field field_mInterfaceType = ReflectUtils.findField(klass, "mInterfaceType");
                int interfaceType = TETHERING_WIFI;
                if (field_mInterfaceType == null) {
                    log("[Warning]: field_mInterfaceType not found, assuming TETHERING_WIFI.");
                } else {
                    interfaceType = field_mInterfaceType.getInt(param.thisObject);
                }

                if (!isIpv6RelayDisabled(interfaceType)) return;

                // 上游本来有 IPv6，这里让它以为没有：不发前缀、不建转发规则
                param.args[0] = null;
                log("[Success Edit]: disable IPv6 relay (interfaceType " + interfaceType + ")");
            }
        });
        log("[Success]: [updateUpstreamIPv6LinkProperties] found in " + processName);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        ClassLoader classLoader = lpparam.classLoader;

        // system_server 里额外装一个「重启共享」的入口：界面保存网段后可以自动开关一次共享
        if ("android".equals(lpparam.packageName)) {
            TetheringRestarter.install(classLoader);
        }

        // 固定热点ip
        final String className = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ? className_P :
                className_Q;
        final String methodName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? methodName_R :
                methodName_P_Q;

        // 安卓9-10
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // 安卓框架
            try {
                findAndHookMethod(className, classLoader, methodName,
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                // 安卓9/10 这里返回的是不带前缀长度的字符串地址
                                String address = resolveAddress(TETHERING_WIFI);
                                return address == null ? null : CidrUtils.hostPart(address);
                            }
                        });
            } catch (Throwable throwable) {
                log("[Error]: [hook " + methodName + "] in " + lpparam.processName + ": " + throwable);
            }
        }
        // 安卓11+（安卓14/15/16 的 hook 点相同：函数名 requestIpv4Address 未变，
        // 只有参数在安卓14 变成 (int scope, boolean useLastAddress)）
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Class<?> klass;
                try {
                    klass = classLoader.loadClass(className);
                } catch (ClassNotFoundException e) {
                    // 绝大多数进程（系统框架、设置等）里没有 Tethering 的代码，跳过就行
                    klass = null;
                    log("[Skip]: [" + className + "] not found in " + lpparam.processName);
                }

                if (klass != null) {
                    Constructor<?> ctor_LinkAddress = LinkAddress.class.getDeclaredConstructor(String.class);
                    Constructor<?> ctor_IpPrefix = IpPrefix.class.getDeclaredConstructor(String.class);

                    Method method = ReflectUtils.findMethod(klass, methodName);
                    if (method == null) {
                        // 只跳过固定IP，后面的5G信道/隐藏热点类型还要继续hook
                        log("[Error]: [" + methodName + "] not found in class " + klass.getName()
                                + " of " + lpparam.processName);
                    } else {
                        log("[Success]: [" + methodName + "] found in " + lpparam.processName);
                        hookRequestIpv4Address(klass, method, ctor_LinkAddress, ctor_IpPrefix, className);
                    }
                }
            } catch (Throwable throwable) {
                log("[Error]: [hook " + methodName + "] in " + lpparam.processName + ": " + throwable);
            }
        }

        // 关闭 IPv6 中继（安卓 9~16）
        // 安卓 9 的类名不一样（见上面的 className），方法名各版本都一样
        try {
            hookUpstreamIpv6(classLoader.loadClass(className), className, lpparam.processName);
        } catch (ClassNotFoundException e) {
            // 和固定IP一样，绝大多数进程里没有 Tethering 的代码，跳过就行
        } catch (Throwable throwable) {
            log("[Error]: [hook updateUpstreamIPv6LinkProperties] in "
                    + lpparam.processName + ": " + throwable);
        }

        //固定5G热点信道 (Android 9-11)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // TODO
        }
        // Android 12
        else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            // TODO
        }
        // Android 13+（含 16）
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                Constructor<?>[] ctors = SoftApConfiguration.class.getDeclaredConstructors();
                if (ctors.length > 0) {
                    log("[Success]: [SoftApConfiguration] found " + ctors.length
                            + " constructors in " + lpparam.processName);
                }
                for (Constructor<?> ctor : ctors) {
                    XposedBridge.hookMethod(ctor,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                                    super.beforeHookedMethod(param);

                                    if (param.args.length < MIN_SOFT_AP_CONFIGURATION_ARGS) {
                                        // 参数更少的重载，跳过
                                        return;
                                    }

                                    // staticBSSID
                                    if (shouldStaticBSSID) {
                                        param.args[ARG_INDEX_BSSID] = MacAddress.fromString("aa:bb:cc:dd:ee:ff");
                                    }

                                    SparseIntArray channels = (SparseIntArray) param.args[ARG_INDEX_CHANNELS];
                                    Set<Integer> allowedAcsChannels5g =
                                            (Set<Integer>) param.args[ARG_INDEX_ALLOWED_ACS_CHANNELS_5G];
                                    if (channels == null || allowedAcsChannels5g == null) {
                                        return;
                                    }

                                    // 界面上指定了频段/信道就覆盖掉
                                    SparseIntArray configuredChannels = resolveChannels();
                                    if (configuredChannels != null) {
                                        channels = configuredChannels;
                                        param.args[ARG_INDEX_CHANNELS] = configuredChannels;
                                    }

                                    int channel5gIndex = channels.indexOfKey(AppSettings.BAND_5GHZ);

//                                    int maxChannelBandwidth = (int) param.args[ARG_INDEX_MAX_CHANNEL_BANDWIDTH];
//                                    log("orig channel5gIndex " + channel5gIndex);
//                                    log("orig channels " + channels);
//                                    log("orig allowedAcsChannels5g " + allowedAcsChannels5g);
//                                    log("orig maxChannelBandwidth " + maxChannelBandwidth);

                                    // config has set 5G channel
                                    if (channel5gIndex >= 0) {
                                        int channel = channels.get(AppSettings.BAND_5GHZ);

                                        // 5GHz + allowedAcsChannels5g.size == 0
                                        if (channel == 0 && allowedAcsChannels5g.size() == 0) {
                                            // 5G ACS channels
                                            param.args[ARG_INDEX_ALLOWED_ACS_CHANNELS_5G] = AvailableChannelSet_HIGH;
                                            // max bandwidth
                                            param.args[ARG_INDEX_MAX_CHANNEL_BANDWIDTH] = CHANNEL_WIDTH_320MHZ;
                                        }
                                    }
                                }

                            });
                }

            } catch (Throwable throwable) {
                log("[Error]: [hook SoftApConfiguration] in " + lpparam.processName + ": " + throwable);
            }

            // 频段/信道（界面里配置的 2.4G / 5G / 双频 + 信道）
            hookSoftApBand();
        }

        //隐藏热点类型 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Class<?> klass = classLoader.loadClass("android.net.dhcp.DhcpServingParamsParcelExt");
                Method method = ReflectUtils.findMethod(klass, "setMetered");
                if (method == null) {
                    log("[Error]: [setMetered] not found in class " + klass.getName());
                } else {
                    log("[Success]: [setMetered] found in " + lpparam.processName);
                    XposedBridge.hookMethod(method,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                                    super.beforeHookedMethod(param);
                                    param.args[0] = false;
                                }
                            });
                }
            } catch (ClassNotFoundException e) {
                // DhcpServingParamsParcelExt 只在 Tethering 进程里，其它进程取不到是正常的
                log("[Skip]: [DhcpServingParamsParcelExt] not found in " + lpparam.processName);
            } catch (Throwable throwable) {
                log("[Error]: [hook setMetered] in " + lpparam.processName + ": " + throwable);
            }
        }

        log("[Loaded] " + lpparam.processName + " (SDK " + Build.VERSION.SDK_INT + ")");
    }


}
