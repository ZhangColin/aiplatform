# -*- coding: utf-8 -*-
"""内核 v0 迭代实证 harness（#586）。

v0-harness.py（#576 首建）原样，仅两处按 #577 迭代语义决议改动：
  - SYSTEM 任务句：从「实现完整系统」换成「落实迭代记录中未落地的意见」
    （同机制、异起点：环境句/工具/判据/自然终止逐字不变）；
  - 催续句同步换成迭代口径。
其余（工具 3 件套、300 轮/75min 运营护栏、连续 2 轮无工具调用＝自然终止）
与 #576 逐字相同。用法：python v0-iter-harness.py <container> <label> [logfile]
PRD 迭代节由壳（本实验＝docker cp）落 /workspace/docs/PRD.md，harness 只驱动。
"""
import json, subprocess, time, sys, io, os
import urllib.request

CONTAINER = sys.argv[1]
LABEL = sys.argv[2]
API = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-flash"
GUARD_ROUNDS = 300
GUARD_WALL_SEC = 75 * 60
LOG = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "v0-%s-%s-log.jsonl" % (CONTAINER, LABEL))

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
    "运行，连接串在 /workspace/.env。任务：/workspace 已有一套按 /workspace/docs/PRD.md 实现"
    "并在运行中的系统；PRD 末尾「迭代记录」节按序记录历次修改意见（最后一条最新）。请通读"
    " PRD 并核对工作区现状，把迭代记录中尚未落实的修改意见全部实现（只做意见要求的改动，"
    "其余一律保持现状）。完成后用 pnpm build 验证可构建，把应用重新起在 0.0.0.0:8081 常驻"
    "（如 pnpm start -- -p 8081 后台运行），用 curl http://localhost:8081 确认有响应才算完成；"
    "最后把 PRD 整合改写为最新需求真相（把已落实的迭代记录并入正文，使 PRD 不依赖迭代记录"
    "也可单独读懂）。用工具读写文件、运行命令，可随时跑命令验证与调试。注意：单个文件太长时"
    "拆分或分块写（write_file 覆盖写全量、bash 可追加）。全程中文。"
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
    log({"meta": {"container": CONTAINER, "label": LABEL, "model": MODEL,
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
            messages.append({"role": "user", "content": "若迭代记录已全部落实、pnpm build 通过且 http://localhost:8081 有响应，就直接结束、不要再做额外核对；只有还有未落实的修改时才继续。"})
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
