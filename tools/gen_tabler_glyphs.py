#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Tabler Icons 的 Android AAR 中提取指定图标的 pathData，生成 Compose ImageVector 内联代码。

为什么内联而不直接依赖 AAR：
  `com.composables:icons-tabler-outline-android` 是纯资源 AAR（classes.jar 为空），
  内含 4,985 个 vector XML，描边 2.0 被烧死在 XML 里，渲染到 16dp 时只剩 1.33dp，
  无法做「描边光学补偿」。内联成 ImageVector 后描边可参数化，且体积只有 AAR 的 1/N。

用法:
    python3 tools/gen_tabler_glyphs.py <解包后的 AAR 目录> <输出 .kt 路径>
    例: python3 tools/gen_tabler_glyphs.py /tmp/tab/x app/src/main/kotlin/.../TablerGlyph.kt

上游: https://github.com/tabler/tabler-icons  (MIT License)
"""
import os
import re
import sys

# XuanGlyphType -> Tabler outline 图标名（None 表示保留微玄自绘爻线，不在此文件生成）
MAPPING = {
    "Settings": "settings",
    "Tools": "tools",
    "Permission": "shield-lock",
    "Terminal": "terminal-2",
    "Browser": "browser",
    "Plus": "plus",
    "History": "history",
    "More": "dots",
    "Send": "send",
    "Stop": "player-stop",
    "Search": "search",
    "Check": "check",
    "Close": "x",
    "ChevronRight": "chevron-right",
    "ChevronDown": "chevron-down",
    "ChevronLeft": "chevron-left",
    "Refresh": "refresh",
    "Download": "download",
    "Delete": "trash",
    "Play": "player-play",
    "Globe": "world",
    "Folder": "folder",
    "Image": "photo",
    "Device": "device-mobile",
    "Clipboard": "clipboard",
    "Tap": "hand-click",
    "Wifi": "wifi",
    "Swap": "arrows-exchange",
    "Bag": "shopping-bag",
    "Monitor": "device-desktop",
    "Bell": "bell",
    "Location": "map-pin",
    "Music": "music",
    "Pulse": "activity",
    "Mic": "microphone",
    "Command": "command",
    "Keyboard": "keyboard",
    "Note": "notes",
    "Contact": "address-book",
    "Move": "arrows-move",
    "Sync": "rotate",
    "Link": "link",
}

EXPECTED_STROKE = "2.0"
PATH_RE = re.compile(r"<path\b([^>]*?)/?>", re.S)
ATTR_RE = re.compile(r'android:(\w+)="([^"]*)"')


def extract(drawable_dir: str, tabler_name: str):
    """返回该图标的 pathData 列表；同时校验规格符合预期。"""
    fname = "tabler_ic_%s_outline.xml" % tabler_name.replace("-", "_")
    path = os.path.join(drawable_dir, fname)
    if not os.path.exists(path):
        raise SystemExit("缺失图标: %s (%s)" % (tabler_name, fname))
    src = open(path, encoding="utf8").read()

    datas = []
    for m in PATH_RE.finditer(src):
        attrs = dict(ATTR_RE.findall(m.group(1)))
        if "pathData" not in attrs:
            continue
        sw = attrs.get("strokeWidth")
        if sw != EXPECTED_STROKE:
            raise SystemExit("%s 存在非预期描边 %r（期望 %s）" % (tabler_name, sw, EXPECTED_STROKE))
        if "fillColor" in attrs:
            raise SystemExit("%s 存在填充路径，需人工确认" % tabler_name)
        datas.append(attrs["pathData"])
    if not datas:
        raise SystemExit("未提取到任何 path: %s" % tabler_name)
    return datas


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    drawable_dir, out_path = sys.argv[1], sys.argv[2]

    entries = []
    total_paths = 0
    for enum_name, tabler_name in MAPPING.items():
        datas = extract(drawable_dir, tabler_name)
        total_paths += len(datas)
        body = ",\n".join('            "%s"' % d for d in datas)
        entries.append("    %s(\n        listOf(\n%s,\n        )\n    )" % (enum_name, body))

    out = [
        "// GENERATED FILE — DO NOT EDIT BY HAND.",
        "// 生成脚本: tools/gen_tabler_glyphs.py",
        "// 来源: Tabler Icons <https://github.com/tabler/tabler-icons> (MIT License)",
        "//       via com.composables:icons-tabler-outline-android:2.2.1 (outline 资源)",
        "// 规格: 24x24 viewport / strokeWidth 2.0 / round cap+join / 纯描边无填充",
        "// 共 %d 枚图标 / %d 条路径。" % (len(entries), total_paths),
        "//",
        "// 说明: 内联为 ImageVector 数据而非依赖资源 AAR，是为了让描边宽度可按渲染尺寸",
        "//       参数化（描边光学补偿），并避免 4,985 个 drawable 的资源膨胀。",
        "",
        "package cn.yangrq.weixuan.ui.design.tabler",
        "",
        "internal enum class TablerGlyph(val paths: List<String>) {",
        ",\n".join(entries) + ",",
        "}",
        "",
    ]
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf8") as f:
        f.write("\n".join(out))
    print("OK: %d 图标 / %d 路径 -> %s" % (len(entries), total_paths, out_path))


if __name__ == "__main__":
    main()
