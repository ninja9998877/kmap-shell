# kmap-shell

**六年知识地图**（`D:\claudeWorkbase\kmap`）的安卓外壳。

一个全屏、**强制横屏**的 WebView，指向电脑上跑着的 `serve.py`。桌面有图标，
点开就是 kmap，孩子不用输地址。

## ★ 装一次就够了 —— 改内容不用重新打包

**这个 APK 里没有 kmap 的任何界面代码**，一行都没有。它只做一件事：把
`http://<电脑>:8890` 显示出来。所以：

| 你改了什么 | 平板上怎么生效 |
|---|---|
| `learn.html`（布局、文案、卡通样式、术语问号…） | 刷新页面 |
| `graph-*.yaml`（知识点、单元） | 刷新页面 |
| `grader.py` / `mastery.py` / `tutor.py`（判分、出题） | 刷新页面 |
| 结算页、复习队列、先修提示 | 刷新页面 |

**只有"外壳自己"变了才需要重新打包**：图标、横屏、WebView 配置、找服务的方式。
不需要 Expo OTA / CodePush 那类热更新框架 —— 架构本身就给了。

（`serve.py` 发的是 `Cache-Control: no-store`，所以不会拿到缓存的旧页面。）

## 为什么必须是外壳，不能做成离线 App

kmap 有两条硬性设计：**答案绝不发前端**（判分在服务端）、出题要调 LLM 而
key 在服务端 `.env` 里。把逻辑塞进 APK 等于同时破掉这两条。
CI 里有一条断言守着这件事（APK 内不许出现 `.env` / `learn.html` /
`progress.json`，dex 里不许出现 `LLM_API_KEY`）。

## 出包

**只手动触发**（Actions → Android → Run workflow）。开发机没有 Android SDK，
云构建是唯一途径。跑完在 Actions 的 artifact 里下载 `kmap-shell-apk`。

或者更省事：把下下来的 APK 放到 `kmap/apk/kmap-shell.apk`，
平板浏览器打开 `http://<电脑>:8890/app.apk` 直接下载安装 ——
不过局域网，也不用数据线。

### 装之前

平板要允许「安装未知来源的应用」。装完第一次打开，它会自己找电脑上的服务；
找不到会显示一块**说清楚原因**的面板（不是白屏），并每 3 秒自动重试 ——
电脑睡醒或服务重启之后它自己会好。

**连按两次返回键**可以打开诊断面板（给大人看的），上面有构建号：
拍一张截图就能确认平板上装的是哪一次构建。

## 两个数字是钉死的，别随手改

| 数字 | 值 | 为什么 |
|---|---|---|
| `targetSdk` | **34** | Android 16 起，target 36 在 sw>=600dp 的屏幕上忽略 `screenOrientation` —— **平板正在此列**。升到 36 就是"清单里写着横屏，系统直接无视，代码一行没动"。<br>不用 35 是因为 Android 15 对 target 35 强制 edge-to-edge，输入法会盖住输入框，得自己接管 IME inset。34 绕开这一整套。 |
| `minSdk` | **24** | 真正的门槛不是 OS 而是 WebView 版本（`dialog.showModal()` 要 37+、`env()` 要 69+），而 WebView 从 Android 7 起就能独立更新。 |

CI 里有断言盯着这两个数字，以及横屏、`resizeableActivity=false`、
明文 HTTP、`configChanges` 含 orientation|screenSize。

### ★ 横屏锁是"软需求"

Google 在**系统性地拆**方向限制。官方原话是 *"Restrictions like fixed
orientation or limited resizability hinder app adaptability"*，Android 16 起
target 36 在平板上不再理会，Android 17 之后连 opt-out 都没有了。

**但这不构成风险**：kmap 页面本来就有窄屏布局（`innerWidth < 760` 时切
`ISLE_NARROW`），`kmap-layout.mjs` 一直在测 800×1280 竖屏。
所以横屏锁是**体验优化，不是承重墙** —— 哪天失效了页面照样能用，
**不要围绕它做任何别的架构决策**。

## 怎么找到电脑上的服务

**扫子网 + 用 `/api/health` 验明正身**，顺序是：
记住的地址 → 编译时写死的地址 → 扫子网 → 手填。

