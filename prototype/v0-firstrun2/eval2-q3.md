# 题目 3「小型仓库出入库管理系统」新旧应用验收复核

- 复核时间：2026-11-23 00:42–01:05 UTC（北京时间 08:42–09:05）
- 旧应用：容器 `v0-run3`，宿主机 http://localhost:18093（容器内 8081）。2026-11-21 生成，生产模式 `next start` 运行。
- 新应用：容器 `v0r2b-q3`，宿主机 http://localhost:18123（容器内 8081）。2026-11-23 生成，开发模式 `next dev` 运行。
- 需求文档：`C:/Users/zhang/AppData/Local/Temp/v0-firstrun/prd-run3-warehouse.md`（两容器 `/workspace/docs/PRD.md` 同文）。
- 方法：curl 探页面和 API、`psql` 查库、Chromium 1366×900 截图并逐页收集浏览器控制台错误。两个应用都没有登录页（源码里 `auth.ts` 只是基座的会话工具，没有任何登录路由），不需要登录。
- 写入纪律：旧应用零写入（结束时行数与序列号和开始时完全一致，见 4.3 节）。新应用做了一组明确标注「验收测试」的写操作，全部列在第 7 节。

## 1. 结论概览

| | 完成 | 部分 | 未做 |
| --- | --- | --- | --- |
| 旧应用（六、功能清单 6 项） | 3 | 3 | 0 |
| 新应用（六、功能清单 6 项） | 6（其中「盘点」1 项带一个已实测复现的缺陷） | 0 | 0 |

- 旧应用最严重的问题：当前运行中的服务用的是一份「过期构建」。服务 12:52 启动后，`.next` 目录在 12:55 又被重新构建，运行中的服务还在引用 3 个已经不存在的页面脚本文件。结果 `/products/new`、`/products/1/edit`、`/inbound/new`、`/outbound/new` 四个表单页的脚本请求全部 HTTP 500，浏览器里显示「页面加载失败」或者表单看得见但点不动。也就是说，**商品建档、新建入库单、新建出库单这三条新建流程在当前实例里都走不通**（接口代码本身是齐全的，但我按规则没有在旧应用上发写请求）。
- 新应用最严重的问题：**完成盘点时不重算账面数**。盘点发起后如果仓库又入了货，完成盘点时系统仍拿「发起时的账面数」算差异，会把这批货重复记一遍。我实测复现：发起盘点 → 入库 2 件 → 按实际 2 件录入并完成盘点 → 系统记成「盘盈 +2」，库存从 2 变成 4（实物只有 2）。旧应用源码里明确写了「确认时按最新库存重算账面数」。

## 2. 逐条对照表

