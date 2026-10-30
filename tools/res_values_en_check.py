#!/usr/bin/env python3
"""
values-en 完全性チェッカー（日本語 UI の英語対応・受入用）。

各モジュールの `src/main/res/values/strings.xml`（既定＝日本語）と
`src/main/res/values-en/strings.xml`（英語）を突き合わせ、次を検証する:

1. キー集合の完全一致（英語側の欠落・余剰・重複）
2. フォーマット指定子の完全一致（種類・位置引数・出現順をそのまま比較）
3. `translatable` 属性の一致
4. 英語値に日本語文字（かな/漢字/全角記号）が残っていないこと
5. 既定値が空でないのに英語値が空、という欠落がないこと

使用法: python3 tools/res_values_en_check.py   （リポジトリ・ルートで実行）
終了コード: 0 = 全モジュール PASS / 1 = 違反あり
"""
from __future__ import annotations

import glob
import re
import sys
import xml.etree.ElementTree as ET

# Android のフォーマット指定子（%% も 1 トークンとして扱う）
FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

# 日本語（ひらがな・カタカナ・漢字・全角記号）。英語 UI に残ってはいけない。
JAPANESE = re.compile(
    r"[\u3000-\u303f\u3040-\u309f\u30a0-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff66-\uff9f]"
)


def parse_strings(path: str) -> dict[str, tuple[str, str]]:
    """{name: (text, translatable)} を返す。重複キーは ValueError。"""
    root = ET.parse(path).getroot()
    out: dict[str, tuple[str, str]] = {}
    unsupported = sorted({el.tag for el in root if el.tag != "string"})
    if unsupported:
        raise ValueError(
            f"{path}: unsupported resource elements {unsupported} "
            "(extend this checker before adding plurals/string-arrays)"
        )
    for el in root.findall("string"):
        name = el.get("name")
        if name is None:
            raise ValueError(f"{path}: <string> without name")
        if name in out:
            raise ValueError(f"{path}: duplicate key {name!r}")
        text = "".join(el.itertext())
        out[name] = (text, el.get("translatable", "true"))
    return out


def main() -> int:
    defaults = sorted(
        p
        for p in glob.glob("**/src/main/res/values/strings.xml", recursive=True)
        if "/build/" not in p
    )
    if not defaults:
        print("FAIL: no values/strings.xml found (run from the repository root)")
        return 1

    failures: list[str] = []
    print(f"checked modules: {len(defaults)}")

    for default_path in defaults:
        module = default_path.split("/src/main/res/")[0]
        en_path = default_path.replace("/values/", "/values-en/")
        print(f"\n[{module}]")
        try:
            base = parse_strings(default_path)
        except (ET.ParseError, ValueError) as e:
            failures.append(f"{module}: default parse error: {e}")
            print(f"  FAIL parse: {e}")
            continue
        try:
            en = parse_strings(en_path)
        except FileNotFoundError:
            failures.append(f"{module}: missing {en_path}")
            print(f"  FAIL missing: {en_path}")
            continue
        except (ET.ParseError, ValueError) as e:
            failures.append(f"{module}: en parse error: {e}")
            print(f"  FAIL parse {en_path}: {e}")
            continue

        missing = sorted(set(base) - set(en))
        extra = sorted(set(en) - set(base))
        if missing:
            failures.append(f"{module}: {len(missing)} key(s) missing in values-en: {missing}")
            print(f"  FAIL missing keys ({len(missing)}): {missing}")
        if extra:
            failures.append(f"{module}: {len(extra)} extra key(s) in values-en: {extra}")
            print(f"  FAIL extra keys ({len(extra)}): {extra}")

        format_errors: list[str] = []
        translatable_errors: list[str] = []
        japanese_errors: list[str] = []
        empty_errors: list[str] = []

        for name in sorted(set(base) & set(en)):
            base_text, base_translatable = base[name]
            en_text, en_translatable = en[name]

            base_specs = FORMAT_SPEC.findall(base_text)
            en_specs = FORMAT_SPEC.findall(en_text)
            if base_specs != en_specs:
                format_errors.append(f"{name}: {base_specs} != {en_specs}")

            if base_translatable != en_translatable:
                translatable_errors.append(
                    f"{name}: translatable={en_translatable!r} (default {base_translatable!r})"
                )

            hit = JAPANESE.search(en_text)
            if hit:
                japanese_errors.append(f"{name}: {en_text!r} (U+{ord(hit.group()):04X})")

            if base_text.strip() and not en_text.strip():
                empty_errors.append(name)

        for label, errors in (
            ("format specifier mismatch", format_errors),
            ("translatable mismatch", translatable_errors),
            ("Japanese text remains", japanese_errors),
            ("empty translation", empty_errors),
        ):
            if errors:
                failures.extend(f"{module}: {label}: {e}" for e in errors)
                print(f"  FAIL {label} ({len(errors)}):")
                for e in errors:
                    print(f"    - {e}")

        if not (missing or extra or format_errors or translatable_errors or japanese_errors or empty_errors):
            print(f"  PASS keys={len(base)} (format specifiers, translatable, no Japanese)")

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)} issue(s))")
        return 1
    print("RESULT: PASS — values-en matches values on every key, specifier, and translatable flag")
    return 0


if __name__ == "__main__":
    sys.exit(main())