### 为什么不用 mDNS

不是"mDNS 不行"，是它在这台机器 + 这个网络里会以**三种方式**失败，
而且每一种看起来都一样：

1. 电脑的防火墙只放行了 TCP 8890，没放 UDP 5353。而且现在能通是"碰巧"，
   靠的是一条按 `python.exe` 全路径放行的宽规则 —— 路径一变就同时断掉
   发现和连接，你会在两个症状之间反复横跳。
2. 主机名解析出来是 Tailscale 的 `100.81.230.99`。`zeroconf` 默认把所有网卡
   地址都广播出去，平板拿到那个地址**永远连不上**，而现象是"找到了却打不开"。
3. 最要命的一条：**mDNS 失败时无法区分"没找到服务"和"网络根本不通"。**
   `NsdManager` 只会说"没有服务"，原因它不知道。

扫子网没有这三个问题，服务端一行都不用改，而且**天然给出三态**：

| 观测 | 含义 | 面板上说什么 |
|---|---|---|
| 有人应答但端口是关的（收到 RST） | 网络通，服务没跑 | 「电脑上的服务没在跑」 |
| 所有地址都超时 | 不在同一网 / AP 隔离 | 「不在同一个网络里」 |
| 8890 有人但不是 kmap | 端口被占 | 「这个地址上不是 kmap」 |

命中之后**必须**用 `/api/health` 验 —— 同网段里任何开 8890 的东西都会接受
TCP 连接，只看"端口通不通"会连错。

## 代码结构

```
app/src/main/java/com/ninja/kmap/
  MainActivity.java   三态界面 + 失败分类 + 自动重试 + WebView 生命周期
  ServerFinder.java   探测单个地址（分类七种原因）、扫子网
  Subnet.java         地址算术。★ 不 import 任何 android.*
app/src/main/res/     布局、主题（含深色）
tools/
  SubnetCheck.java    子网算术自测
  assert-lib-test.sh  断言库自测
.github/
  workflows/android.yml
  assert-lib.sh       断言用的共用小工具
```

**零 AndroidX 依赖。** 只用 `android.app.Activity` 和平台自带的 WebView，
APK 约 40KB。加一个 appcompat 会让它涨到 ~2MB 并引入一整类清单合并风险，
而这个外壳的全部价值就是"把网页显示出来"。

### 两处刻意不依赖 Android 的代码

`Subnet.java` 和 `assert-lib.sh` 都不碰 Android API，所以
**本机（没有 SDK、没有设备）就能真跑一遍**，CI 里也是在装 SDK 之前先跑它们
—— 比等 Android 构建便宜几个数量级。它们测的都是**产品代码本身**，
不是抄一份的副本（抄一份 = 两份实现各自正确、彼此不一致、且不会报错）。

`assert-lib.sh` 单独有测试是有原因的：本机跑不了 `aapt2`，所以那套解析逻辑
是"只能到 CI 才知道对不对"的一段代码，而它又决定着一堆断言的成败。
它解析错的两个方向后果不同但都糟：**假失败**（对的包被拦下，然后人去修
没坏的东西）和**假通过**（漏了横屏，平板上才发现）。

## 签名

固定 keystore，存 GitHub Secrets（`KMAP_KEYSTORE_B64` / `KMAP_STORE_PASSWORD` /
`KMAP_KEY_ALIAS` / `KMAP_KEY_PASSWORD`）。本地那份在
`D:\claudeWorkbase\kmap-signing\`（**仓库外**，别提交）。

**为什么要固定**：AGP 在 runner 上找不到 `~/.android/debug.keystore` 会现场
生成一个，密钥是新的随机值，而 `ubuntu-latest` 每次都是干净 VM ⇒ 每次构建
签名都不同 ⇒ 新包报「应用未安装」，必须先卸载，而**卸载会丢掉 App 里存的
服务地址**。

CI 里有两条守这个：缺 secret 直接 fail-fast（**绝不回退 debug 签名**），
以及拿 APK 实际的签名指纹跟 `expected-signer-sha256.txt` 比。
**换了 keystore 就要同步改那个文件**，否则 CI 会挡住你 —— 那是它的工作。