| 条目 | 旧应用 | 新应用 | 证据 |
| --- | --- | --- | --- |
| 六-1 商品管理：名称、分类、型号、单位、安全库存线；列表＋搜索＋分类筛选 | **部分** | **完成** | 旧：`GET /products` 200，浏览器里 51 行；搜索「冰箱」→ 12 行，分类筛选「冰箱」→ 11 行（已实测）。字段齐全（`products` 表：name/category/model/unit/safety_stock/last_price）。但 `/products/new` 页面脚本 `/_next/static/chunks/2-jotapbcbp4r.js` 返回 500，浏览器显示「页面加载失败」；`/products/1/edit` 脚本 `26vrn4rmaqnu4.js` 返回 500，表单虽显示但页面没被激活（等 6 秒后有时也变成错误页）。新建、编辑都无法保存。<br>新：`GET /products` 200，55 行（验收后 56 行）；搜索「冰箱」→ 11 行，状态筛选「缺货」→ 3 行；点「新增商品」展开表单（商品编码/名称/分类/型号/单位/安全库存线/参考单价），我实际建了 1 个商品（id 56）；点「编辑」弹出预填好的表单（未保存）。控制台 0 错误。 |
| 六-2 入库单：新建（供应商、多行明细、数量、单价），确认后库存增加，列表与详情可查 | **部分** | **完成** | 旧：`/inbound` 列表 200、53 行，支持单号/供应商搜索和日期区间；`/inbound/1` 详情 200。新建页 `/inbound/new` 脚本 `1fi68alcnu60v.js` 返回 500：一次实测等 6 秒后显示「页面加载失败」，另一次表单显示出来但点「添加一行」没有任何反应（行数 1→1），即页面不可用。接口 `POST /api/inbound` 源码（`src/lib/services/inbound.ts`）逻辑完整：一个事务里写单头、明细、入库流水、更新最近进价；但未实测。<br>新：浏览器里走通——填供应商「验收测试供应商」、选商品、数量 2、单价 100、点「确认入库」→ 跳到 `/inbound/73`，状态「已确认」，提示「库存已增加（RK20261123-02）」，商品库存 0→2，`stock_movements` 多一行 `+2`。列表 72→73 行，支持搜索和状态筛选（草稿→2 行）；详情页正常。另有「保存为草稿 → 确认入库」两步流程（种子数据里有 2 张草稿带「确认入库」按钮，未点）。 |
| 六-3 出库单：新建（客户、多行明细、数量），保存时逐行校验库存并提示不足，确认后库存扣减，列表可查 | **部分** | **完成** | 旧：`/outbound` 列表 200、53 行，搜索「张先生」→ 4 行；`/outbound/1` 详情 200。新建页 `/outbound/new` 脚本同一个 500 文件，浏览器显示「页面加载失败」。源码（`src/lib/services/outbound.ts`）有逐行库存校验（同一商品多行合并校验、`FOR UPDATE` 锁行），不足时返回 `issues`（商品、需要数、可用数）且不生成单据，表单组件逐行红字提示——但当前实例打不开表单，接口也没实测（遵守只读）。<br>新：浏览器里走通——选一个缺货商品数量 1，该行立刻变红并提示「现存 0 台（不足，尚缺 1）」，右侧黄框「有 1 行数量超过当前库存，确认出库会被拦下」；实际提交库存不足的单（需 5 现存 1）被服务端拒绝：红框「库存不足：验收测试-即热式电热水器（需 5，现存 1）」，停留在表单页，库存不变、没生成单据。正常出库单 `CK20261123-04`（3 件）确认后库存 4→1，流水多一行 `-3`。列表搜索、状态筛选正常。 |
| 六-4 库存看板：商品/分类/数量/状态，低于安全线标红，首页缺货预警区 | **完成** | **完成** | 旧：`/inventory` 200、51 行，列有当前库存、库存水位条、安全线、状态；状态筛选「缺货」→ 4 行，徽章「缺货」红字、整行浅红底；低库存行黄色徽章、库存数红字。首页「库存预警」面板列出 10 条（缺货 4 红徽章、低库存 6 黄徽章）。`GET /api/inventory?status=out` 4 条、`status=low` 6 条。<br>新：`/inventory` 200、55 行；状态筛选「缺货」→ 3 行（红徽章、库存数红字、浅红行底），「低库存」→ 4 行（橙黄徽章、橙字）。首页「库存预警」7 条（缺货 3、低库存 4）。API 同口径。<br>口径差别：旧应用「低库存」= 库存严格小于安全线（符合文档「低于」）；新应用是「小于或等于」，库存刚好等于安全线也算低库存（当前数据里没有正好相等的商品，暂不影响显示）。 |
| 六-5 盘点：按分类发起、逐项录入实盘数、生成差异表（盘盈/盘亏/一致） | **完成**（只读核对） | **完成，但有缺陷** | 旧：`/stocktakes` 页右侧「发起新盘点」卡：盘点范围下拉（全仓或单分类）、盘点说明、「创建盘点单」按钮（未点）。草稿盘点 `/stocktakes/2` 12 行可录入：我在第一行输入 40（账面 36），该行立刻显示「+4 盘盈 +4」，计数变「已录入 6 / 12」、「盘盈 1 盘亏 2 一致 3」，「有差异/未录入」筛选可用（未保存）。已确认盘点 `/stocktakes/1` 显示 盘盈 1 / 盘亏 4 / 一致 5，库里有对应的 5 条盘点调账流水。另有 `/stocktakes/1/print` 打印差异表页。源码里确认盘点时按「确认时刻」重算账面数。<br>新：浏览器里走通——`/stocktakes/new` 选「热水器」→ 生成盘点单 `PD20261123-04`（7 行，账面数快照）；逐行录入实盘数 → 「完成盘点」→ 显示「账实差异表」，结论列 盘盈/盘亏/一致，统计卡「盘盈 1 项、盘亏 0 项、一致 6 项」。种子里的 2 张已完成盘点（1 张含盘亏 1、盘盈 1）库里调账流水对得上。**缺陷**：账面数不重算，盘点期间入库的 2 件被记成「盘盈 +2」，库存 2→4（见第 6.2 节）。 |
| 六-6 报表：本月入库总量、出库总量、TOP10 出库、库存金额四块卡片＋缺货清单 | **完成** | **完成** | 旧：`/reports` 200，四卡：本月入库 622 件（10 张单，¥982,181.63）、本月出库 347 件（17 张单，¥376,965.86）、库存金额 ¥3,720,373.24（51 SKU · 2,498 件）、缺货商品 4 个（另有 6 个低于安全线）；TOP10 表 10 行；缺货清单 4 行（只列库存为 0 的）。`GET /api/reports` 数字与 SQL 直接算的结果完全一致。<br>新：`/reports` 200，四卡（验收测试前）：入库 1,804 件（22 张单，¥879,067.49）、出库 387 件（21 张单，¥316,205.61）、库存金额 ¥1,587,346.75（55 SKU · 3,677 件）、缺货/低库存 3/4；TOP10 表 10 行；「缺货与低库存清单」7 行（把低库存也列进去了）；另有近 6 个月趋势。`GET /api/reports` 与 SQL 结果一致。<br>注：新应用的「出库金额」按前端填的单价算，表单默认给出「最近进价 × 1.1」；旧应用按最近进价当成本。文档没规定出库单价，不算对错。 |
| 三-1 入库确认后库存自动增加 | 未实测（表单打不开，接口只读未发） | 实测通过 | 新：库存 0→2，流水 +2，见六-2。旧：种子数据里每张入库单的明细数量都和流水一一对应（53/53 张）。 |
| 三-2 出库确认后库存自动扣减 | 未实测（同上） | 实测通过 | 新：库存 4→1，流水 −3，见六-3。旧：种子数据 53/53 张出库单明细与流水对应。 |
| 三-2 库存不足时该行提示 | 当前实例看不到（表单页报错） | 实测通过 | 新：行内红字「现存 1 台（不足，尚缺 4）」＋提交后服务端拒绝「库存不足：…（需 5，现存 1）」，未生成单据。旧：源码有逐行提示和服务端拒绝，未实测。 |
| 三-3 低库存与缺货行标红 | 通过 | 通过 | 见六-4。 |
| 三-6 首页缺货预警区 | 通过（10 条） | 通过（7 条） | 旧 `GET /api/dashboard` → `alerts` 10 条；新 → `alerts` 7 条。截图 old-00-home-full.png / new-00-home-full.png。 |
| 八 六项功能走通闭环（建档→入库→出库→盘点→报表） | **部分**：当前实例建档/入库/出库三个新建表单不可用 | **完成**：我用 1 个测试商品把整条链路走了一遍（见第 7 节） | — |
| 八 库存数字口径一致（入库加、出库减、盘点调账） | 通过（51/51） | 通过（56/56，含我新增的测试数据） | 见第 4 节。 |
| 八 演示数据 ≥40 个 SKU、≥100 条出入库单分布在近三个月 | 通过：51 个 SKU、106 张单 | 通过：55 个 SKU、153 张单 | 见第 3 节。 |
| 八（待确认）库存金额按最近一次入库单价 | 通过：50 个有入库的商品最近进价全部等于日期最新的入库单价 | 通过：55/55；但有一点不同，见 6.2 节第 3 条 | 旧：`last_price` 与「按单据日期取最新一条入库明细」0 处不一致。新：`last_in_price` 与「最新已确认入库单价」0 处不一致。 |
| 五 演示数据真实可信（中文商品名、真实感型号与数量） | 通过 | 通过（有两处小瑕疵，见 6.2 节） | 旧：海尔/美的/格力/西门子等 51 个真实样式型号；新：55 个，型号同样是真实样式。 |

