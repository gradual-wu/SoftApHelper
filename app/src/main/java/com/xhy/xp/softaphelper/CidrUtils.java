package com.xhy.xp.softaphelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IPv4 网段字符串的解析与校验。
 *
 * <p>这里刻意不用 {@code LinkAddress}/{@code IpPrefix}/{@code InetAddresses}：
 * 它们是 @hide API，模块界面进程是普通应用，访问会被 hidden API 限制拦掉。
 */
public class CidrUtils {

    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    public static final int DEFAULT_PREFIX_LENGTH = 24;

    /** 省略前缀长度时按 /24 处理，返回 {@code a.b.c.d/len}，无法解析时返回 null。 */
    public static String normalize(String input) {
        if (input == null) return null;
        String text = input.trim();
        if (text.isEmpty()) return null;

        String addressPart = text;
        int prefixLength = DEFAULT_PREFIX_LENGTH;
        int slash = text.indexOf('/');
        if (slash >= 0) {
            addressPart = text.substring(0, slash).trim();
            String prefixPart = text.substring(slash + 1).trim();
            try {
                prefixLength = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        int[] octets = parseIpv4(addressPart);
        if (octets == null) return null;
        if (prefixLength < 1 || prefixLength > 30) return null;

        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3] + "/" + prefixLength;
    }

    /**
     * 校验输入并返回错误提示，合法时返回 null。
     *
     * <p>热点自身要占用这个地址，所以网络地址（{@code .0}）和广播地址不能用。
     */
    public static String validate(String input) {
        if (input == null || input.trim().isEmpty()) return "请输入网段";

        String text = input.trim();
        int slash = text.indexOf('/');
        String prefixPart = slash < 0 ? null : text.substring(slash + 1).trim();
        if (prefixPart != null) {
            int prefixLength;
            try {
                prefixLength = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                return "前缀长度必须是数字，例如 192.168.43.1/24";
            }
            if (prefixLength < 1 || prefixLength > 30) return "前缀长度只能是 1~30";
        }

        int[] octets = parseIpv4(slash < 0 ? text : text.substring(0, slash).trim());
        if (octets == null) return "只能填 IPv4，例如 192.168.43.1/24";

        String normalized = normalize(text);
        if (normalized == null) return "格式不对，例如 192.168.43.1/24";

        int prefixLength = Integer.parseInt(normalized.substring(normalized.indexOf('/') + 1));
        if (isNetworkAddress(octets, prefixLength)) return "不能用网络地址（末位是 0）";
        if (isBroadcastAddress(octets, prefixLength)) return "不能用广播地址";
        return null;
    }

    /** 去掉 {@code /xx}，只留 IP。 */
    public static String hostPart(String cidr) {
        if (cidr == null) return null;
        int slash = cidr.indexOf('/');
        return slash < 0 ? cidr : cidr.substring(0, slash);
    }

    /** 网段地址（主机位清零）：{@code 192.168.43.1/24 → 192.168.43.0/24}。 */
    public static String networkCidr(String cidr) {
        String normalized = normalize(cidr);
        if (normalized == null) return null;
        int prefixLength = prefixLength(normalized);
        int network = address(normalized) & mask(prefixLength);
        return (network >>> 24) + "." + ((network >>> 16) & 0xff) + "."
                + ((network >>> 8) & 0xff) + "." + (network & 0xff) + "/" + prefixLength;
    }

    /**
     * 两个网段是否重叠。
     *
     * <p>按较短的那个前缀取掩码再比网络号，例如 {@code 192.168.43.1/24} 和
     * {@code 192.168.43.128/25} 算重叠。
     */
    public static boolean overlaps(String cidrA, String cidrB) {
        String a = normalize(cidrA);
        String b = normalize(cidrB);
        if (a == null || b == null) return false;
        int mask = mask(Math.min(prefixLength(a), prefixLength(b)));
        return (address(a) & mask) == (address(b) & mask);
    }

    private static int prefixLength(String cidr) {
        return Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
    }

    private static int address(String cidr) {
        return toInt(parseIpv4(hostPart(cidr)));
    }

    private static int mask(int prefixLength) {
        return prefixLength == 0 ? 0 : (-1 << (32 - prefixLength));
    }

    private static int[] parseIpv4(String address) {
        Matcher matcher = IPV4.matcher(address);
        if (!matcher.matches()) return null;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            int value;
            try {
                value = Integer.parseInt(matcher.group(i + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || value > 255) return null;
            octets[i] = value;
        }
        return octets;
    }

    private static int toInt(int[] octets) {
        return (octets[0] << 24) | (octets[1] << 16) | (octets[2] << 8) | octets[3];
    }

    private static boolean isNetworkAddress(int[] octets, int prefixLength) {
        int mask = prefixLength == 0 ? 0 : (-1 << (32 - prefixLength));
        return (toInt(octets) & mask) == toInt(octets);
    }

    private static boolean isBroadcastAddress(int[] octets, int prefixLength) {
        if (prefixLength >= 31) return false;
        int mask = -1 << (32 - prefixLength);
        return (toInt(octets) | mask) == toInt(octets);
    }
}
