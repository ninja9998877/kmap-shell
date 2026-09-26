#!/usr/bin/env bash
# 断言用的共用小工具。由 android.yml 的各断言步骤 `source` 进来。
#
# 单独一个文件是为了**只有一份** —— 每个 step 各抄一遍 helper，
# 迟早会出现"两处理解不一致，而且不会报错"（本项目的规矩）。
#
# ★ 这些 helper 全都是照着"不要假失败"写的。aapt2 的输出格式有几个坑：
#   - 属性名前缀可能是完整命名空间 URI（http://schemas.android.com/apk/res/android:...）
#     也可能缩写成 android:，两种都出现过 —— 所以只按**属性名**匹配，不认前缀。
#   - 类型码不固定：布尔是 0x12，整型可能是 0x10 也可能是 0x11(hex)。
#     写死某一种就会在格式变化时"检查根本没跑成"，而那种失败看起来像真失败。

fail() { echo "::error::$*"; exit 1; }
ok()   { echo "  OK  $*"; }

# attrval <属性名> <xmltree 输出文件> -> 打印 "=" 之后那一截，例如 "(type 0x10)0x6"
attrval() {
  grep -m1 -E "[^a-zA-Z]$1\(0x[0-9a-f]+\)" "$2" | sed -E 's/.*=[[:space:]]*//' || true
}

# 从 "(type 0x10)0x6" 里取出末尾那个十六进制值（$ 锚点保证不会取到类型码）
hexval() { echo "$1" | grep -oE '0x[0-9a-f]+$' || true; }

# checkhex <属性名> <文件> <期望值(十六进制, 逗号分隔多个可接受值)> <人话说明>
checkhex() {
  local a="$1" f="$2" want="$3" human="$4" raw v
  raw=$(attrval "$a" "$f")
  [ -n "$raw" ] || fail "最终 APK 的清单里没有 $a —— $human"
  v=$(hexval "$raw")
  [ -n "$v" ] || fail "$a 的值读不出来：$raw"
  # 期望值写 6 或 0x6 都行 —— 两边都去掉 0x 前缀再比。
  # （第一版没归一化：传 "0,6,8" 却拿 "0x6" 去比，**横屏那条断言必然假失败**。）
  local vn="${v#0x}" got=0 w wn
  local IFS=','
  for w in $want; do
    wn="${w#0x}"
    # ★ 用 if，不用 `[ ] && got=1`。
    #   `[ ] && x` 在第一次比较不相等时整条返回非零，而各断言步骤都带 `set -e`
    #   —— shell 会**当场退出**，连下面 fail 那句说明都打不出来。
    #   症状是"CI 红了，日志里却没有任何解释"，最难查的一种。
    if [ "$vn" = "$wn" ]; then got=1; fi
  done
  [ "$got" = "1" ] || fail "$a=$v 不在允许的取值（$want）里 —— $human"
  ok "$a=$v"
}

# checkbool <属性名> <文件> <true|false> <人话说明>
checkbool() {
  local want="0"
  if [ "$3" = "true" ]; then want="ffffffff"; fi
  checkhex "$1" "$2" "$want" "$4"
}

# 断言属性**不存在**。用于 networkSecurityConfig 那一类"存在即有害"的。
checkabsent() {
  local raw
  raw=$(attrval "$1" "$2")
  [ -z "$raw" ] || fail "$1 出现在清单里了（$raw）—— $3"
  ok "没有 $1"
}