## 3. 演示数据核对（对照文档数字）

| 检查项 | 文档要求 | 旧应用（实测） | 新应用（实测） |
| --- | --- | --- | --- |
| SKU 数 | ≥ 40 | **51**（冰箱 11、空调 12、洗衣机 10、厨电 8、配件 10） | **55**（验收测试后 56：安装配件 12、空调 10、冰箱 10、厨房电器 9、洗衣机 8、热水器 6） |
| 出入库单总数 | ≥ 100 | **106**（入库 53 ＋ 出库 53，全部已确认） | **153**（入库 72 ＝ 已确认 70 ＋ 草稿 2；出库 81 ＝ 已确认 79 ＋ 草稿 2） |
| 日期范围 | 近三个月 | 2026-08-24 ～ 2026-11-21，106 张全部在近三个月内，无未来日期 | 2026-08-26 ～ 2026-11-23，153 张全部在近三个月内，无未来日期 |
| 按月分布 | — | 8 月 5/2、9 月 21/17、10 月 17/17、11 月 10/17（入库/出库） | 8 月 5/7、9 月 22/29、10 月 21/22、11 月 24/23 |
| 供应商 / 客户数 | — | 9 家供应商、12 个客户，名字真实（海尔智家华东仓、杭州湾酒店采购部…） | 11 家供应商、14 个客户，名字真实 |
| 多行明细 | 多行商品明细 | 入库单平均 3.2 行（最多 4），出库单平均 1.8 行（最多 6） | 入库单平均 2.5 行（最多 9），出库单平均 1.9 行（最多 3） |
| 单头合计与明细 | 一致 | 106 张单的数量、金额合计与明细求和 0 处不一致 | 153 张单 0 处不一致 |
| 商品名与型号 | 中文、真实感 | 如「海尔十字对开门冰箱 BCD-518WGHSS5D9U1」「格力1.5匹挂机空调 KFR-35GW/NhAd1BAj」 | 如「格力挂机空调 1.5 匹 KFR-35GW/NhAd1BAj」「林内燃气热水器 13 升 JSQ26-C05」 |
| 盘点数据 | — | 2 张：配件区已确认（盘盈 1、盘亏 4、一致 5）、空调区录入中（已录 5/12） | 3 张：安装配件已完成（盘盈 1、盘亏 1）、冰箱已完成（盘盈 1）、空调进行中（0/10） |

