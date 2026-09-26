#!/usr/bin/env bash
# 断言用的共用小工具。由 android.yml 的各断言步骤 `source` 进来。
#
# 单独一个文件是为了**只有一份** —— 每个 step 各抄一遍 helper，
# 迟早会出现"两处理解不一致，而且不会报错"。
#
# ============================================================================
# ★ `aapt2 dump xmltree` 的输出格式**不是稳定的**，这部分踩过两次坑：
#
#   1. 属性名前缀：可能是完整命名空间 URI
#      （http://schemas.android.com/apk/res/android:screenOrientation）
#      也可能是缩写成 `android:`。所以只按**属性名**匹配，不认前缀。
#
#   2. 属性值的写法有三种，随 aapt2 的版本变：
#        (type 0x10)0x22          老格式：类型码 + 十六进制
#        (type 0x11)0x4a0         同上，类型码是 INT_HEX
#        34                       新格式（build-tools 37）：**十进制，没有类型码**
#      第一版只认第一种，于是在 37.0.0 上读不到值 → 断言报"值读不出来"。
#      现在统一解析成**十进制**再比，看的是数值本身，不是它写成什么样。
#
#   顺带：CI 里 aapt2 的版本现在是**钉死的**（用自己装的 build-tools 35.0.0），
#   而不是 `ls build-tools/*/aapt2 | tail -1` 取 runner 镜像里最高的那个 ——
#   否则"检查什么格式"这件事就交给镜像的更新节奏决定了。
# ============================================================================

fail() { echo "::error::$*"; exit 1; }
ok()   { echo "  OK  $*"; }

# attrval <属性名> <xmltree 输出文件> -> 打印 "=" 之后那一截，例如 "(type 0x10)0x22" / "34"
attrval() {
  grep -m1 -E "[^a-zA-Z]$1\(0x[0-9a-f]+\)" "$2" | sed -E 's/.*=[[:space:]]*//' || true
}

# attrnum <属性名> <文件> -> **十进制**数值；读不出来就什么都不输出
attrnum() {
  local raw v
  raw=$(attrval "$1" "$2")
  [ -n "$raw" ] || return 0
  # 优先取行尾的十六进制（"$" 锚点保证不会取到类型码里的那个 0x10）
  v=$(printf '%s' "$raw" | grep -oE '0x[0-9a-fA-F]+$' || true)
  # 没有 0x 就是十进制
  if [ -z "$v" ]; then
    v=$(printf '%s' "$raw" | grep -oE '[0-9]+$' || true)
  fi
  [ -n "$v" ] || return 0
  printf '%d' "$v"
}

# attrbool <属性名> <文件> -> "true" / "false" / 空
attrbool() {
  local raw n
  raw=$(attrval "$1" "$2")
  [ -n "$raw" ] || return 0
  case "$raw" in
    *true*)  printf 'true';  return 0 ;;
    *false*) printf 'false'; return 0 ;;
  esac
  n=$(attrnum "$1" "$2")
  [ -n "$n" ] || return 0
  if [ "$n" = "0" ]; then printf 'false'; else printf 'true'; fi
}

# checknum <属性名> <文件> <期望值(十进制, 逗号分隔多个可接受值)> <人话说明>
checknum() {
  local a="$1" f="$2" want="$3" human="$4" v
  v=$(attrnum "$a" "$f")
  [ -n "$v" ] || fail "最终 APK 的清单里没有 $a，或者它的值读不出来 —— $human"
  local got=0 w
  local IFS=','
  for w in $want; do
    # ★ 用 if，不用 `[ ] && got=1`。
    #   `[ ] && x` 在第一次比较不相等时整条返回非零，而各断言步骤都带 `set -e`
    #   —— shell 会**当场退出**，连下面 fail 那句说明都打不出来。
    #   症状是"CI 红了，日志里却没有任何解释"，最难查的一种。
    if [ "$v" = "$w" ]; then got=1; fi
  done
  [ "$got" = "1" ] || fail "$a=$v 不在允许的取值（$want）里 —— $human"
  ok "$a=$v"
}

# checkbool <属性名> <文件> <true|false> <人话说明>
checkbool() {
  local a="$1" f="$2" want="$3" human="$4" v
  v=$(attrbool "$a" "$f")
  [ -n "$v" ] || fail "最终 APK 的清单里没有 $a —— $human"
  if [ "$v" != "$want" ]; then
    fail "$a=$v，期望 $want —— $human"
  fi
  ok "$a=$v"
}

# 断言属性**不存在**。用于 networkSecurityConfig 那一类"存在即有害"的。
checkabsent() {
  local raw
  raw=$(attrval "$1" "$2")
  [ -z "$raw" ] || fail "$1 出现在清单里了（$raw）—— $3"
  ok "没有 $1"
}
