# -*- coding: utf-8 -*-
"""核对脚本里照抄的提示句、阈值与留存的源码是否一致（只读；不调模型）。用法：python check_texts.py
src/、dsh/ 两个文件夹是取证时留下的一手源码副本（只在本地，不入分支）。"""
import importlib.util
import io
import os
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))


def norm(s):
    return re.sub(r"\s+", " ", s.replace("\r", "")).strip()


def src(p):
    return norm(io.open(os.path.join(HERE, p), encoding="utf-8", errors="replace").read())


def ok(label, cond):
    print("%-52s %s" % (label, "一致" if cond else "!! 不一致"))


def load(variant):
    sys.argv = [sys.argv[0], "--selftest", "none"]      # 自测模式：不读密钥
    os.environ["VARIANT"] = variant
    spec = importlib.util.spec_from_file_location("harness3" + variant, os.path.join(HERE, "v0-harness3.py"))
    h = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(h)
    return h


h, h3 = load("1"), load("3")

oc = src("src/oc-max-steps.ts")
m = re.search(r"MAX_STEPS_PROMPT\s*=\s*`(.*?)`", oc, re.S)
ok("OpenCode MAX_STEPS_PROMPT 全文", bool(m) and m.group(1) == norm(h.MAX_STEPS_PROMPT))

cl = src("src/cline-agent-runtime.ts")
ok("Cline 截断提示句全文", norm(h.CLINE_TRUNCATION_PROMPT) in cl)
ok("Cline 截断连续次数上限 = 3", h.TRUNCATION_LIMIT == 3 and bool(re.search(r"MAX_TOKENS_RECOVERY_LIMIT\s*=\s*3", cl)))

ld = src("src/cline-loop-detection.ts")
ok("Cline soft 3 / hard 5", (h.SOFT_THRESHOLD, h.HARD_THRESHOLD) == (3, 5)
   and bool(re.search(r"softThreshold:\s*3", ld) and re.search(r"hardThreshold:\s*5", ld)))
tpl = (ld.replace("\\`", "`")                                   # 源码是模板字符串，内层反引号带转义
       .replace("${this.state.consecutiveIdenticalCount}", "%d").replace("${call.name}", "%s"))
ok("Cline 第 3 次提示句", ("`" + h.soft_msg(3, "x") + "`") in tpl.replace("%d", "3").replace("%s", "x"))
ok("Cline 第 5 次提示句", ("`" + h.hard_msg(5, "x") + "`") in tpl.replace("%d", "5").replace("%s", "x"))

bolt = src("src/bolt-prompt-workstyle.ts")
ok("bolt.diy 纪律句①②", "Treat tool output as the source of truth. Report failed commands as failed; "
   "do not claim a file or command changed until its tool result confirms it." in bolt)
ok("bolt.diy 纪律句③（stream-text.ts:239）", "Do not claim a task or check is complete without tool evidence." in src("src/bolt-stream-text.ts"))

ge = src("src/gemini-useGeminiStream.ts")
ok("Gemini CLI 空回复提示句全文", ("'" + h.GEMINI_EMPTY_PROMPT + "'") in ge)
ok("Gemini CLI 空回复提醒次数上限 = 2", h.EMPTY_LIMIT == 2 and "MAX_AUTO_NUDGE_ATTEMPTS = 2" in ge)

ed, rd, wr = src("dsh/dsh-tool-fs.edit.ts"), src("dsh/dsh-tool-fs.read.ts"), src("dsh/dsh-tool-fs.write.ts")
ok("DSH 工具说明·读（原文只换工具名）", "Use the read tool — not shell commands like cat, sed, head, or tail — to inspect text files. "
   "Use offset and limit to read a line range or continue a large file." in rd)
ok("DSH 工具说明·写（原文只换工具名）", "Read an existing file before overwriting it with write" in wr
   and "and prefer edit for targeted changes" in wr)
ok("DSH 工具说明·改（原文只换工具名）", "Read a file before editing it unless you just created or edited it in this session." in ed)


def adapt(s):
    """脚本对 DSH 原文做过的三处改动：file_path→path；去掉「resolved by the filesystem backend」；limit 的默认值代入 2000。"""
    return (s.replace("file_path", "path").replace(", resolved by the filesystem backend", "")
             .replace("${caps.limit}", "2000").replace("' + '", ""))       # 源码里相邻两段字符串之间的拼接符


fn = lambda t: t["function"]
E, R, W = fn(h3.EDIT_TOOL), fn(h3.READ_TOOL), fn(h3.TOOLS[0])
ok("DSH edit_file 说明", E["description"] in ed)
for k in ("path", "old_string", "new_string", "replace_all"):
    ok("DSH edit_file 参数说明 " + k, E["parameters"]["properties"][k]["description"] in adapt(ed))
ok("DSH read_file 说明", R["description"] in rd)
for k in ("path", "offset", "limit"):
    ok("DSH read_file 参数说明 " + k, R["parameters"]["properties"][k]["description"] in adapt(rd))
ok("DSH write_file 说明（原文＋自加的建父目录句）", W["description"].replace(" (parent directories are created automatically)", "") in wr)
ok("DSH 读取上限 2000／2000／51200 字节", (h3.READ_LIMIT, h3.READ_MAX_LINE, h3.READ_MAX_BYTES) == (2000, 2000, 51200))