新应用演示数据的两处小瑕疵（都不影响「数量够不够」的结论）：

1. 7 条「今天」的记录创建时间晚于复核时的真实时钟：1 张入库单、3 张出库单、3 张盘点单的 `created_at`，以及 5 条流水的 `occurred_at`（例如盘点单写的是 2026-11-23 08:30～11:00 UTC，而复核时才 00:43 UTC）。页面按容器时区（UTC）原样显示这些时间。
2. 2 个 SKU 在 2026-08-29 先出库后入库（id 15 海尔净风挂机 10:59 出库 3 件、13:05 才首次入库；id 33 史密斯电热水器 同样），按时间顺序账面会短暂为负（−3、−1）。旧应用按时间顺序余额从未为负。

## 4. 库存口径一致性检查

### 4.1 旧应用（库存来自视图 `product_stock` = `SUM(stock_movements.qty_change)`）

```sql
-- 库存 = 入库明细 − 出库明细 + 已确认盘点差异
with exp as (
  select p.id,
    coalesce((select sum(quantity) from inbound_items i where i.product_id=p.id),0)
   -coalesce((select sum(quantity) from outbound_items o where o.product_id=p.id),0)
   +coalesce((select sum(coalesce(si.diff,0)) from stocktake_items si join stocktakes s on s.id=si.stocktake_id
              where si.product_id=p.id and s.status='confirmed'),0) as expected
  from products p where p.active)
select count(*) filter (where e.expected <> v.stock_qty), count(*), count(*) filter (where v.stock_qty < 0)
from exp e join product_stock v on v.id = e.id;
```

结果：**0 处不一致 / 51 行，负库存 0**。另外：每张入库单、出库单的明细合计与对应流水合计 53/53 张全部相等；流水里没有找不到对应单据的「孤儿」记录；正负号全部正确；流水日期与单据日期 0 处不一致。

### 4.2 新应用（`products.stock_qty` 是存下来的数，`stock_movements` 是流水）

