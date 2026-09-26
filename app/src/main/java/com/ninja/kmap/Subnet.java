package com.ninja.kmap;

import java.util.ArrayList;
import java.util.List;

/**
 * 子网地址的算术。
 *
 * ★ 单独一个类、**不 import 任何 android.***，是有意的：
 *   本机没有 Android SDK，只有这部分能用 `javac` 真跑一遍
 *   （见 tools/SubnetCheck.java，CI 里也当一道关卡）。
 *   放进 ServerFinder 就只能靠肉眼看 —— 而这段算术恰恰是"看一眼觉得对、
 *   跑起来扫不到自己"的那一类。
 */
public final class Subnet {

    /** 网段比 /24 还大时最多扫这么多块，免得 /16 变成 6 万次连接。 */
    public static final int MAX_BLOCKS = 8;

    private Subnet() {}

    public static final class IpPrefix {
        public final String ip;
        public final int prefix;
        public IpPrefix(String ip, int prefix) { this.ip = ip; this.prefix = prefix; }
    }

    /**
     * 本机所在网段里所有可扫的地址（跳过网络号和广播地址）。
     *
     * ★ 从**自己那一块**开始绕圈，不是从网段头部开始。
     *   家里的网是 /24，这一条无所谓；但碰上 /16 的网，
     *   从头部开扫就只能扫到别人的 8 块，**永远扫不到自己**。
     */
    public static List<String> addressesInPrefix(IpPrefix self) {
        List<String> out = new ArrayList<>();
        try {
            long ip = ipv4ToLong(self.ip);
            int prefix = Math.max(self.prefix, 16);   // /16 以下按 /16 算，别扫 6 万个
            long mask = (1L << (32 - prefix)) - 1;
            long netBase = ip & ~mask;
            long totalBlocks = 1L << (24 - prefix);
            long baseIdx = netBase >> 8;                     // 网段里第一块的绝对序号
            long ownOffset = (ip >> 8) - baseIdx;            // 我们自己那一块的序号
            long blocks = Math.min(totalBlocks, MAX_BLOCKS);
            for (long i = 0; i < blocks; i++) {
                long netStart = (baseIdx + ((ownOffset + i) % totalBlocks)) << 8;
                for (long h = 1; h <= 254; h++) out.add(longToIpv4(netStart + h));
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) out.add(self.ip);
        return out;
    }

    public static long ipv4ToLong(String ip) {
        String[] p = ip.split("\\.");
        long v = 0;
        for (int i = 0; i < 4; i++) v = (v << 8) | (Long.parseLong(p[i]) & 0xFF);
        return v;
    }

    public static String longToIpv4(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "."
                + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
