#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""删除微玄代码中所有 `androidx.compose.material.icons.*` 的死 import。

【为什么会有死 import】
早期轮次把图标从 Material 迁到微玄自绘「爻线」/Tabler 图形语言时，只替换了调用点，
没清理 import 行。Kotlin 对未使用 import 只报 warning 不报 error，所以它们一直留着，
并让 `material-icons-extended`（体积大户）无法从依赖里摘掉。

【为什么可以全删】
Material 图标只能以 `Icons.<Variant>.<Name>` 形式使用。实测：
  - 全工程该形式仅剩 1 处，位于 `EtaPreferenceStyle.kt` 的 ImageVector 重载内；
  - 该重载零调用，已随本次清理一并删除。
故这些 import 全部为死代码。

【安全网】
删完必须编译一次：编译器会精确报出任何「其实还在用」的 import（unresolved reference），
比人工核对 200 行可靠得多。

用法：
    python3 tools/strip_material_icons.py [根目录，默认 app/src/main/kotlin]
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/kotlin"
IMPORT_RE = re.compile(r"^import androidx\.compose\.material\.icons\.")

removed_total = 0
touched_files = 0
details = []

for dirpath, _dirnames, filenames in os.walk(ROOT):
    for name in sorted(filenames):
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        with open(path, encoding="utf8") as fh:
            lines = fh.readlines()

        kept = [ln for ln in lines if not IMPORT_RE.match(ln)]
        dropped = len(lines) - len(kept)
        if dropped == 0:
            continue

        # 折叠因整块删除而留下的多余空行（3 个以上连续换行压成 2 个）
        text = "".join(kept)
        text = re.sub(r"\n{3,}", "\n\n", text)
        with open(path, "w", encoding="utf8") as fh:
            fh.write(text)

        removed_total += dropped
        touched_files += 1
        details.append((dropped, os.path.relpath(path, ROOT)))

print("删除死 import：%d 行，涉及 %d 个文件" % (removed_total, touched_files))
for count, rel in sorted(details, reverse=True):
    print("  %3d  %s" % (count, rel))
if removed_total == 0:
    print("（无变化：可能已清理过）")
