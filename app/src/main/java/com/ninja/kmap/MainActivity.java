package com.ninja.kmap;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * kmap 的安卓外壳：一个全屏横屏的 WebView，指向电脑上的 serve.py。
 *
 * **这个 app 里没有 kmap 的任何逻辑**，一行都没有。界面、判分、出题全在服务端 ——
 * 所以改 learn.html / 图谱 / 判分规则，平板上刷新一下就是新的，不用重新装 APK。
 * 真正需要重装的只有"外壳自己"变了（图标、横屏、WebView 配置）。
 *
 * ★ 贯穿全文件的一条要求：**任何时刻都不能是一片白**。
 *   WebView 只在页面真的画出来了才揭开，其余所有时刻都盖着诊断面板，
 *   而那块面板会写清楚是**哪一种**连不上。
 *   "失败界面显示不出来"和"失败"没有区别 —— 这个项目反复栽在这一条上。
 */
public class MainActivity extends Activity {

    private static final String PREF = "kmap";
    private static final String KEY_URL = "server_url";
    /** 连不上之后的自动重试间隔。电脑睡醒/服务重启后界面**自己会好**，不用大人。 */
    private static final long RETRY_MS = 3000;
    /** 连地址都还没有时的重扫间隔。扫一次要 1-3 秒，别也按 3 秒来。 */
    private static final long RESCAN_MS = 8000;

    /** 三条家长自己能查的。**不写"请联系管理员"这种没法执行的话。** */
    private static final String CHECKS =
            "可以按顺序查这三样：\n"
            + "1. 平板和电脑连的是不是同一个 WiFi\n"
            + "2. 电脑上那个跑着 serve.py 的窗口还开着吗\n"
            + "3. 电脑是不是睡眠了（睡眠后网络会断，屏幕看着还亮）";

    private FrameLayout root;
    private WebView webView;
    private ScrollView diagView;
    private TextView diagHeadline, diagReason, diagServerMsg, diagChecks, diagHealth, diagBuild;
    private EditText serverUrlInput;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 当前认准的地址。null = 还没有，得靠扫。 */
    private String currentUrl;
    /** 正在扫子网（用来防重入 + 取消）。 */
    private boolean scanning;
    /** 自动重试了几轮。只在**因为连不上**而重试时累加，诊断面板正常打开时不动。 */
    private int retryRound;
    /** 上次连上时 /api/health 报的进度文件名。出问题时显示出来 ——
     *  能一眼看出平板是不是连到了测试用的 scratch 进度。 */
    private String lastProgressFile = "";
    private boolean lastScratch = false;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        diagView = findViewById(R.id.diagView);
        diagHeadline = findViewById(R.id.diagHeadline);
        diagReason = findViewById(R.id.diagReason);
        diagServerMsg = findViewById(R.id.diagServerMsg);
        diagChecks = findViewById(R.id.diagChecks);
        diagHealth = findViewById(R.id.diagHealth);
        diagBuild = findViewById(R.id.diagBuild);
        serverUrlInput = findViewById(R.id.serverUrl);

        diagBuild.setText("构建 " + BuildConfig.BUILD_ID);

        webView = findViewById(R.id.webview);
        setupWebView();

        findViewById(R.id.connectBtn).setOnClickListener(v -> onConnectClicked());
        findViewById(R.id.scanBtn).setOnClickListener(v -> autoFind(true));

        serverUrlInput.setText(getPrefs().getString(KEY_URL, ""));

