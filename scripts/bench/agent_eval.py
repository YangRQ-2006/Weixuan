#!/usr/bin/env python3
# 微玄 4B Agent 工具调用评测台
#
# 目的：回答「4B 模型智力够不够撑 30+ 工具调用的本地 Agent」。
# 做法：把 AgentToolCatalog 里提取出的**真实工具集**（39 个）按 OpenAI 格式喂给
#       llama-server 的 /v1/chat/completions（与 App 完全同一条链路、同一 server 参数），
#       用 26 条真实中文语句逐条判分。
#
# 判分维度（分开统计，因为失败模式完全不同）：
#   fmt   —— 有没有吐出结构合法的 tool_call（模型连格式都学不会 = 硬伤）
#   name  —— 工具选对没有（在 accept 集合里）
#   args  —— required 参数是否齐全 + arg_contains 的字面值是否命中（传错参数 = 半可用）
#   over  —— no_tool 用例里是否乱调工具（过度触发，Agent 里很致命）
#
# 用法：
#   python3 agent_eval.py --port 19710 [--tools full|core|both] [--concurrency 1]
import argparse, json, pathlib, re, sys, time, urllib.request

HERE = pathlib.Path(__file__).resolve().parent

SYSTEM_PROMPT = (
    "你是微玄，一个可以在 Android 手机上执行任务的智能体。"
    "需要操作手机时调用提供的工具；不需要时直接用中文回答。"
    "一次只调用一个最合适的工具。"
)

CORE = ["get_current_context", "launch_app", "search_apps", "tap", "tap_element", "input_text",
        "press_key", "swipe", "observe_screen", "wait", "wait_for_text", "run_command"]


def post(port, payload, timeout=300):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def extract_call(msg):
    """返回 (name, args_dict)；没有工具调用返回 (None, {})。兼容 tool_calls 与旧的 function_call。"""
    tcs = msg.get("tool_calls") or []
    if tcs:
        fn = tcs[0].get("function") or {}
        raw = fn.get("arguments") or "{}"
        try:
            return fn.get("name"), json.loads(raw) if isinstance(raw, str) else (raw or {})
        except Exception:
            return fn.get("name"), {"__unparsed__": raw}
    fc = msg.get("function_call")
    if fc:
        try:
            return fc.get("name"), json.loads(fc.get("arguments") or "{}")
        except Exception:
            return fc.get("name"), {"__unparsed__": fc.get("arguments")}
    return None, {}


def score(case, name, args):
    """→ (fmt, name_ok, args_ok, note)"""
    if case.get("no_tool"):
        if name is None:
            return True, True, True, "正确未调用工具"
        return True, False, False, f"过度触发：调了 {name}"
    if name is None:
        return False, False, False, "未吐出任何 tool_call"
    accept = case["accept"]
    if name not in accept:
        return True, False, False, f"选错工具：{name}（期望 {'/'.join(accept)}）"
    if "__unparsed__" in args:
        return True, True, False, "参数不是合法 JSON"
    miss = [a for a in case.get("must_args", []) if a not in args]
    if miss:
        return True, True, False, f"缺必填参数 {miss}"
    for k, want in (case.get("arg_contains") or {}).items():
        got = args.get(k)
        if got is None:
            return True, True, False, f"缺参数 {k}"
        if str(want).lower() not in str(got).lower():
            return True, True, False, f"参数 {k}={got!r} 不含 {want!r}"
    for k, want in (case.get("arg_expect") or {}).items():
        if str(args.get(k)) != str(want):
            return True, True, False, f"参数 {k}={args.get(k)!r} ≠ {want!r}"
    return True, True, True, "OK"


def run(port, tools, label, cases, gen=256):
    print(f"\n========== 工具基数：{label}（{len(tools)} 个，schema ≈{len(json.dumps(tools, ensure_ascii=False))//2} token）==========")
    print(f"{'用例':<18} {'fmt':<4} {'name':<5} {'args':<5} 说明")
    res = []
    for c in cases:
        payload = {
            "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                         {"role": "user", "content": c["say"]}],
            "tools": tools, "tool_choice": "auto",
            "max_tokens": gen, "temperature": 0,
        }
        t0 = time.time()
        try:
            r = post(port, payload)
            msg = r["choices"][0]["message"]
        except Exception as e:
            print(f"{c['id']:<18} ---- 请求失败：{e}")
            res.append((c["id"], False, False, False))
            continue
        dt = time.time() - t0
        name, args = extract_call(msg)
        fmt, nok, aok, note = score(c, name, args)
        res.append((c["id"], fmt, nok, aok))
        m = "✅" if (fmt and nok and aok) else ("⚠️" if fmt and nok else "❌")
        print(f"{c['id']:<18} {'Y' if fmt else 'N':<4} {'Y' if nok else 'N':<5} {'Y' if aok else 'N':<5} {m} {note}  ({dt:.1f}s)")
    n = len(res)
    fmt = sum(1 for _, f, _, _ in res if f)
    nam = sum(1 for _, _, nm, _ in res if nm)
    arg = sum(1 for _, _, _, a in res if a)
    print(f"—— {label} 合计：格式 {fmt}/{n}  选对工具 {nam}/{n}  参数正确 {arg}/{n}  （全对 {sum(1 for _,f,nm,a in res if f and nm and a)}/{n}）")
    return {"label": label, "n": n, "fmt": fmt, "name": nam, "args": arg,
            "all": sum(1 for _, f, nm, a in res if f and nm and a), "detail": res}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=19710)
    ap.add_argument("--tools", default="both", help="full|core|both|full57|pref28|file:<name>.json")
    ap.add_argument("--gen", type=int, default=256)
    ap.add_argument("--out", default="/data/local/tmp/agent_eval.json")
    a = ap.parse_args()

    allt = json.loads((HERE / "agent_tools.json").read_text(encoding="utf-8"))
    cases = json.loads((HERE / "agent_cases.json").read_text(encoding="utf-8"))["cases"]
    plans = []
    if a.tools.startswith("file:"):
        plans.append(json.loads((HERE / a.tools[5:]).read_text(encoding="utf-8")))
    if a.tools == "pref28":
        plans.append(json.loads((HERE / "agent_tools_pref28.json").read_text(encoding="utf-8")))
    if a.tools == "full57":
        plans.append(json.loads((HERE / "agent_tools_full.json").read_text(encoding="utf-8")))
    if a.tools in ("full", "both"):
        plans.append(allt)
    if a.tools in ("core", "both"):
        core = [t for t in allt if t["function"]["name"] in CORE] + \
               [t for t in allt if t["function"]["name"] == "tap"]  # 去重无副作用
        seen, ded = set(), []
        for t in core:
            k = t["function"]["name"]
            if k not in seen:
                seen.add(k); ded.append(t)
        plans.append(ded)

    out = []
    for i, t in enumerate(plans):
        label = "全量 39 工具" if len(t) > 20 else f"白名单 {len(t)} 工具"
        out.append(run(a.port, t, label, cases, a.gen))
        if i + 1 < len(plans):
            print("\n（切换工具基数：同一 server，前缀会重算，属预期）")
    try:
        pathlib.Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\n结果已写入 {a.out}")
    except Exception as e:
        print(f"（写 {a.out} 失败：{e}）")


if __name__ == "__main__":
    main()
