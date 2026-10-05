#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 B 组（分组工具 + action 枚举）schema，并与全量 A 组做体量对照。

设计原则（为了让评测只测「分组」这一个变量，不掺其它噪声）：
  1. **不截断任何描述文字** —— 成员工具的原描述按 `工具名: <原描述>` 形式完整放进 action 说明里；
     参数描述也原样保留（并集）。
  2. 省下来的只有「结构性脚手架」：每个工具独立的 name/description/type/object/
     parameters/properties/required 外壳（实测平均 183 字节/工具）。
  3. `required` 只保留 ["action"]（各成员必需参数不同，无法统一）——这是分组方案的固有代价，
     由 action 说明里的文字来补偿（模型需自己判断哪些参数必填）。

产出：
  agent_tools_grouped.json   给 llama-server 用的 tools 数组
  agent_tools_group_alias.json  {组名: {action: 原工具名}}，评测判分时把「组+action」还原成原工具名
"""
import json
import os

BENCH = os.path.dirname(os.path.abspath(__file__))
FULL = os.path.join(BENCH, "agent_tools_full.json")

# ── 分组表：按功能把 57 个工具收敛成 11 组 ──────────────────────────────────────
GROUPS = {
    "device_query": ["get_current_context", "get_setting", "get_health_summary", "recent_notifications",
                     "search_notification_history", "recent_app_activity", "app_usage_summary",
                     "list_alarms", "list_active_timers", "read_sms_code", "get_logcat", "wifi_credentials"],
    "device_action": ["set_alarm", "set_timer", "media_control", "set_volume", "set_setting",
                      "set_device_state", "app_state_control", "open_system_panel"],
    "ui_observe": ["observe_screen", "wait", "wait_for_text", "wait_for_package"],
    "ui_touch": ["tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe",
                 "scroll", "scroll_element"],
    "ui_text": ["input_text", "replace_text", "clear_text", "set_clipboard", "get_clipboard",
                "paste_text", "press_key"],
    "app_launch": ["search_apps", "launch_app", "open_uri"],
    "file": ["read_file", "write_file", "list_directory", "read_image"],
    "shell": ["terminal", "run_command"],
    "memory": ["memory_get", "memory_write"],
    "skills": ["skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
               "skills_inspect_github", "skills_install_from_github"],
    "browser": ["browser_use"],
}

GROUP_DESC = {
    "device_query": "读取设备状态与系统数据。action 决定读什么。",
    "device_action": "修改设备状态或触发系统动作（闹钟/计时器/音量/媒体/设置/应用状态/系统面板）。",
    "ui_observe": "观察屏幕与等待界面变化（观察、等待、等文本、等应用）。",
    "ui_touch": "对屏幕做触控操作（点击、长按、滑动、滚动；可按坐标、区域或节点）。",
    "ui_text": "文字输入与键盘/剪贴板操作。",
    "app_launch": "查找并启动应用或打开链接。",
    "file": "读写文件与查看图片。",
    "shell": "执行终端/命令行命令。",
    "memory": "读写长期记忆。",
    "skills": "技能库的浏览、阅读、检视与安装。",
    "browser": "浏览器操作。",
}


def load_tools(path):
    data = json.load(open(path, encoding="utf-8"))
    items = data if isinstance(data, list) else data.get("tools", [])
    out = {}
    for it in items:
        fn = it.get("function", it)
        out[fn["name"]] = fn
    return out


def main():
    tools = load_tools(FULL)
    grouped, alias = [], {}
    missing = []
    for group, names in GROUPS.items():
        props = {}
        action_desc = []
        required_any = []
        for name in names:
            t = tools.get(name)
            if t is None:
                missing.append(name)
                continue
            action_desc.append(f"{name}: {t.get('description', '').strip()}")
            params = (t.get("parameters") or {}).get("properties", {}) or {}
            for pname, pschema in params.items():
                if pname not in props:          # 并集，冲突时保留首个（描述原样）
                    props[pname] = pschema
            if name != names[0] and (t.get("parameters") or {}).get("required"):
                pass                            # 各成员必需参数不同 → 不做交集推导
        action_prop = {
            "type": "string",
            "enum": [n for n in names if n in tools],
            "description": "；".join(action_desc),
        }
        merged = {"action": action_prop}
        merged.update(props)
        grouped.append({
            "type": "function",
            "function": {
                "name": group,
                "description": GROUP_DESC.get(group, ""),
                "parameters": {"type": "object", "properties": merged, "required": ["action"]},
            },
        })
        alias[group] = {n: n for n in names if n in tools}

    out_dir = BENCH
    json.dump(grouped, open(os.path.join(out_dir, "agent_tools_grouped.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    json.dump(alias, open(os.path.join(out_dir, "agent_tools_group_alias.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    def size(obj):
        raw = json.dumps(obj, ensure_ascii=False)
        b = len(raw.encode())
        return b, b / 6.0

    a_b, a_t = size({"tools": [{"type": "function", "function": t} for t in tools.values()]})
    b_b, b_t = size({"tools": grouped})
    print(f"A组 全量 {len(tools)} 工具 : {a_b} 字节 ≈ {a_t:.0f} token")
    print(f"B组 分组 {len(grouped)} 工具 : {b_b} 字节 ≈ {b_t:.0f} token")
    print(f"→ 省 {a_b-b_b} 字节 ≈ {a_t-b_t:.0f} token（{(a_b-b_b)*100/a_b:.1f}%）")
    if missing:
        print("⚠️ 分组表里未在 full 中出现的名字:", missing)
    print("\n各组体量:")
    for g in grouped:
        b, t = size(g)
        print(f"  {g['function']['name']:<14} {b:>6} 字节 ≈ {t:>5.0f} token  ({len(g['function']['parameters']['properties'])-1} 个可选参数)")


if __name__ == "__main__":
    main()
