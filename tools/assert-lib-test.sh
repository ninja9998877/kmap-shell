#!/usr/bin/env bash
#
# 断言库的自测。
#
# 为什么值得单独测：本机**跑不了 aapt2**（没有 Android SDK），所以那套解析
# 逻辑是"只能到 CI 上才知道对不对"的一段代码 —— 而它又决定着一堆断言的成败。
# 解析错的两个方向后果不同，但都糟：
#   - 假失败：明明是对的包被拦下来，然后人会去"修"没坏的东西
#   - 假通过：坏包放行（例如漏了横屏），平板上才发现
#
# ★ 这里用 aapt2 **真实出现过的三种输出格式**造样本喂给它。三个都不是编的，
#   每一个都对应一次真实的失败：
#
#   格式一  属性名带完整命名空间 URI，值写成 (type 0x10)0x6
#   格式二  属性名缩写成 android:，类型码 0x10
#   格式三  **十进制，没有类型码** —— build-tools 37 就是这么打的
#           （第三次 CI 失败：targetSdk 读出来是字符串 "34"，
#             而当时只认带 0x 的形式，于是"值读不出来"）
#
#   bash tools/assert-lib-test.sh
#
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=../.github/assert-lib.sh
source "$HERE/../.github/assert-lib.sh"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ---- 格式一：完整命名空间 URI 前缀，configChanges 用 0x11(int hex)
cat > "$TMP/uri.txt" <<'EOF'
  E: manifest (line=2)
    E: uses-sdk (line=8)
      A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=(type 0x10)0x22
    E: application (line=5)
      A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=(type 0x12)0xffffffff
      E: activity (line=11)
        A: http://schemas.android.com/apk/res/android:screenOrientation(0x0101001e)=(type 0x10)0x6
        A: http://schemas.android.com/apk/res/android:resizeableActivity(0x01010020)=(type 0x12)0x0
        A: http://schemas.android.com/apk/res/android:configChanges(0x0101001f)=(type 0x11)0x4a0
EOF

# ---- 格式二：缩写成 android:，且是**竖屏 + 可调整大小**（这两种都该被拒）
cat > "$TMP/short.txt" <<'EOF'
  E: manifest
    E: application
      A: android:label(0x01010001)="kmap"
      E: activity
        A: android:screenOrientation(0x0101001e)=(type 0x10)0x1
        A: android:resizeableActivity(0x01010020)=(type 0x12)0xffffffff
        A: android:configChanges(0x0101001f)=(type 0x10)0x480
EOF

# ---- 格式三：build-tools 37 的十进制写法（没有类型码）
cat > "$TMP/dec.txt" <<'EOF'
  E: manifest (line=2)
    E: uses-sdk (line=8)
      A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=24
      A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=34
    E: application (line=5)
      A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=true
      E: activity (line=11)
        A: http://schemas.android.com/apk/res/android:screenOrientation(0x0101001e)=6
        A: http://schemas.android.com/apk/res/android:resizeableActivity(0x01010020)=false
        A: http://schemas.android.com/apk/res/android:configChanges(0x0101001f)=1184
EOF

# ---- 相近的属性名 + 挂上了 networkSecurityConfig
cat > "$TMP/trap.txt" <<'EOF'
  E: manifest
    E: application
      A: android:networkSecurityConfig(0x0101054a)=(type 0x1)@0x7f0f0000
      E: activity
        A: android:notScreenOrientation(0x0101001e)=(type 0x10)0x9
EOF

bad=0
# 期望通过的：直接跑。
want_pass() { if ( eval "$1" ) >/dev/null 2>&1; then echo "  OK    $2"; else echo "  FAIL  $2 （本该通过）"; bad=$((bad+1)); fi; }
# 期望被拒的：**必须放子 shell** —— fail 里的 exit 1 会把测试脚本本身干掉。
want_fail() { if ( eval "$1" ) >/dev/null 2>&1; then echo "  FAIL  $2 （本该被拒）"; bad=$((bad+1)); else echo "  OK    $2"; fi; }

echo "=== 格式一：URI 前缀 + 类型码 ==="
want_pass "checknum screenOrientation $TMP/uri.txt '0,6,8' x"   "0x6 在 {0,6,8} 里"
want_pass "checknum targetSdkVersion $TMP/uri.txt '34' x"       "0x22 读成十进制 34"
want_pass "checknum configChanges $TMP/uri.txt '1184' x"        "类型码 0x11 也认（0x4a0=1184）"
want_pass "checkbool resizeableActivity $TMP/uri.txt false x"   "resizeableActivity=false"
want_pass "checkbool usesCleartextTraffic $TMP/uri.txt true x"  "cleartext=true"

echo "=== 格式二：android: 前缀 + 竖屏（该拒的必须拒）==="
want_pass "checknum screenOrientation $TMP/short.txt '1' x"     "android: 前缀也读得出值"
want_fail "checknum screenOrientation $TMP/short.txt '0,6,8' x" "竖屏(1)被拒"
want_fail "checkbool resizeableActivity $TMP/short.txt false x" "resizeableActivity=true 被拒"
want_fail "checkbool usesCleartextTraffic $TMP/short.txt true x" "cleartext 缺失被拒"

echo "=== ★ 格式三：build-tools 37 的十进制（就是它让 CI 第三次红的）==="
want_pass "checknum minSdkVersion $TMP/dec.txt '24' x"          "十进制 24 读得出来"
want_pass "checknum targetSdkVersion $TMP/dec.txt '34' x"       "十进制 34 读得出来"
want_pass "checknum configChanges $TMP/dec.txt '1184' x"        "十进制 1184 读得出来"
want_pass "checknum screenOrientation $TMP/dec.txt '0,6,8' x"   "横屏 6 通过"
want_pass "checkbool resizeableActivity $TMP/dec.txt false x"   "十进制 false 认"
want_pass "checkbool usesCleartextTraffic $TMP/dec.txt true x"  "true 认"
want_fail "checknum targetSdkVersion $TMP/dec.txt '36' x"       "targetSdk 升到 36 会被挡"
want_fail "checknum screenOrientation $TMP/dec.txt '1' x"       "竖屏被拒"

echo "=== 该拒的、以及不许误伤 ==="
want_fail "checknum screenOrientation $TMP/trap.txt '0,6,8' x"  "属性缺失被拒（不能静默通过）"
want_fail "checknum configChanges $TMP/trap.txt '1184' x"       "configChanges 缺失被拒"
want_fail "checknum screenOrientation $TMP/trap.txt '9' x"      "notScreenOrientation 不算数"
want_pass "checkabsent networkSecurityConfig $TMP/uri.txt x"    "没挂 NSC -> 通过"
want_fail "checkabsent networkSecurityConfig $TMP/trap.txt x"   "挂了 NSC -> 被拒"

echo
if [ "$bad" = "0" ]; then echo "断言库自测全部通过"; exit 0; fi
echo "$bad 项不合格"; exit 1