```sql
with m as (select product_id, sum(quantity) s from stock_movements group by 1)
select count(*) filter (where p.stock_qty <> coalesce(m.s,0)), count(*), count(*) filter (where p.stock_qty < 0)
from products p left join m on m.product_id = p.id;
```

结果：验收测试前 **0 / 55**，测试后 **0 / 56**，负库存 0。同时用「已确认入库明细 − 已确认出库明细 + 已完成盘点差异」再算一遍，也是 0 处不一致；每张已确认单据、每条明细都与流水逐条对上；没有指向草稿单的流水；草稿单没有影响库存。

### 4.3 旧应用未被改动的证明

开始和结束各查一次：products 51、inbound_orders 53、inbound_items 172、outbound_orders 53、outbound_items 98、stock_movements 275、stocktakes 2、stocktake_items 22；四个序列号 `last_value`（53/53/51/2）未动；最晚一条流水和单据的创建时间仍是 2026-11-21。

## 5. 探测过的路由 / API

### 5.1 旧应用（http://localhost:18093）

| 路径 | 状态 | 备注 |
| --- | --- | --- |
| `/`、`/products`、`/inbound`、`/outbound`、`/inventory`、`/stocktakes`、`/reports` | 200 | 列表页在浏览器里都正常，控制台 0 错误 |
| `/products/new` | 200（HTML） | 页面脚本 500 → 浏览器「页面加载失败」 |
| `/products/1/edit` | 200（HTML） | 页面脚本 500 → 表单不激活/变错误页 |
| `/inbound/new`、`/outbound/new` | 200（HTML） | 页面脚本 500 → 浏览器「页面加载失败」 |
| `/inbound/1`、`/outbound/1`、`/stocktakes/1`、`/stocktakes/2`、`/stocktakes/1/print` | 200 | 正常 |
| `/nonexistent-page` | 404 | 正常 404 页 |
| `/icon.svg` | 200 | 网站图标存在（新应用没有） |
| `/_next/static/chunks/2-jotapbcbp4r.js` | **500** | products/new 的脚本，磁盘上不存在 |
| `/_next/static/chunks/26vrn4rmaqnu4.js` | **500** | products/[id]/edit 的脚本，磁盘上不存在 |
| `/_next/static/chunks/1fi68alcnu60v.js` | **500** | inbound/new 与 outbound/new 的脚本，磁盘上不存在 |
| 其余 13 个页面引用的脚本 | 200 | — |
| `GET /api/products`、`?search=海尔`、`?category=冰箱`、`/api/products/1` | 200 | `{ok:true,data:…}` 信封 |
| `GET /api/products/999999` | 404 | `{"ok":false,"error":"商品不存在"}` |
| `GET /api/inbound`、`/api/inbound/1`、`/api/outbound`、`/api/outbound/1` | 200 | — |
| `GET /api/inbound/999999`、`/api/outbound/999999`、`/api/stocktakes/999999` | 404 | 资源不存在返回 404 |
| `GET /api/inventory`、`?status=low`（6 条）、`?status=out`（4 条） | 200 | — |
| `GET /api/stocktakes`、`/api/stocktakes/1` | 200 | — |
| `GET /api/reports`、`/api/dashboard`、`/api/health` | 200 | `/api/health` → `{"ok":true,"db":true,…}` |

### 5.2 新应用（http://localhost:18123）

