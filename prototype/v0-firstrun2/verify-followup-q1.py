# -*- coding: utf-8 -*-
"""主会话复核：第二遍工单应用（v0r2b-q1，追加修改之后）三处修改是否真的生效。
只走应用自己的接口；会新建 1 张标明「请忽略」的测试工单并走完流转。用法：python verify-followup-q1.py"""
import http.cookiejar
import io
import json
import sys
import time
import urllib.error
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
B = "http://localhost:18121"


def client():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(op, method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(B + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        r = op.open(req, timeout=120)
        raw, code = r.read().decode("utf-8", "replace"), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read().decode("utf-8", "replace"), e.code
    try:
        return code, json.loads(raw)
    except Exception:
        return code, raw[:160]


def login(username):
    op = client()
    code, j = call(op, "POST", "/api/auth/login", {"username": username, "password": "123456"})
    assert code == 200, (username, code, j)
    return op


def ticket(op, tid):
    code, j = call(op, "GET", "/api/tickets/%d" % tid)
    t = j.get("ticket", j) if isinstance(j, dict) else j
    keys = ("status", "processingStartedAt", "processingSeconds", "assigneeId", "assigneeName")
    return {k: t.get(k) for k in keys if isinstance(t, dict) and k in t}, (code, list(t.keys())[:14] if isinstance(t, dict) else t)


def step(label, op, method, path, body=None):
    code, j = call(op, method, path, body)
    msg = j.get("error") if isinstance(j, dict) and j.get("error") else ("" if isinstance(j, dict) else str(j)[:80])
    print("  %-58s -> %s %s" % (label, code, msg))
    return code, j


lead, eng, other = login("chenming"), login("liuzhiqiang"), login("wangxiaolei")
print("1) 派单后应是「待开工」，点「开始处理」才计时")
code, j = step("主管新建工单", lead, "POST", "/api/tickets",
               {"customerName": "主会话复核（请忽略）", "contactPhone": "13900000000", "deviceModel": "复核用设备",
                "faultDesc": "追加修改之后的复核用例", "priority": "low"})
tid = j.get("ticket", j).get("id") if isinstance(j, dict) else None
print("     工单 id =", tid)
step("主管派单给刘志强", lead, "POST", "/api/tickets/%s/transition" % tid, {"action": "assign", "assigneeId": 2})
t1, raw1 = ticket(lead, tid)
print("     派单后:", t1 if t1 else raw1)
step("工程师点「开始处理」", eng, "POST", "/api/tickets/%s/transition" % tid, {"action": "start"})
t2, _ = ticket(lead, tid)
print("     开工后:", t2)
time.sleep(3)
step("已开工再点一次「开始处理」", eng, "POST", "/api/tickets/%s/transition" % tid, {"action": "start"})
t3, _ = ticket(lead, tid)
print("     重复开工后:", t3, "| 开工时间未变 =", t2.get("processingStartedAt") == t3.get("processingStartedAt"))

print("2) 没有权限的操作：别人（王筱蕾）对这张单")
step("王筱蕾挂起刘志强的工单", other, "POST", "/api/tickets/%s/transition" % tid, {"action": "suspend", "note": "复核"})
code, j = call(other, "GET", "/api/tickets/%d" % tid)
html = j if isinstance(j, str) else ""
print("     王筱蕾看详情页 HTTP %s，页面里有「只能查看」说明 = %s" % (code, ("只能查看" in html)))

print("3) 已关闭的工单不能编辑")
step("主管先编辑处理中的工单（允许）", lead, "PATCH", "/api/tickets/%d" % tid, {"deviceModel": "复核用设备（已改）"})
step("刘志强提交结果", eng, "POST", "/api/tickets/%s/transition" % tid, {"action": "submit", "note": "复核：已处理完毕"})
step("主管回访关闭", lead, "POST", "/api/tickets/%s/transition" % tid, {"action": "close", "note": "复核关闭"})
t4, _ = ticket(lead, tid)
print("     当前状态:", t4.get("status"))
step("主管编辑已关闭的工单", lead, "PATCH", "/api/tickets/%d" % tid, {"deviceModel": "不该生效"})
step("工程师编辑已关闭的工单", eng, "PATCH", "/api/tickets/%d" % tid, {"deviceModel": "不该生效"})
t5, _ = ticket(lead, tid)
print("     编辑后设备型号仍是:", t5.get("status") and call(lead, "GET", "/api/tickets/%d" % tid)[1].get("ticket", {}).get("deviceModel", "?"))
