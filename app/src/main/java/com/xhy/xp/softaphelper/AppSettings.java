package com.xhy.xp.softaphelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各共享方式的固定网段配置。
 *
 * <p>界面进程用 {@link #getAddress}/{@link #setAddress} 读写，hook 进程用
 * {@code XSharedPreferences} 读同一个文件，见 {@link MainHook#resolveAddress(int)}。
 *
 * <p>写文件时必须带 {@code MODE_WORLD_READABLE}：框架会把模块自身的
 * {@code ContextImpl.getPreferencesDir()} 重定向到框架目录，两个进程才看得到同一份文件。
 */
public class AppSettings {

    public static final String PREFS_NAME = "softap_config";

    /** 界面保存网段后发给 system_server 的广播：让它把对应的共享重启一次。 */
    public static final String ACTION_RESTART_TETHERING =
            "com.xhy.xp.softaphelper.action.RESTART_TETHERING";
    public static final String EXTRA_TETHERING_TYPE = "tethering_type";
    /** 只有持有这个 signature 权限的应用（也就是本模块自己）能触发重启。 */
    public static final String PERMISSION_RESTART_TETHERING =
            "com.xhy.xp.softaphelper.permission.RESTART_TETHERING";

    public static final int TYPE_WIFI = 0;
    public static final int TYPE_USB = 1;
    public static final int TYPE_BLUETOOTH = 2;
    public static final int TYPE_WIFI_P2P = 3;
    public static final int TYPE_ETHERNET = 5;

    /** 频段：跟随系统（默认，不改写），或者 SoftApConfiguration 的 BAND_* 值。 */
    public static final int BAND_FOLLOW_SYSTEM = -1;
    public static final int BAND_2GHZ = 1;
    public static final int BAND_5GHZ = 1 << 1;
    public static final int BAND_2GHZ_5GHZ = BAND_2GHZ | BAND_5GHZ;

    /** 信道：0 表示自动（由系统 ACS 选，5G 会自动锁到 149~165）。 */
    public static final int CHANNEL_AUTO = 0;

    private static final String KEY_PREFIX = "address_";
    public static final String KEY_WIFI_BAND = "wifi_band";
    public static final String KEY_WIFI_CHANNEL = "wifi_channel";

    private static final int[] CHANNELS_2GHZ = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
    private static final int[] CHANNELS_5GHZ = {
            36, 40, 44, 48, 52, 56, 60, 64,
            100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
            149, 153, 157, 161, 165
    };

    private static final Map<Integer, String> DEFAULT_ADDRESS = new LinkedHashMap<>();
    private static final Map<Integer, Integer> TITLE_RES = new LinkedHashMap<>();

    static {
        DEFAULT_ADDRESS.put(TYPE_WIFI, "192.168.43.1/24");
        DEFAULT_ADDRESS.put(TYPE_USB, "192.168.42.1/24");
        DEFAULT_ADDRESS.put(TYPE_BLUETOOTH, "192.168.44.1/24");
        DEFAULT_ADDRESS.put(TYPE_WIFI_P2P, "192.168.49.1/24");
        DEFAULT_ADDRESS.put(TYPE_ETHERNET, "192.168.45.1/24");

        TITLE_RES.put(TYPE_WIFI, R.string.config_wifi);
        TITLE_RES.put(TYPE_USB, R.string.config_usb);
        TITLE_RES.put(TYPE_BLUETOOTH, R.string.config_bluetooth);
        TITLE_RES.put(TYPE_WIFI_P2P, R.string.config_wifi_p2p);
        TITLE_RES.put(TYPE_ETHERNET, R.string.config_ethernet);
    }

    /** 界面上展示的顺序，也是 TetheringType 的顺序。 */
    public static int[] types() {
        int[] types = new int[DEFAULT_ADDRESS.size()];
        int i = 0;
        for (Integer type : DEFAULT_ADDRESS.keySet()) {
            types[i++] = type;
        }
        return types;
    }

    public static boolean isSupported(int type) {
        return DEFAULT_ADDRESS.containsKey(type);
    }

    public static String defaultAddress(int type) {
        return DEFAULT_ADDRESS.get(type);
    }

    public static int titleRes(int type) {
        Integer res = TITLE_RES.get(type);
        return res == null ? 0 : res;
    }

    public static String key(int type) {
        return KEY_PREFIX + type;
    }

    /** 读取用户配置，没配过或者配置已损坏时回落到默认网段。 */
    public static String getAddress(Context context, int type) {
        String defaultAddress = defaultAddress(type);
        if (defaultAddress == null) return null;
        String stored = null;
        try {
            stored = preferences(context).getString(key(type), defaultAddress);
        } catch (Throwable ignored) {
        }
        String normalized = CidrUtils.normalize(stored);
        return normalized != null ? normalized : defaultAddress;
    }

    public static void setAddress(Context context, int type, String cidr) {
        preferences(context).edit().putString(key(type), cidr).apply();
    }

    public static SharedPreferences preferences(Context context) {
        // MODE_WORLD_READABLE 是必须的：hook 在别的进程里读这个文件。
        // 目标 SDK 24+ 时系统本来会拒绝，框架的 checkMode hook 会放行。
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
    }

    // ---------------- WiFi 热点的频段 / 信道 ----------------

    public static int getWifiBand(Context context) {
        try {
            return preferences(context).getInt(KEY_WIFI_BAND, BAND_FOLLOW_SYSTEM);
        } catch (Throwable ignored) {
            return BAND_FOLLOW_SYSTEM;
        }
    }

    public static void setWifiBand(Context context, int band) {
        preferences(context).edit().putInt(KEY_WIFI_BAND, band).apply();
    }

    public static int getWifiChannel(Context context) {
        try {
            return preferences(context).getInt(KEY_WIFI_CHANNEL, CHANNEL_AUTO);
        } catch (Throwable ignored) {
            return CHANNEL_AUTO;
        }
    }

    public static void setWifiChannel(Context context, int channel) {
        preferences(context).edit().putInt(KEY_WIFI_CHANNEL, channel).apply();
    }

    /** 某个频段可选的信道；双频/跟随系统没有可选信道。 */
    public static int[] channelsFor(int band) {
        if (band == BAND_2GHZ) return CHANNELS_2GHZ;
        if (band == BAND_5GHZ) return CHANNELS_5GHZ;
        return new int[0];
    }

    /** 信道是否属于这个频段。 */
    public static boolean isChannelInBand(int band, int channel) {
        for (int candidate : channelsFor(band)) {
            if (candidate == channel) return true;
        }
        return false;
    }
}