| 路径 | 状态 | 备注 |
| --- | --- | --- |
| `/`、`/products`、`/inbound`、`/outbound`、`/inventory`、`/stocktakes`、`/reports` | 200 | 浏览器控制台 0 错误、0 失败请求 |
| `/inbound/new`、`/outbound/new`、`/stocktakes/new`、`/inbound/1`、`/inbound/71`（草稿）、`/outbound/1`、`/outbound/80`（草稿）、`/stocktakes/1`、`/2`、`/3` | 200 | 全部正常 |
| `/products/new`、`/products/1/edit`、`/stocktakes/1/print` | 404 | 新应用把新建/编辑做成了 `/products`、`/stocktakes` 页内的面板，没有独立路由；没有打印页 |
| `/nonexistent-page` | 404 | — |
| `/favicon.ico` | 404 | 没有网站图标，浏览器控制台会记一条 404 |
| `GET /api/products`、`?search=海尔`、`?category=冰箱`、`/api/products/1`、`/api/products/options` | 200 | — |
| `GET /api/products/999999`、`/api/inbound/999999`、`/api/outbound/999999`、`/api/stocktakes/999999` | **200** | 内容是 `{"ok":false,"error":"xx不存在"}`，但 HTTP 状态码是 200 而不是 404 |
| `GET /api/inbound`、`/api/inbound/1`、`?status=draft`（2 条） | 200 | — |
| `GET /api/outbound`、`/api/outbound/1`、`?status=draft`、`?search=张先生` | 200 | — |
| `GET /api/inventory`、`?status=low`（4 条）、`?status=out`（3 条） | 200 | — |
| `GET /api/stocktakes`、`/api/stocktakes/1` | 200 | — |
| `GET /api/reports`、`/api/dashboard` | 200 | — |
| `GET /api/health` | 404 | 新应用没有健康检查接口 |
| 写接口（仅验收测试用，见第 7 节） | 200 | `POST /api/products`、`POST /api/stocktakes`、`POST /api/inbound`、`PATCH /api/stocktakes/4`、`POST /api/stocktakes/4/complete`、`POST /api/outbound` ×2（第 2 次被业务校验拒绝，仍是 200 + ok:false） |

说明：新应用每个接口处理前都会调用 `ensureReady()`（建表、空库时播种），我只发过 GET，商品表非空所以不会重新播种，结束时的行数证明没有被重置。

## 6. 缺陷清单

### 6.1 旧应用

1. **（严重）四个表单页的脚本 500，新建流程全部不可用。** 时间线：`next start` 进程 2026-11-21 12:52:14 启动；`.next/BUILD_ID` 等 12:55:04 被重写（又一次构建）。运行中的服务 HTML 里引用 `1fi68alcnu60v.js`、`2-jotapbcbp4r.js`、`26vrn4rmaqnu4.js`，磁盘上 `static/chunks/` 里没有这三个文件（磁盘上的新构建用的是 `185eevy8dyh49.js`、`01pqk2lvp2d4_.js` 等新名字）。浏览器实测：`/products/new`、`/outbound/new` 显示「页面加载失败」；`/inbound/new`、`/products/1/edit` 表单渲染出来但按钮无反应，多等一会儿也会变成错误页。控制台错误：`Failed to load resource: 500` ×1～2、`ChunkLoadError: Failed to load chunk …`、`[页面异常] ChunkLoadError`。影响：六-1 的新建/编辑、六-2 新建入库单、六-3 新建出库单。重启服务后大概率能恢复（磁盘上的构建是完整的），但我按规则没有重启，**这一点未验证**。
2. （轻微）首页「近 14 天趋势」柱状图被 11-21 的 224 件出库拉高了刻度，其余日期的柱子几乎看不见。
3. （轻微）出库/入库没有草稿状态，新建即确认；没有 SKU 编码字段、没有库存状态筛选（有分类筛选和搜索）。这些文档没要求，仅作记录。

### 6.2 新应用

1. **（严重，实测复现）完成盘点时不重算账面数，盘点期间的出入库会被重复调账。** `src/server/stocktakes.ts` 的 `completeStocktake` 用 `actual_qty - book_qty` 算差异，`book_qty` 是发起盘点时的快照，完成时不刷新。实测步骤：发起「热水器」盘点（测试商品账面 0）→ 入库 2 件（库存 0→2）→ 实盘数按实物填 2 → 完成盘点 → 系统记「盘盈 +2」并再加 2，库存变 4；差异表里显示账面 0 / 实盘 2 / 差异 +2 / 盘盈。正确做法应当是账面 2 / 实盘 2 / 一致（旧应用 `src/lib/services/stocktake.ts` 在确认时重新取当前库存做账面数）。
2. （轻微）演示数据时间瑕疵：7 条今天记录的时间戳晚于真实时钟；2 个 SKU 在 8 月 29 日先出库后入库（见第 3 节）。
3. （轻微）确认入库时无条件把「最近进价」改成这张单的单价（`src/server/inbound.ts`：`last_in_price = $3`），如果补录一张日期更早的入库单并确认，会把更新的进价盖掉；旧应用会保留日期更新的那个价格。源码判断，未实测。
4. （轻微）「低库存」口径是「库存 ≤ 安全线」，文档写的是「低于」；库存正好等于安全线时也会被预警和标红。
5. （轻微）接口对不存在的资源返回 HTTP 200（内容 ok:false），不是 404；没有 `/api/health`；没有网站图标（`/favicon.ico` 404，浏览器控制台会记一条）。
6. （记录）出库单价由前端传入，默认填「最近进价 × 1.1」，可手改；出库金额、TOP10 出库金额都按这个价算。文档没规定出库单价口径，不算错，但和「库存金额按最近进价」不是一个口径。
7. （记录）当前以开发模式（`next dev`）运行，页面左下角有 Next.js 开发标记；`package.json` 里 `start` 脚本也是 `next dev`。`.next/BUILD_ID` 是今天 00:41 写的，看起来生成时跑过一次生产构建（我没有再跑构建，构建是否通过未能确认）。