        // 屏幕常亮：做 5 道题中间黑屏很恼人。不需要任何权限。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台时如果还停在诊断面板上，接着重试（可能大人刚把服务起回来）。
        if (diagView.getVisibility() == View.VISIBLE) scheduleRetry();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRetry();
    }

    @Override
    protected void onDestroy() {
        stopRetry();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private SharedPreferences getPrefs() { return getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    private void save(String url) { getPrefs().edit().putString(KEY_URL, url).apply(); }

    // ------------------------------------------------------------ 启动 / 找服务

    /**
     * 起手顺序：记住的地址 → 编译时写死的地址 → 扫子网 → 手填。
     *
     * 「记住的地址」放最前是有意的：绝大多数时候它是对的，一次探测（1.5 秒超时）
     * 就结束了，不用扫 254 个地址。
     */
    private void start() {
        String saved = getPrefs().getString(KEY_URL, "");
        if (!saved.isEmpty()) {
            connect(saved);
            return;
        }
        if (!BuildConfig.DEFAULT_SERVER.isEmpty()) {
            connect(ServerFinder.normalize(BuildConfig.DEFAULT_SERVER));
            return;
        }
        autoFind(false);
    }

    private void onConnectClicked() {
        String u = ServerFinder.normalize(serverUrlInput.getText().toString());
        if (u == null) {
            headline("还没填地址", "在左边那格里填电脑的地址，例如 10.0.0.24:8890。");
            return;
        }
        retryRound = 0;
        connect(u);
    }

    /** 探测一个已知地址。 */
    private void connect(String url) {
        currentUrl = url;
        scanning = false;
        headline("正在打开…", url + "\n连不上的话这里会说清是为什么。");
        new Thread(() -> {
            ServerFinder.Result r = ServerFinder.probe(url);
            ui.post(() -> onResult(r));
        }, "kmap-probe").start();
    }

    /** 扫子网找。manual=true 表示是用户按了「自动查找」，要给出明确反馈。 */
    private void autoFind(final boolean manual) {
        if (scanning) return;
        scanning = true;
        headline("正在找电脑上的 kmap…",
                manual ? "在同一 WiFi 里挨个问一遍，大概几秒钟。" : null);
        new Thread(() -> ServerFinder.scan(this, new ServerFinder.Callback() {
            @Override public void onProgress(int tried, int total) {
                // 让用户看到"在动"。没这个的话 2-3 秒的扫描看起来像卡死了。
                if (tried % 16 == 0 || tried == total) {
                    ui.post(() -> {
                        if (diagView.getVisibility() == View.VISIBLE) {
                            diagReason.setText("已经问了 " + tried + " / " + total + " 个地址…");
                        }
                    });
                }
            }
            @Override public boolean isCancelled() { return !scanning; }
            @Override public void onDone(ServerFinder.Result r) {
                ui.post(() -> { scanning = false; onResult(r); });
            }
        }), "kmap-scan").start();
    }

    private void onResult(ServerFinder.Result r) {
        if (r.ok()) {
            currentUrl = r.url;
            lastProgressFile = r.progressFile == null ? "" : r.progressFile;
            lastScratch = r.scratch;
            save(r.url);
            serverUrlInput.setText(r.url);
            retryRound = 0;
            healthy = true;
            stopRetry();
            diagView.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(r.url);
            return;
        }
        if (r.url != null) currentUrl = r.url;
        healthy = false;
        showDiag(r);
        scheduleRetry();
    }

    // ------------------------------------------------------------ 显示

    /** 把一次失败翻译成"出了什么事 + 该查什么"。**这句翻译是整个 app 的价值所在。** */
    private void showDiag(ServerFinder.Result r) {
        diagView.setVisibility(View.VISIBLE);
        String addr = currentUrl == null ? "（还没找到地址）" : currentUrl;

        switch (r.why) {
            case NO_SERVER:
                headline("电脑上的服务没在跑",
                        "地址是通的，但那台电脑的 8890 端口上没东西在听。\n"
                        + "多半是 serve.py 没启动，或者起它的那个窗口被关了。");
                break;
            case UNREACHABLE:
                headline("叫了没人应",
                        "往 " + addr + " 发了请求，一直没回。\n"
                        + "常见原因：电脑睡眠了；或者平板和电脑不在同一个 WiFi。");
                break;
            case WRONG_NETWORK:
                headline("不在同一个网络里",
                        "整个网段问了一圈，一个设备都没应答。\n"
                        + "平板连的 WiFi 和电脑连的可能不是同一个 —— 有些路由器把访客网络隔开了。");
                break;
            case FORBIDDEN:
                headline("被电脑挡下来了",
                        "kmap 服务找到了，但它不接受这台设备。具体原因它自己写在下面了。");
                break;
            case PORT_TAKEN:
                headline("这个地址上不是 kmap", "有人占了 8890，但不是 kmap。");
                break;
            case BAD_ADDRESS:
                headline("地址填得不对", "认不出这个地址：" + addr);
                break;
            default:
                headline("连不上", addr);
        }

        // 服务端自己说的话，原样照抄（403 那段尤其别改写 ——
        // 它写明"是哪台设备被挡、允许的网段是什么"，是全项目写得最好的一段诊断信息）。
        if (r.serverMsg != null && !r.serverMsg.isEmpty()) {
            diagServerMsg.setVisibility(View.VISIBLE);
            diagServerMsg.setText(r.serverMsg);
        } else {
            diagServerMsg.setVisibility(View.GONE);
        }

        diagChecksText();
        StringBuilder health = new StringBuilder();
        if (!lastProgressFile.isEmpty()) {
            health.append("上次连上时服务报的进度文件：").append(lastProgressFile);
            if (lastScratch) health.append("   ← 这是**测试用**的临时进度，不是孩子的真实进度");
        }
        if (retryRound > 1) {
            if (health.length() > 0) health.append("\n");
            health.append("已经自动重试 ").append(retryRound - 1)
                  .append(" 次了，每 3 秒一次 —— 服务起回来它自己会好。");
        }
        diagHealth.setVisibility(health.length() == 0 ? View.GONE : View.VISIBLE);
        diagHealth.setText(health.toString());
    }

    private void diagChecksText() {
        diagChecks.setText(CHECKS);
    }

    private void headline(String h, String reason) {
        diagHeadline.setText(h);
        diagReason.setText(reason == null ? "" : reason);
    }

    /**
     * 家长从返回键进来的诊断面板。
     *
     * 单独一个方法，不走 showDiag —— 那是"失败"的展示路径，会累加重试计数、
     * 会把标题写成"连不上"。**连接正常的时候打开诊断面板不该看起来像出了故障。**
     */
    private void showDiagnosticPanel() {
        diagView.setVisibility(View.VISIBLE);
        headline("诊断", currentUrl == null ? "还没找到服务。" : "正在用：" + currentUrl);
        if (lastProgressFile.isEmpty()) {
            diagServerMsg.setVisibility(View.GONE);
        } else {
            diagServerMsg.setVisibility(View.VISIBLE);
            diagServerMsg.setText("服务正常。进度文件：" + lastProgressFile
                    + (lastScratch ? "\n★ 这是测试用的临时进度，不是孩子的真实进度" : ""));
        }
        diagChecksText();
        diagHealth.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ 自动重试

    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (diagView.getVisibility() != View.VISIBLE) return;
            if (scanning) return;
            // ★ 连接**正常**的时候不许重试。否则家长按返回键打开诊断面板想看一眼，
            //   3 秒后就被自动重试踢回页面 —— 那个面板根本没法看。
            //   （第一版没这个判断，症状是"诊断页一开就自己关了"。）
            if (healthy) return;
            retryRound++;
            if (currentUrl != null) connect(currentUrl);
            else autoFind(false);
        }
    };

    /** 现在连着吗。决定自动重试该不该动。 */
    private boolean healthy;

    private void scheduleRetry() {
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, currentUrl == null ? RESCAN_MS : RETRY_MS);
    }

    private void stopRetry() {
        ui.removeCallbacks(retry);
        scanning = false;
    }

    // ------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        // learn.html 不用 localStorage，但开着几乎不花钱，省得以后加了要查半天。
        ws.setDomStorageEnabled(true);
        // 收紧到只需要的那几项。参照的 comfyui-client 放得比这宽，它需要，我们不需要。
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setSupportZoom(false);
        ws.setDisplayZoomControls(false);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        // 不受系统字体缩放影响：页面排版是按固定断点（760 / 1180px）调过的。
        ws.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 同源的留在 WebView 里（例如页面里那个 /plan 链接）。
                // 外链甩给系统浏览器 —— 否则外壳会不知不觉变成一个**没有返回键的浏览器**。
                String host = request.getUrl().getHost();
                Uri mine = Uri.parse(currentUrl == null ? "" : currentUrl);
                if (host != null && mine.getHost() != null
                        && !host.equalsIgnoreCase(mine.getHost())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面真的画出来了才揭开诊断面板。这一行就是"不会有白屏"的保证。
                diagView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // ★ 只认主框架。不判断的话，页面里一个 emoji 或字体加载失败
                //   就会把整页盖成一个错误界面。
                if (!request.isForMainFrame()) return;
                healthy = false;
                final int code = error.getErrorCode();
                final CharSequence desc = error.getDescription();
                ui.post(() -> {
                    ServerFinder.Why why = whyFromWebViewError(code);
                    showDiag(new ServerFinder.Result(why, currentUrl,
                            "WebView 报的错：" + desc + "（code " + code + "）", null, false));
                    scheduleRetry();
                });
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                if (response.getStatusCode() < 400) return;
                // ★ 不读 response.getData()：错误响应上它读不干净（老版本直接拿不到）。
                //   用自己的请求重取一遍正文 —— 403 那段正文正是最该显示的东西。
                //   走 probe 的同一套分类，免得两处判断不一致。
                healthy = false;
                final String url = request.getUrl().toString();
                new Thread(() -> {
                    ServerFinder.Result r = ServerFinder.probe(url);
                    ui.post(() -> { showDiag(r); scheduleRetry(); });
                }, "kmap-httperror").start();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // ★ 最容易被忘、后果最像"白屏"的一个。
                //   便宜平板内存紧张时渲染进程会被系统杀掉，WebView 就变成一块死白，
                //   症状是"用着用着白了，退了重进才好"。
                //   返回 true = 我们自己处理了（返回 false 的话整个 app 会被杀掉）。
                //
                //   注意：这里**不能**再碰 view，它的渲染进程已经没了。
                //   所以先把它从布局里摘掉、销毁，再新建一个。
                final boolean crashed = detail != null && detail.didCrash();
                ui.post(() -> rebuildWebView(crashed ? "页面崩了" : "页面被系统回收了"));
                return true;
            }
        });

        webView.setKeepScreenOn(true);
    }

    private static ServerFinder.Why whyFromWebViewError(int code) {
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                return ServerFinder.Why.BAD_ADDRESS;
            case WebViewClient.ERROR_CONNECT:
            case WebViewClient.ERROR_TIMEOUT:
            case WebViewClient.ERROR_IO:
                return ServerFinder.Why.UNREACHABLE;
            default:
                return ServerFinder.Why.UNREACHABLE;
        }
    }

    /** 渲染进程没了之后重建。**必须**换个新实例 —— 旧的已经是死的了。 */
    private void rebuildWebView(String why) {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        WebView fresh = new WebView(this);
        fresh.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fresh.setVisibility(View.GONE);
        root.addView(fresh, 0);
        webView = fresh;
        setupWebView();

        healthy = false;
        headline(why, "已经重新起了一份，正在重新打开…");
        diagView.setVisibility(View.VISIBLE);
        scheduleRetry();
    }

    // ------------------------------------------------------------ 返回键

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);

        // 三段：
        //   1. 页面里还能后退 -> 后退（地图下钻的回退，符合直觉）
        //   2. 已经回到首页，而诊断面板没开着 -> **打开诊断面板，不退出**
        //      这是家长唯一一个不污染孩子界面的入口（否则就得在界面上加个丑按钮）
        //   3. 诊断面板已经开着 -> 退出
        if (diagView.getVisibility() == View.VISIBLE) return super.onKeyDown(keyCode, event);
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        showDiagnosticPanel();
        return true;
    }
}
