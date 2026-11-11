#!/usr/bin/env python3
# 生产复演驱动：以既有项目需求重触发新项目，走真实用户 API 路径（经 nginx，
# Host: semgravio.com），自动答访谈问答卡 → PRD 确认 → 首 Demo 生成，记录
# 各阶段时长。断点续跑口径：生成中断（state=3）自动 POST /generate 续跑。
# 用法：nohup python3 retrigger-driver.py > retrigger-<ts>.log 2>&1 &
import json
import sys
import time
import urllib.request
import urllib.error

BASE = "http://localhost"
HOST = "semgravio.com"
REQUIREMENT = (
    "帮我设计一个帮助小朋友背诵英语单词的小程序。"
    "通过手机扫描或者打字输入新单词 按照记忆曲线测试，"
    "分孩子和家长两个端口，家长端可以监控学习/复习情况"
)

# 访谈问答自动作答口径（与历次复演一致：肯定＋让主智能体按推荐收）
DEFAULT_ANSWER = "可以，按你推荐的方案来，默认配置即可。"
KEYWORD_ANSWERS = [
    (["名字", "名称", "叫什么", "起名", "标题"], "就叫「单词小达人」吧。"),
    (["颜色", "风格", "主题"], "明亮活泼的儿童风格，蓝黄配色即可。"),
    (["技术", "栈", "框架"], "按你推荐的技术方案来。"),
    (["平台", "微信", "小程序"], "微信小程序优先。"),
]


def pick_answer(text: str) -> str:
    low = text or ""
    for keys, ans in KEYWORD_ANSWERS:
        if any(k in low for k in keys):
            return ans
    return DEFAULT_ANSWER


class Api:
    def __init__(self):
        self.cookie = None

    def call(self, method, path, body=None):
        url = BASE + path
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Host", HOST)
        req.add_header("Content-Type", "application/json")
        if self.cookie:
            req.add_header("Cookie", self.cookie)
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                sc = resp.headers.get("Set-Cookie")
                if sc:
                    self.cookie = sc.split(";")[0]
                raw = resp.read().decode()
        except urllib.error.HTTPError as e:
            raw = e.read().decode(errors="replace")
            log(f"HTTP {e.code} {method} {path}: {raw[:500]}")
            return e.code, None
        try:
            return 200, json.loads(raw)
        except json.JSONDecodeError:
            return 200, {"raw": raw}


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def main():
    api = Api()
    t0 = time.time()
    log("== 创建项目 ==")
    code, resp = api.call("POST", "/api/projects", {"requirement": REQUIREMENT})
    if code != 200 or not resp or resp.get("code") not in (0, 200, "0", "200"):
        log(f"创建失败：{code} {resp}")
        sys.exit(1)
    pid = str(resp["data"]["project"]["id"])
    log(f"projectId={pid} runId={resp['data'].get('runId')} cookie={'set' if api.cookie else 'MISSING'}")
    if not api.cookie:
        log("!! 未拿到访客 cookie")
        sys.exit(1)

    answered_ids = set()
    prd_confirmed = False
    last_resume = 0.0
    last_conv_len = -1

    while True:
        elapsed = time.time() - t0
        if elapsed > 5 * 3600:
            log("!! 超时 5h 放弃")
            sys.exit(2)

        code, detail = api.call("GET", f"/api/projects/{pid}")
        if code == 200 and detail and detail.get("data"):
            d = detail["data"]
            gen_state = d.get("generationState")
            gen_interrupted = gen_state in (3, "3", "INTERRUPTED", "中断")
            if not prd_confirmed and d.get("prdProducedAt"):
                log(f"== PRD 已产出（面试耗时 {(time.time()-t0)/60:.1f} min），确认成版 ==")
                c2, r2 = api.call("POST", f"/api/projects/{pid}/prd/confirm", {})
                log(f"confirm -> {c2} {json.dumps(r2, ensure_ascii=False)[:300]}")
                prd_confirmed = True
            if d.get("generatedAt"):
                gen_min = (time.time() - t0) / 60
                log(f"== 生成完成！总耗时 {gen_min:.1f} min（含面试），projectId={pid} ==")
                log(f"detail: generationState={gen_state} generatedAt={d.get('generatedAt')}")
                break
            # 中断（3）或失败（state 对应中断语义）→ 断点续跑（每分钟至多一次）
            if prd_confirmed and gen_interrupted and time.time() - last_resume > 60:
                last_resume = time.time()
                log(f"!! 生成中断（state=3），POST /generate 续跑（累计 {(time.time()-t0)/60:.1f} min）")
                api.call("POST", f"/api/projects/{pid}/generate", {})
        else:
            log(f"detail 读取异常 code={code}")

        if not prd_confirmed:
            code, conv = api.call("GET", f"/api/projects/{pid}/conversation")
            if code == 200 and conv and conv.get("data"):
                entries = conv["data"]
                if len(entries) != last_conv_len:
                    last_conv_len = len(entries)
                    kinds = [e.get("kind") for e in entries]
                    log(f"对话进展：{len(entries)} 条 kind={kinds}")
                for e in entries:
                    if e.get("kind") == 3 and not e.get("answered"):
                        qid = str(e["id"])
                        if qid in answered_ids:
                            continue
                        q = e.get("question") or {}
                        qtext = (q.get("data") or {}).get("text") or q.get("text") or ""
                        run_id = q.get("runId")
                        tool_calls = (q.get("data") or {}).get("toolCalls") or []
                        ans = pick_answer(qtext)
                        log(f"答问答卡 entry={qid} q={qtext[:60]!r} -> {ans[:40]!r}")
                        c3, r3 = api.call(
                            "POST",
                            f"/api/projects/{pid}/questions/{qid}/answer",
                            {"runId": run_id, "toolCalls": tool_calls, "answer": ans},
                        )
                        log(f"answer -> {c3}")
                        answered_ids.add(qid)
        else:
            time.sleep(60)
            continue

        time.sleep(15)


if __name__ == "__main__":
    main()