两应用共同点（环境层面，不计入缺陷）：页面里的时间按容器时区（UTC）显示，比北京时间慢 8 小时。

## 7. 在新应用上做过的写操作（全部带「验收测试」字样）

目的：验证「商品建档 → 入库 → 出库 → 盘点」的写流程，同时验证盘点期间有入库时的账面数处理。全部通过浏览器界面操作（不是直接调接口），每步都查了库。

| 步骤 | 操作 | 产生的记录 | 库存变化（商品 56） |
| --- | --- | --- | --- |
| 1 | `/products` 点「新增商品」填表保存 | 商品 id 56：编码 `YS-TEST-001`，名称「验收测试-即热式电热水器」，分类 热水器，型号 YS-TEST-001，单位 台，安全线 0，参考单价 100 | 新建，库存 0 |
| 2 | `/stocktakes/new` 选「热水器」、盘点人「验收测试」、备注「验收测试：盘点发起后再做一笔入库，检查账面数是否按完成时点重算」 | 盘点单 id 4 `PD20261123-04`，7 条明细（账面 45/2/7/34/13/9/0） | 无 |
| 3 | `/inbound/new` 供应商「验收测试供应商」、备注「验收测试：盘点期间入库」、商品 56 × 2 @ 100，点「确认入库」 | 入库单 id 73 `RK20261123-02`（已确认，2 件 ¥200.00）；明细 1 行；流水 id 332（+2） | 0 → 2 |
| 4 | `/stocktakes/4` 逐行录入实盘数（6 个原有商品按账面数、测试商品填 2），点「完成盘点」 | 明细实盘数写入；盘点单状态 completed；流水 id 333（adjust +2，即缺陷造成的多记） | 2 → **4**（应为 2） |
| 5 | `/outbound/new` 客户「验收测试客户」、商品 56 × 3（为把库存拉回 1 件），点「确认出库」 | 出库单 id 82 `CK20261123-04`（已确认，3 件 ¥330.00，单价用了表单默认的 110）；明细 1 行；流水 id 334（−3） | 4 → 1 |
| 6 | `/outbound/new` 客户「验收测试客户」、商品 56 × 5，点「确认出库」 | **被拒绝，没有生成任何记录** | 不变（1） |

写完后的状态：products 56、stock_in_orders 73、stock_in_items 182、stock_out_orders 82、stock_out_items 156、stock_movements 334、stocktakes 4、stocktake_items 39。55 个原有 SKU 的库存全部和测试前一样（逐个比对 55/55 相同）；库存口径一致性复查仍然 0 处不一致。副作用：新应用首页/报表的「本月入库」从 1,804 变 1,806 件（22→23 张单）、「本月出库」从 387 变 390 件（21→22 张单）、库存金额 ¥1,587,346.75 → ¥1,587,446.75、SKU 55 → 56；缺货预警仍是 7 条（测试商品安全线为 0，不会进预警）。这些测试记录没有删除入口（新应用没有删除商品/单据的功能），会一直留在演示数据里，可以靠「验收测试」字样和编码 `YS-TEST-001` 识别。

## 8. 截图清单（1366×900，`-full` 后缀是整页截图）

新应用 `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-new2-q3/`（共 53 张 PNG，另有 3 个探测结果 JSON）：

