# -*- coding: utf-8 -*-
"""主会话复核：第二遍仓库应用（v0r2b-q3，追加修改之后）两处修改是否真的生效。只走应用自己的接口；
会新建 1 个标明「请忽略」的商品、1 张盘点单、2 张入库单、1 张出库单，跑完后按商品编号从库里删掉这些复核数据。
用法：python verify-followup-q3.py"""
import datetime
import io
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
B = "http://localhost:18123"
SKU = "ZTH-VERIFY-001"


def call(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(B + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        r = req_obj = urllib.request.urlopen(req, timeout=180)
        raw, code = r.read().decode("utf-8", "replace"), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read().decode("utf-8", "replace"), e.code
    try:
        return code, json.loads(raw)
    except Exception:
        return code, raw[:200]


def show(label, code, j, keys):
    d = j.get("data", j) if isinstance(j, dict) else j
    picked = {k: d.get(k) for k in keys if isinstance(d, dict) and k in d} if isinstance(d, dict) else d
    err = j.get("error") if isinstance(j, dict) else ""
    print("  %-46s -> %s %s" % (label, code, (err or "") + " " + json.dumps(picked, ensure_ascii=False)[:230]))


def find_product():
    code, j = call("GET", "/api/products?keyword=%s" % SKU)
    d = j.get("data", j) if isinstance(j, dict) else j
    items = d.get("items") if isinstance(d, dict) else None
    if items is None and isinstance(d, list):
        items = d
    if items is None and isinstance(d, dict):
        items = d.get("list") or []
    return (items or [None])[0]


print("0) 低库存口径：安全线 5")
code, j = call("POST", "/api/products", {"sku": SKU, "name": "主会话复核商品（请忽略）", "category": "主会话复核",
                                         "model": "复核", "unit": "台", "safetyStock": 5, "lastInPrice": 0})
show("新建商品（库存 0，安全线 5）", code, j, ("id", "sku", "stockQty", "status"))
p = find_product()
pid = p["id"]
print("     商品 id =", pid, "| 当前:", {k: p.get(k) for k in ("stockQty", "safetyStock", "status") if k in p})

today = datetime.date.today().isoformat()
print("1) 盘点期间有入库：完成盘点不应把库存再算一遍")
code, j = call("POST", "/api/stocktakes", {"category": "主会话复核", "operator": "主会话复核"})
show("发起盘点", code, j, ("id", "status"))
sid = (j.get("data", j) if isinstance(j, dict) else j).get("id")
code, j = call("POST", "/api/inbound", {"supplier": "主会话复核（请忽略）", "orderDate": today, "confirmed": True,
                                        "items": [{"productId": pid, "quantity": 2, "unitPrice": 0}]})
show("盘点期间入库 2 件并确认", code, j, ("id", "status"))
code, j = call("PATCH", "/api/stocktakes/%s" % sid, {"items": [{"productId": pid, "actualQty": 2}]})
show("录入实盘 2 件", code, j, ("id", "status"))
code, j = call("POST", "/api/stocktakes/%s/complete" % sid)
show("完成盘点", code, j, ("id", "status", "stats"))
p = find_product()
print("     完成后库存 =", p.get("stockQty"), "（应为 2）| 状态 =", p.get("status"))

print("2) 低库存应是「严格小于安全线」")
code, j = call("POST", "/api/inbound", {"supplier": "主会话复核（请忽略）", "orderDate": today, "confirmed": True,
                                        "items": [{"productId": pid, "quantity": 3, "unitPrice": 0}]})
p = find_product()
print("     再入库 3 件 -> 库存 =", p.get("stockQty"), "（等于安全线 5）| 状态 =", p.get("status"), "（应为 normal）")
code, j = call("POST", "/api/outbound", {"customer": "主会话复核（请忽略）", "orderDate": today, "confirmed": True,
                                         "items": [{"productId": pid, "quantity": 1}]})
p = find_product()
print("     出库 1 件 -> 库存 =", p.get("stockQty"), "（低于安全线）| 状态 =", p.get("status"), "（应为 low）")
code, j = call("GET", "/api/products?status=low&category=%s" % urllib.parse.quote("主会话复核"))
d = j.get("data", j) if isinstance(j, dict) else j
items = d.get("items") if isinstance(d, dict) else d
print("     「低库存」筛选在这个分类下命中 =", len(items or []), "条")

print("3) 清理复核数据")
# 清理用事务；表名是 v0r2b-q3 这个应用自己的（stock_in_items／stock_out_items／stocktake_items／stock_movements.quantity）。
# 2026-11-23 实跑时这里写错过一次表名，事务整体回滚、没有删掉任何东西，之后改成下面的正确写法。
sql = ("BEGIN; DELETE FROM stocktake_items WHERE stocktake_id = %d; DELETE FROM stocktakes WHERE id = %d; "
       "DELETE FROM stock_out_items WHERE order_id IN (SELECT id FROM stock_out_orders WHERE customer LIKE '主会话复核%%'); "
       "DELETE FROM stock_out_orders WHERE customer LIKE '主会话复核%%'; "
       "DELETE FROM stock_in_items WHERE order_id IN (SELECT id FROM stock_in_orders WHERE supplier LIKE '主会话复核%%'); "
       "DELETE FROM stock_in_orders WHERE supplier LIKE '主会话复核%%'; "
       "DELETE FROM stock_movements WHERE product_id = %d; DELETE FROM products WHERE id = %d AND sku = '%s'; COMMIT; "
       "SELECT 'leftover=' || count(*) FROM products WHERE sku = '%s';") % (sid, sid, pid, pid, SKU, SKU)
io.open("_cleanup.sql", "w", encoding="utf-8", newline="\n").write(sql)
subprocess.run(["docker", "cp", "_cleanup.sql", "v0r2b-q3:/tmp/_cleanup.sql"])
out = subprocess.run(["docker", "exec", "v0r2b-q3", "sh", "-c",
                      'psql "$(grep ^DATABASE_URL /workspace/.env | cut -d= -f2-)" -At -f /tmp/_cleanup.sql'], capture_output=True)
print("     ", (out.stdout + out.stderr).decode("utf-8", "replace").strip().replace("\n", " | ")[:400])
