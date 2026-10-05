#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B 组（分组工具 + action 枚举）评测运行器。

设计：**完全不改 agent_eval.py**（保证与 84.6% 基线同一套判分逻辑），
只用 monkey-patch 在「模型回答 → 判分」之间插一层映射：
    模型吐 {tool: "ui_touch", action: "tap"}  → 还原成 {tool: "tap"}  → 交给原 score()
这样 A/B 两组的判分口径完全一致，差异只来自工具形态。

用法（先按 eval_server.sh 起 server）：
    python3 agent_eval_grouped.py --port 19710 --grouped scripts/bench/agent_tools_grouped.json \
        [--baseline]     # 加 --baseline 时先跑一遍 A 组（全量 57）再跑 B 组
"""
import argparse
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import agent_eval as ev  # noqa: E402

ALIAS = {}

# ★ 必须先抓住原函数再替换，否则包装器里再调 ev.extract_call 会递归到自己（实测 RecursionError）
_ORIG_EXTRACT = ev.extract_call


def _patched_extract(msg):
    name, args = _ORIG_EXTRACT(msg)
    group = ALIAS.get(name or "")
    if group and isinstance(args, dict):
        action = args.get("action")
        original = group.get(action) if isinstance(action, str) else None
        if original:
            rest = {k: v for k, v in args.items() if k != "action"}
            return original, rest
    return name, args


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=19710)
    ap.add_argument("--grouped", default="agent_tools_grouped.json")
    ap.add_argument("--alias", default="agent_tools_group_alias.json")
    ap.add_argument("--baseline", action="store_true", help="同时跑一遍 A 组（全量 57）做对照")
    ap.add_argument("--gen", type=int, default=256)
    ap.add_argument("--out", default="/data/local/tmp/agent_eval_ab.json")
    a = ap.parse_args()

    grouped = json.loads((HERE / a.grouped).read_text(encoding="utf-8"))
    alias_path = HERE / a.alias
    if alias_path.exists():
        ALIAS.update(json.loads(alias_path.read_text(encoding="utf-8")))
    cases = json.loads((HERE / "agent_cases.json").read_text(encoding="utf-8"))["cases"]

    out = []
    if a.baseline:
        full = json.loads((HERE / "agent_tools_full.json").read_text(encoding="utf-8"))
        out.append(ev.run(a.port, full, f"A组 全量 {len(full)} 工具", cases, a.gen))
        print("\n（切换 B 组：同一 server，前缀会重算，属预期）")

    ev.extract_call = _patched_extract          # 只在跑 B 组时启用映射
    out.append(ev.run(a.port, grouped, f"B组 分组 {len(grouped)} 工具", cases, a.gen))

    try:
        pathlib.Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\n结果已写入 {a.out}")
    except Exception as e:
        print(f"（写 {a.out} 失败：{e}）")


if __name__ == "__main__":
    main()
