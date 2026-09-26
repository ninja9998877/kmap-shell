package com.ninja.kmap;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import com.ninja.kmap.Subnet.IpPrefix;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 找电脑上的 kmap 服务。
 *
 * ★ 为什么不用 mDNS/Bonjour（这是个深思过的决定，别随手改回去）：
 *
 *  1. 这台电脑的防火墙只放行了 **TCP 8890**，没放 UDP 5353。而且现在能通是"碰巧"，
 *     靠的是一条按 python.exe 全路径放行的宽规则 —— 路径一变（升级 Python、
 *     换虚拟环境）就同时断掉发现和连接，你会在这两个症状之间反复横跳。
 *  2. 主机名解析出来是 Tailscale 的 100.81.230.99，zeroconf 默认会把所有网卡地址
 *     都广播出去。平板拿到那个地址**永远连不上**，而现象是"明明找到了服务却打不开"。
 *  3. 最要命的一条：**mDNS 失败时无法区分"没找到服务"和"网络根本不通"**。
 *     NsdManager 只会告诉你"没有服务"，原因它不知道。
 *
 * 扫子网没有这三个问题，而且服务端一行都不用改。它还**天然给出三态**：
 *   - 有人应答了但端口是关的（收到 RST）  → 网络通，是 serve.py 没跑
 *   - 所有地址都超时                      → 不在同一个 WiFi / 路由器开了 AP 隔离
 *   - 有人应答 8890 但不是 kmap           → 端口被别的东西占了
 *
 * 命中之后**必须**用 /api/health 验明正身：同网段里任何开 8890 的东西都会
 * 接受 TCP 连接，只看"端口通不通"会连错。
 */
public final class ServerFinder {

    public static final int PORT = 8890;

    /** 扫一个地址的等待时间。局域网里通常 <20ms，400ms 已经非常宽松。 */
    private static final int SCAN_TIMEOUT_MS = 400;
    /** 并发度。254 个地址 / 48 并发 * 0.4s ≈ 最坏 2 秒。 */
    private static final int SCAN_THREADS = 48;
    private static final int PROBE_CONNECT_MS = 1500;
    private static final int PROBE_READ_MS = 2500;

    private ServerFinder() {}

    /** 连不上的具体原因。**每一个都要能翻译成一句人话**，不能笼统叫"失败"。 */
    public enum Why {
        OK,
        /** 地址本身不合法。 */
        BAD_ADDRESS,
        /** 地址上没人应答。 */
        UNREACHABLE,
        /** 主机在，但 8890 上没东西在听 —— serve.py 没跑。 */
        NO_SERVER,
        /** 有人听 8890，但不是 kmap（端口被别的东西占了）。 */
        PORT_TAKEN,
        /** 服务在，但把我们挡了 —— 网段白名单。正文里写清了原因。 */
        FORBIDDEN,
        /** 整个网段都没人应答 —— 多半不在同一个 WiFi。 */
        WRONG_NETWORK
    }

    public static final class Result {
        public final Why why;
        /** 探测用的地址，形如 http://10.0.0.24:8890。失败时是"试过的那个"。 */
        public final String url;
        /** 服务端自己说的话，原样照抄。403 时是那段写明"哪台设备被挡"的正文。 */
        public final String serverMsg;
        /** /api/health 报的进度文件名。能一眼看出是不是连到了测试用的 scratch 进度。 */
        public final String progressFile;
        public final boolean scratch;

        Result(Why why, String url, String serverMsg, String progressFile, boolean scratch) {
            this.why = why;
            this.url = url;
            this.serverMsg = serverMsg;
            this.progressFile = progressFile;
            this.scratch = scratch;
        }

        public boolean ok() { return why == Why.OK; }
    }

    public interface Callback {
        /** tried/total 用来告诉用户"在动"，不是卡住了。 */
        void onProgress(int tried, int total);
        boolean isCancelled();
        void onDone(Result r);
    }

    // ------------------------------------------------------------ 探测单个地址

