# -*- coding: utf-8 -*-
"""追加修改的数据点（#590 附带，不在票的原定范围）。

在已经生成好的系统上，按用户的一句修改要求再跑一遍。循环机制与 v0-harness3.py VARIANT=3 完全相同
（一轮不调工具即结束、不催续、到上限关工具如实汇报），只多一条用户消息。
用法：python v0-iter3.py <container> <prd-file> <logfile> <task-file>
      DRY=1 python v0-iter3.py ...   只打印替换后的系统提示和首条用户消息，不调模型
做法：加载 v0-harness3.py（不改它），换掉系统提示里的「任务」一句，对话开头插入一条用户消息。
「任务」替换句是这次自己写的，没有出处。
"""
import importlib.util, io, os, sys

here = os.path.dirname(os.path.abspath(__file__))


def load(container, prd, logfile, task_file):
    os.environ["VARIANT"] = "3"
    sys.argv = [sys.argv[0], container, prd, logfile]      # 必须在加载前置好：脚本在导入时读 argv
    spec = importlib.util.spec_from_file_location("harness3", os.path.join(here, "v0-harness3.py"))
    h = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(h)
    OLD_TASK = "任务：把 /workspace/docs/PRD.md 描述的完整系统实现出来（建表、后端 API、全部页面、演示数据）。"
    NEW_TASK = "任务：/workspace 里已经有一个按 /workspace/docs/PRD.md 实现好的系统，现在按用户提出的修改要求做修改。"
    assert OLD_TASK in h.SYSTEM, "system prompt changed"
    h.SYSTEM = h.SYSTEM.replace(OLD_TASK, NEW_TASK)
    with io.open(task_file, encoding="utf-8") as f:
        task = f.read().strip()
    orig_call, added = h.call, [False]

    def call_with_task(messages):
        if not added[0]:
            messages.insert(1, {"role": "user", "content": task})
            added[0] = True
        return orig_call(messages)

    h.call = call_with_task
    return h, task


if __name__ == "__main__":
    h, task = load(*sys.argv[1:5])
    if os.environ.get("DRY"):
        print(h.SYSTEM)
        print("--- user ---")
        print(task)
        raise SystemExit(0)
    h.log({"iter_task": task, "harness": "v0-iter3 (= v0-harness3 VARIANT=3 + one user message)"})
    h.main()
