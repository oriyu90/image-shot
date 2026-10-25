#!/usr/bin/env python3
"""
PR-27 G1 文字列外部化の受入用チェッカー（07 Wave-3 §11・受入条件「grep 台帳」）。

機能:
- feature/* と app の Kotlin main ソースから、日本語（かな/漢字)を含む文字列
  リテラル（直書き文言）を検出する。正本は各 feature の res/values/strings.xml。
- 台帳（08_文字列台帳.md）に記録済みの「構造的例外」ファイルは許容する。
- 例外（許容ファイル）以外で 1 件でも検出されたら exit 1。

使用法: python3 tools/ui_strings_check.py   （リポジトリ・ルートで実行）
"""
import glob
import re
import sys

# CJK を含む文字列リテラルを検出する正則（かな + 漢字 −英数字のみは対象外）
CJK = re.compile(r"[ぁ-んァ-ヶ一-龥]")
LITERAL = re.compile(r'"([^"\n]*)"')

# 台帳済み・構造的例外（core 層の正本文言。08_文字列台帳.md §2 参照）:
# - core:render/ExportRenderer.kt, GainmapExportPolicy.kt — ADR-026 警告文言
#   （request ファイル issueNotes 正本・UI 表示経路の結線は別 PR）
# - core:storage/*.kt — SA/provider層の診断理由正本（ImageOpResult.detail /
#   ImportProbe.warnings に載り、DB と request に保存される技術文言）
# - core:model/EditEvent.kt — EditEventKind.label は 01_製品仕様書 §5 の仕様書語彙
#   （KDoc 明示のとおり UI とは別・現行コードから未参照）
ALLOWED_FILES = re.compile(
    r"^core/(render/src/main/kotlin/com/imageshot/core/render/(ExportRenderer|GainmapExportPolicy)"
    r"|storage/src/main/kotlin/com/imageshot/core/storage/(ProviderErrors|ImportSniff|ContentUriImportRepository)"
    r"|model/src/main/kotlin/com/imageshot/core/model/EditEvent)"
    r"\.kt$"
)


def main() -> int:
    violations = []
    allowed_hits = []
    patterns = [
        "feature/*/src/main/**/*.kt",
        "app/src/main/**/*.kt",
        "core/*/src/main/**/*.kt",
    ]
    for pattern in patterns:
        for f in sorted(glob.glob(pattern, recursive=True)):
            for i, line in enumerate(open(f, encoding="utf-8"), 1):
                for m in LITERAL.finditer(line):
                    if CJK.search(m.group(1)):
                        if ALLOWED_FILES.match(f):
                            allowed_hits.append((f, i, m.group(1)))
                        else:
                            violations.append((f, i, m.group(1)))
    for f, i, s in allowed_hits:
        print(f"allowed (ledger §2): {f}:{i}: \"{s}\"")
    if violations:
        print("--- VIOLATIONS（台帳へ追加するか strings.xml へ移設すること）:")
        for f, i, s in violations:
            print(f'  {f}:{i}: "{s}"')
        return 1
    print("OK: 文言直書きは台帳済み例外のみ（UI 文言は feature/*/res/values/strings.xml 正本）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
