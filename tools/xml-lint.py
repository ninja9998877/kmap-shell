#!/usr/bin/env python3
"""资源 XML 的体检。本机就能跑，不用等 CI。

存在的理由是一次真实的浪费：`styles.xml` 里有一句注释写成了
「跟页面 --paper 一致」，而 **XML 注释里不许出现 `--`**。
Gradle 的报错是：

    mergeReleaseResources FAILED
    styles.xml:12:42: The string "--" is not permitted within comments.

报错本身很清楚，但它要等到**下完 Gradle、配好 SDK、跑进 AGP 的合并任务**
之后才出现 —— 而这一条用标准库解析器在本机一秒钟就能查出来。
这个工程没有本地 Android SDK，"等 CI 告诉我们"是最贵的一种反馈方式，
所以凡是能用解析器提前判的，就别留给编译器。

用法：
    python tools/xml-lint.py            # 检查 app/src/main 下所有 xml
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'app' / 'src' / 'main'


def lint(path: Path) -> list:
    """返回问题列表（空 = 通过）。"""
    text = path.read_text(encoding='utf-8')
    problems = []

    # 先单独挑出 `--` 这个，因为它值得一句解释 —— 光看
    # "not well-formed" 没人知道该改什么。
    if '--' in text:
        # 只报注释里的（属性值里的 -- 是合法的，虽然我们的文件里没有）
        import re
        for m in re.finditer(r'<!--(.*?)-->', text, re.S):
            if '--' in m.group(1):
                line = text[:m.start()].count('\n') + 1
                line += m.group(1)[:m.group(1).index('--')].count('\n')
                problems.append(
                    f'{line} 行：XML 注释里出现了 `--`（注释里的 -- 是**非法**的，'
                    f'不是风格问题）。\n'
                    f'      要么换个说法（例如把 CSS 变量名 --paper 写成「paper 变量」），'
                    f'要么用全角破折号。')
                break

    # 再做一次完整的良构检查，抓 `&` 没转义、标签没闭合那一类。
    try:
        ET.parse(str(path))
    except ET.ParseError as err:
        problems.append(f'{err}')

    return problems


def main() -> int:
    files = sorted(SRC.rglob('*.xml'))
    if not files:
        print(f'在 {SRC} 下一个 xml 都没找到 —— 这条检查根本没跑成，当失败处理')
        return 1

    bad = 0
    for f in files:
        problems = lint(f)
        rel = f.relative_to(ROOT)
        if problems:
            bad += 1
            print(f'  ✗ {rel}')
            for p in problems:
                print(f'      {p}')
        else:
            print(f'  ✓ {rel}')

    print()
    if bad:
        print(f'{bad} 个文件有问题')
        return 1
    print(f'XML 全部良构（{len(files)} 个文件）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