    /** 把用户随手输的东西变成 http://host:port。 */
    public static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        // 只给了 IP 没给端口 -> 补上默认端口。手输时最容易漏这个。
        if (s.substring(s.indexOf("://") + 3).indexOf(':') < 0) s = s + ":" + PORT;
        return s;
    }

    /**
     * 问一句 /api/health，按结果分类。
     *
     * 这是整个外壳唯一真正"判断"的地方 —— 失败界面上的每一句话都由这里的原因决定。
     */
    public static Result probe(String base) {
        String url = normalize(base);
        if (url == null) return new Result(Why.BAD_ADDRESS, base, null, null, false);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url + "/api/health").openConnection();
            conn.setConnectTimeout(PROBE_CONNECT_MS);
            conn.setReadTimeout(PROBE_READ_MS);
            conn.setRequestProperty("Accept", "application/json");
            // 服务端发的是 no-store，但中间层可能缓存 —— 探测必须拿到当下的真相。
            conn.setUseCaches(false);
            int code = conn.getResponseCode();

            if (code == 403) {
                // ★ 这段正文是 kmap 里写得最好的一段诊断信息：它写明**是哪台设备被挡、
                //    允许的网段是什么**。原样显示，不要自己改写成"服务器拒绝访问"。
                return new Result(Why.FORBIDDEN, url, readBody(conn, true), null, false);
            }
            if (code != 200) {
                return new Result(Why.PORT_TAKEN, url,
                        "这个地址上的服务回了 HTTP " + code + "，不是 kmap。", null, false);
            }
            String body = readBody(conn, false);
            JSONObject o = new JSONObject(body == null ? "" : body);
            if (!o.optBoolean("ok")) {
                return new Result(Why.PORT_TAKEN, url,
                        "这个地址上的服务没说自己是谁：\n" + body, null, false);
            }
            return new Result(Why.OK, url, null,
                    o.optString("progress_file", ""), o.optBoolean("scratch"));

        } catch (UnknownHostException e) {
            return new Result(Why.BAD_ADDRESS, url, "认不出这个地址。", null, false);
        } catch (ConnectException e) {
            // 收到 RST：这台机器活着，是 8890 上没人听。
            return new Result(Why.NO_SERVER, url, null, null, false);
        } catch (SocketTimeoutException e) {
            return new Result(Why.UNREACHABLE, url, null, null, false);
        } catch (Exception e) {
            // 200 但正文不是 JSON（例如某个路由器管理页、或者别的服务），也走这里。
            return new Result(Why.PORT_TAKEN, url,
                    "连上了，但回的东西看不懂：\n" + e.getClass().getSimpleName()
                            + ": " + e.getMessage(), null, false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn, boolean error) {
        InputStream in = null;
        try {
            in = error ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            // 设个上限：万一连到的是个流式服务，别把内存读爆。
            while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ 扫子网

    /**
     * 在平板自己所在的网段里找 kmap。**在后台线程调用。**
     *
     * 步骤：拿自己的地址 -> 扫 254 个地址的 8890 -> 对每个"端口开着"的候选
     * 用 /api/health 验明正身 -> 第一个验过的就是它。
     */
    public static void scan(Context ctx, Callback cb) {
        IpPrefix self = selfAddress(ctx);
        if (self == null) {
            cb.onDone(new Result(Why.UNREACHABLE, null,
                    "读不到本机地址 —— 平板现在可能没连上 WiFi。", null, false));
            return;
        }

        // 地址算术在 Subnet 里（那个类不依赖 Android，有独立自测）。
        List<String> targets = Subnet.addressesInPrefix(self);
        int total = targets.size();
        AtomicInteger tried = new AtomicInteger();
        AtomicBoolean anyRefused = new AtomicBoolean(false);
        ConcurrentLinkedQueue<String> open = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);

        try {
            CountDownLatch latch = new CountDownLatch(total);
            for (String ip : targets) {
                pool.execute(() -> {
                    if (cb.isCancelled()) { latch.countDown(); return; }
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(ip, PORT), SCAN_TIMEOUT_MS);
                        open.add(ip);               // 8890 上有人在
                    } catch (ConnectException e) {
                        anyRefused.set(true);       // 这台机器活着，只是端口关着
                    } catch (Exception ignored) {
                        // 超时 / 不可达：这台机器当它不存在
                    } finally {
                        try { s.close(); } catch (Exception ignored) {}
                        cb.onProgress(tried.incrementAndGet(), total);
                        latch.countDown();
                    }
                });
            }
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        if (cb.isCancelled()) return;

        // 对每个候选验明正身。**必须验** —— 同网段里任何开 8890 的东西都会接受连接。
        Result lastReject = null;
        for (String ip : open) {
            Result r = probe(ip);
            if (r.ok() || r.why == Why.FORBIDDEN) { cb.onDone(r); return; }  // 找到了（或被挡了）
            lastReject = r;
        }
        if (lastReject != null) { cb.onDone(lastReject); return; }

        // 一个 8890 都没开。用"有没有收到 RST"区分两种完全不同的情况 ——
        // 这是 mDNS 给不了的信息，也是这个方案比它值得的地方。
        if (anyRefused.get()) {
            cb.onDone(new Result(Why.NO_SERVER, null,
                    "同一个网络里有别的设备在应答，但没有一台在 8890 上开着 kmap。",
                    null, false));
        } else {
            cb.onDone(new Result(Why.WRONG_NETWORK, null,
                    "整个网段（" + self.ip + "/" + self.prefix + "）一个设备都没应答。",
                    null, false));
        }
    }

    // ------------------------------------------------------------ 本机地址

    /**
     * 平板自己的 IPv4 和前缀长度。
     *
     * 用 ConnectivityManager.getLinkProperties()，**不用** WifiManager.getConnectionInfo()
     * .getIpAddress() —— 后者早就废弃了，而且拿不到前缀长度。
     */
    static IpPrefix selfAddress(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network net = cm.getActiveNetwork();
            if (net == null) return null;
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                    return new IpPrefix(a.getHostAddress(), la.getPrefixLength());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
