import com.ninja.kmap.Subnet;
import com.ninja.kmap.Subnet.IpPrefix;

import java.util.List;

/**
 * Subnet 的自测。**跑的是产品代码本身**，不是抄一份 —— 抄一份就等于
 * "两份实现各自正确、彼此不一致，而且不会报错"（本项目栽过这个）。
 *
 * 本机没有 Android SDK，但这个文件不需要：`Subnet` 刻意不 import 任何 android.*。
 * 所以这段算术是全工程唯一能在**没有设备、没有 SDK** 的情况下真跑一遍的东西，
 * CI 里也当一道关卡跑（比编译 APK 快几个数量级）。
 *
 *   cd kmap-shell
 *   javac -encoding UTF-8 -d build/check \
 *       app/src/main/java/com/ninja/kmap/Subnet.java tools/SubnetCheck.java
 *   java -Dfile.encoding=UTF-8 -cp build/check SubnetCheck
 */
public class SubnetCheck {

    static int bad = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  ✓ " : "  ✗ ") + msg);
        if (!ok) bad++;
    }

    public static void main(String[] args) {
        List<String> r;

        // ---- 1. 家里最常见的 /24 ----
        r = Subnet.addressesInPrefix(new IpPrefix("10.0.0.24", 24));
        check(r.size() == 254, "/24 -> 254 个地址（实际 " + r.size() + "）");
        check(r.get(0).equals("10.0.0.1") && r.get(253).equals("10.0.0.254"),
                "/24 -> 10.0.0.1 … 10.0.0.254（实际 " + r.get(0) + " … " + r.get(253) + "）");
        // 这台电脑上一次的地址。写死它是因为 IP 真的变过（随机 MAC），
        // 而"找不到服务"最可能的原因就是它。
        check(r.contains("10.0.0.205"), "/24 -> 含 10.0.0.205（这台电脑上回的地址）");
        check(!r.contains("10.0.0.0") && !r.contains("10.0.0.255"),
                "/24 -> 不含网络号和广播地址");

        // ---- 2. ★ 刚修掉的那个 bug：/16 的网里必须扫到**自己那一块** ----
        r = Subnet.addressesInPrefix(new IpPrefix("10.0.200.24", 16));
        check(r.size() == 8 * 254, "/16 -> 封顶 8 块 = 2032 个（实际 " + r.size() + "）");
        check(r.contains("10.0.200.24"),
                "★ /16 -> 含自己 10.0.200.24（从网段头部开扫就会漏掉它）");
        check(r.contains("10.0.200.1"), "/16 -> 含自己那块的第一个地址");

        // ---- 3. /20：16 块封顶成 8 ----
        r = Subnet.addressesInPrefix(new IpPrefix("10.0.200.24", 20));
        check(r.size() == 8 * 254, "/20 -> 16 块封顶成 8 块 = 2032（实际 " + r.size() + "）");
        check(r.contains("10.0.200.24"), "/20 -> 含自己");

        // ---- 4. /16 网的边界：自己落在最后一块也要能绕到 ----
        r = Subnet.addressesInPrefix(new IpPrefix("10.0.255.7", 16));
        check(r.contains("10.0.255.7"), "/16 且自己在最后一块 -> 含自己");
        r = Subnet.addressesInPrefix(new IpPrefix("10.0.0.7", 16));
        check(r.contains("10.0.0.7"), "/16 且自己在第一块 -> 含自己");

        // ---- 5. 不合法输入不许抛异常（抛了 app 就崩在启动路径上） ----
        check(Subnet.addressesInPrefix(new IpPrefix("不是ip", 24)).size() >= 1,
                "非法地址 -> 不抛异常");
        check(Subnet.addressesInPrefix(new IpPrefix("10.0.0.24", 33)).size() >= 1,
                "前缀长度 33（不可能的值）-> 不抛异常");

        // ---- 6. 地址换算本身 ----
        check(Subnet.ipv4ToLong("0.0.0.0") == 0L, "0.0.0.0 -> 0");
        check(Subnet.ipv4ToLong("255.255.255.255") == 0xFFFFFFFFL, "255.255.255.255 -> 全 1");
        check(Subnet.longToIpv4(Subnet.ipv4ToLong("10.0.0.205")).equals("10.0.0.205"),
                "换算往返一致");

        System.out.println(bad == 0 ? "\n子网算术全部通过" : "\n" + bad + " 项不合格");
        System.exit(bad == 0 ? 0 : 1);
    }
}