- 首页：`new-00-home.png`、`new-00-home-full.png`、`new-i-home-after-test.png`（验收测试后）
- 商品：`new-01-products.png`、`new-01-products-full.png`、`new-i-product-dialog.png`（新增商品面板）、`new-i-product-edit.png`（编辑面板）、`new-i-products-search.png`、`new-i-products-status-out.png`、`new-w-1-product-created.png`
- 入库：`new-02-inbound.png`、`new-02-inbound-full.png`、`new-07-inbound-new.png`、`new-08-inbound-1.png`、`new-09-inbound-71.png`（草稿）、`new-w-3a-inbound-form.png`、`new-w-3b-inbound-detail.png`（测试入库单 73）
- 出库：`new-03-outbound.png`、`new-03-outbound-full.png`、`new-10-outbound-new.png`、`new-11-outbound-1.png`、`new-12-outbound-80.png`（草稿）、`new-i-outbound-shortage.png`（行内不足提示）、`new-w-5a-outbound-form.png`、`new-w-5b-outbound-detail.png`（测试出库单 82）、`new-w-6-outbound-shortage-blocked.png`（库存不足被拦）
- 库存：`new-04-inventory.png`、`new-04-inventory-full.png`、`new-zoom-inv-top.png`、`new-zoom-inv-rows.png`（2 倍放大）、`new-i-inventory-out.png`、`new-i-inventory-low.png`
- 盘点：`new-05-stocktakes.png`、`new-13-stocktakes-new.png`、`new-14-stocktakes-1.png`、`new-15-stocktakes-2.png`、`new-16-stocktakes-3.png`（进行中录入页，另有对应 `-full`）、`new-w-2-stocktake-open.png`、`new-w-4a-stocktake-entered.png`、`new-w-4c-stocktake-completed.png`（缺陷证据）
- 报表：`new-06-reports.png`、`new-06-reports-full.png`

旧应用 `C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-old2-q3/`（共 40 张 PNG，另有 2 个探测结果 JSON）：

- 首页：`old-00-home.png`、`old-00-home-full.png`
- 商品：`old-01-products.png`、`old-01-products-full.png`、`old-07-products-new.png`（页面加载失败）、`old-08-products-1-edit.png`、`old-zoom-pn.png`（错误页 2 倍放大）、`old-i-product-edit.png`、`old-i-products-search.png`、`old-i-products-category.png`
- 入库：`old-02-inbound.png`、`old-02-inbound-full.png`、`old-09-inbound-new.png`、`old-i-inbound-new.png`（页面加载失败）、`old-10-inbound-1.png`
- 出库：`old-03-outbound.png`、`old-03-outbound-full.png`、`old-11-outbound-new.png`（页面加载失败）、`old-12-outbound-1.png`
- 库存：`old-04-inventory.png`、`old-04-inventory-full.png`、`old-zoom-inv-top.png`、`old-i-inventory-out.png`
- 盘点：`old-05-stocktakes.png`、`old-13-stocktakes-1.png`、`old-14-stocktakes-2.png`、`old-14-stocktakes-2-full.png`、`old-15-stocktakes-1-print.png`、`old-i-stocktake2-typed.png`（输入实盘数后的实时差异，未保存）
- 报表：`old-06-reports.png`、`old-06-reports-full.png`

截图目视检查（用图像识别工具逐张看重点页，文字以页面实际文本核对）：两应用的首页、商品、入库、出库、库存、盘点、报表列表页布局完整，没有空白页、错误遮罩、错位或截断；旧应用的问题页是 `/products/new`、`/inbound/new`、`/outbound/new` 的「页面加载失败」。

## 9. 未能确认的事项

1. 旧应用的写接口（新建商品、入库、出库、发起/确认盘点）是否正常工作：按规则没有发写请求，只核对了源码和种子数据的流水一致性。
2. 旧应用重启后表单页是否恢复：按规则没有重启服务，只从磁盘上的构建文件推断「大概率能恢复」。
3. 新应用「保存为草稿 → 再确认」的两步流程（入库、出库）：只在种子数据里看到草稿单和确认按钮，没有实际点确认（会改变种子数据的库存）。
4. 新应用的商品编辑保存：只打开了预填表单，没有点保存。
