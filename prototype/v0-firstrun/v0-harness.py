# -*- coding: utf-8 -*-
"""内核 v0 首建实证 harness（#576）。

腿④ bare-harness.py 原样，仅按 #575 决议改动一处：
  - 废除 MAX_ROUNDS=150 硬截停（干到做完：无轮次上限，自然终止唯一＝
    连续 2 轮无工具调用、中间一次「继续直到完成」催续）。
  - 保留实验保护线（运营护栏非工作语义，#575 口径）：300 轮 / 75min 墙钟，
    触发即停并如实记录（喂雾区「时长与成本治理」）。
用法：python v0-harness.py <container> <prd-file> [logfile]
PRD 由壳（本实验＝docker cp）落 /workspace/docs/PRD.md，harness 只驱动。
"""
import json, subprocess, time, sys, io, os
import urllib.request

CONTAINER = sys.argv[1]
PRD_FILE = sys.argv[2]
API = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-flash"
# 实验保护线（非内核语义）
GUARD_ROUNDS = 300
GUARD_WALL_SEC = 75 * 60
LOG = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "v0-%s-log.jsonl" % CONTAINER)

def api_key():
    with io.open(r"c:\Users\zhang\workspace\aiplatform\aiplatform-server\.env.local", encoding="utf-8") as f:
        for line in f:
            if line.startswith("DEEPSEEK_API_KEY="):
                return line.split("=", 1)[1].strip().strip('"')
    raise SystemExit("no key")

KEY = api_key()

SYSTEM = (
    "你是全栈工程师。环境：Linux 容器，/workspace 是配置好的 Next.js(App Router)+React 19"
    "+Tailwind 4+shadcn 工程，pnpm 可用、node_modules 已装；容器内 PostgreSQL 与 Redis 已在"
    "运行，连接串在 /workspace/.env。任务：把 /workspace/docs/PRD.md 描述的完整系统实现出来"
    "（建表、后端 API、全部页面、演示数据）。用工具读写文件、运行命令，可随时跑命令验证与"
    "调试。注意：单个文件太长时拆分或分块写（write_file 覆盖写全量、bash 可追加）。完成后"
    "用 pnpm build 验证可构建，并把应用起在 0.0.0.0:8081 常驻（如 pnpm start -- -p 8081 "
    "后台运行），用 curl http://localhost:8081 确认有响应才算完成。全程中文。"
)

TOOLS = [
    {"type": "function", "function": {"name": "write_file",
        "description": "覆盖写文件（全量内容）",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"]}}},
    {"type": "function", "function": {"name": "read_file",
        "description": "读文件内容",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {"name": "bash",
        "description": "在容器内 sh -c 执行命令（超时 180s）",
        "parameters": {"type": "object", "properties": {
            "command": {"type": "string"}}, "required": ["command"]}}},
]

def call_api(messages):
    body = json.dumps({"model": MODEL, "messages": messages, "tools": TOOLS,
                       "max_tokens": 8192, "stream": False}).encode("utf-8")
    req = urllib.request.Request(API, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode("utf-8"))

def run_tool(name, args):
    try:
        if name == "write_file":
            p = subprocess.run(["docker", "exec", "-i", CONTAINER, "sh", "-c",
                "cat > '%s'" % args["path"].replace("'", "'\\''")],
                input=args["content"].encode("utf-8"), capture_output=True, timeout=60)
            out = (p.stdout + p.stderr).decode("utf-8", "replace")[:2000] or "ok"
            return out, p.returncode == 0
        if name == "read_file":
            p = subprocess.run(["docker", "exec", CONTAINER, "sh", "-c",
                "cat '%s'" % args["path"].replace("'", "'\\''")],
                capture_output=True, timeout=60)
            return p.stdout.decode("utf-8", "replace")[:30000], p.returncode == 0
        if name == "bash":
            p = subprocess.run(["docker", "exec", CONTAINER, "sh", "-c", args["command"]],
                capture_output=True, timeout=180)
            out = ((p.stdout + b"\n--stderr--\n" + p.stderr).decode("utf-8", "replace"))
            return out[:6000] or "(no output)", p.returncode == 0
        return "unknown tool", False
    except subprocess.TimeoutExpired:
        return "TIMEOUT after 180s", False
    except Exception as e:
        return "ERROR: %s" % e, False

def log(obj):
    with io.open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")

def main():
    t0 = time.time()
    log({"meta": {"container": CONTAINER, "prd": PRD_FILE, "model": MODEL,
                  "guard": {"rounds": GUARD_ROUNDS, "wall_sec": GUARD_WALL_SEC},
                  "t0": t0}})
    messages = [{"role": "system", "content": SYSTEM}]
    idle_text = 0
    rnd = 0
    u_prompt = u_completion = 0
    while True:
        rnd += 1
        if rnd > GUARD_ROUNDS or (time.time() - t0) > GUARD_WALL_SEC:
            log({"stopped": "OPERATIONAL GUARDRAIL", "round": rnd,
                 "wall_sec": round(time.time() - t0),
                 "usage_total": {"prompt": u_prompt, "completion": u_completion}})
            print("GUARDRAIL STOP r%d %.0fs" % (rnd, time.time() - t0))
            return
        t1 = time.time()
        try:
            resp = call_api(messages)
        except Exception as e:
            log({"round": rnd, "ts": time.time() - t0, "api_error": str(e)[:300]})
            time.sleep(5)
            continue
        msg = resp["choices"][0]["message"]
        usage = resp.get("usage", {})
        u_prompt += usage.get("prompt_tokens", 0)
        u_completion += usage.get("completion_tokens", 0)
        calls = msg.get("tool_calls") or []
        if not calls:
            idle_text += 1
            log({"round": rnd, "elapsed": round(time.time() - t1, 1),
                 "text": (msg.get("content") or "")[:300],
                 "usage": usage, "idle_text": idle_text})
            if idle_text >= 2:
                log({"final_text": (msg.get("content") or "")[:2000],
                     "total_sec": round(time.time() - t0), "rounds": rnd,
                     "usage_total": {"prompt": u_prompt, "completion": u_completion}})
                print("DONE no-tool-calls x2 r%d %.0fs" % (rnd, time.time() - t0))
                return
            messages.append({"role": "assistant", "content": msg.get("content") or ""})
            messages.append({"role": "user", "content": "继续，直到完成 PRD 全部功能并在 8081 起服务。"})
            continue
        idle_text = 0
        messages.append(msg)
        summary = []
        for c in calls:
            fn = c["function"]
            try:
                args = json.loads(fn["arguments"] or "{}")
            except Exception:
                args = {"_raw": (fn["arguments"] or "")[:200]}
            result, ok = run_tool(fn["name"], args)
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": result})
            a0 = args.get("path") or args.get("command") or ""
            summary.append("%s:%s" % (fn["name"], str(a0)[:100]))
        log({"round": rnd, "elapsed": round(time.time() - t1, 1), "calls": summary, "usage": usage})
        sys.stdout.write("r%d %.0fs %s\n" % (rnd, time.time() - t1, "; ".join(summary)[:200]))
        sys.stdout.flush()

if __name__ == "__main__":
    main()
