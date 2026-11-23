# 第 4 轮验收报告 · 问题 3（小型仓库出入库管理系统）— 新应用 v0r2d-q3

- 验收对象：新应用，容器 `v0r2d-q3`，http://localhost:18143（容器内 8081），Next.js 16.3.1 + PostgreSQL。
- 旧应用 `v0-run3`（http://localhost:18093）仅在需要做新旧对比时做了只读抽查，不逐项过。
- 验收时间：2026-11-23。PRD：`C:/Users/zhang/AppData/Local/Temp/v0-firstrun/prd-run3-warehouse.md`。
- 说明：本环境下 Read 工具读图返回的是 CDN 链接，我改用「Read 上传 + 视觉模型逐图描述」+ Playwright DOM 文本转储两种方式看页面，截图文件本身已存 `shots-new4-q3/`。

## 一、功能清单逐项判定（六、功能清单 1–6）

| 条目 | 新应用 | 证据 |
| --- | --- | --- |
| 1. 商品管理（名称/分类/型号/单位/安全库存线，列表＋搜索＋分类筛选） | 完成 | `GET /products` 200，48 个 SKU，列：商品编码、名称/型号、分类、单位、安全库存线、当前库存、状态、最近进价、编辑；筛选实测：`/api/products?search=KT-2007` 命中 1（奥克斯 壁挂式空调 1.5匹）、`?category=洗衣机` 命中 10 且全为洗衣机。建档实测：`POST /api/products`（VT-9001，201），`DELETE /api/products/50`（200，库里已无该行）。`/products/new`、`/products/[id]/edit` 均 200。期初库存走 `initialStock` 字段并写「期初建账」台账（源码 `products.ts` 144–157 行） |
| 2. 入库单（供应商、多行明细、数量、单价，确认后库存增加，列表与详情可查） | 完成 | 实测 `POST /api/inbound-orders` confirm:true → 201，单号 RK202611231000；库里 product 35 库存 114→116，stock_movements 新增 (+2, balance_after=116)。表单支持多行（「添加一行」、合计数量/金额、同商品去重），可「仅保存草稿」或「保存并确认入库」；种子含 1 张草稿。`/inbound` 列表 200（共 68 张，含待确认/已确认金额汇总），`/inbound/66` 详情 200 |
| 3. 出库单（客户、多行明细，保存逐行校验库存并提示不足，确认后扣减，列表可查） | 完成 | 不足实测：`POST /api/outbound-orders`（KT-2007 要 5、库存 0）→ HTTP 422，`error.details.items=[{productId:19, sku:KT-2007, requested:5, available:0}]`，逐行差异，且未落任何单据。正常实测：CK202611231000 → 201，库存 116→113，movement (-3, balance_after=113)。UI 双保险：表单行内「库存不足（可用 N）」＋「可用库存」列（`order-form.tsx` 138/204/344 行），服务端确认时二次校验（`orders.ts` 510–524 行）。`/outbound` 列表 200（共 98 张） |
| 4. 库存看板（列表、商品/分类/状态筛选，低于安全线标红，首页缺货预警区） | 完成 | `GET /inventory` 200；筛选实测 `?status=low` 10 行、`keyword=空调&category=空调` 命中空调且状态正确。截图可见：5 个缺货行浅红底＋红「缺货」标签＋红字「需补 N」，低库存行浅黄底＋黄标签；列含补货建议。首页预警区：库存预警卡「共 15 个，其中缺货 5 个」，逐条列商品（洗衣机进水管 1.5m PJ-4007 0根 缺货 …）带「去补货」按钮 |
| 5. 盘点（按分类发起、逐项录实盘、账实差异表 盘盈/盘亏/一致） | 完成 | 实测闭环：`POST /api/stocktakes` scope=冰箱 → 12 项快照（账面合计 272）；`PATCH …/3` 录实盘（=账面）→ 200；`POST …/3/confirm` → 200 已确认、差异 0、库存与台账零变化。种子单 PD20261025001 详情页：盘盈项 2／盘亏项 2／账实一致 10／差异合计 -2，逐行「-3 盘亏（红）/ 0 一致（灰）/ +1 盘盈（绿）」；盘点中单 PD20261122001：已录入 5/12、行内输入框、「未录入的填账面数／保存实盘／确认调账」按钮。防重：同分类有进行中单会 409（源码），非法 scope 400（实测） |
| 6. 报表（本月入库总量、出库总量、TOP10 出库商品、库存金额四卡＋缺货清单） | 完成 | `GET /reports` 200：四卡＝2026年11月入库总量 310（14 张单 · ¥52.8 万）／出库总量 811（26 张单 · ¥102.4 万）／库存金额 ¥268.8 万（48 SKU · 1,899 件）／缺货 SKU 5 · 预警 15；TOP10 表 10 行（第 1 空调内机挂板 265）；「缺货 / 低库存清单」15 项含建议补货量；月份切换实测 `?month=2026-10` → 入 626／出 478。金额口径＝最近一次入库单价（见下） |

## 二、核心场景（三、可核部分）

| 场景 | 新应用 | 证据 |
| --- | --- | --- |
| 入库确认库存自动增加 | 完成 | 上表条目 2 实测（114→116） |
| 出库确认库存自动扣减 | 完成 | 上表条目 3 实测（116→113） |
| 库存不足该行提示 | 完成 | 422 逐行差异＋表单行内提示（上表条目 3） |
| 低库存/缺货行标红 | 完成 | 截图 inventory.png：缺货 5 行红底红标、低库存行黄底黄标 |
| 首页缺货预警区 | 完成 | home.png／DOM：预警卡列 15 项（5 缺货＋10 低库存）带去补货 |
| 盘点按分类＋差异表 盘盈/盘亏/一致 | 完成 | 上表条目 5 |
| 四报表卡＋缺货清单 | 完成 | 上表条目 6 |

## 三、八、验收与待确认

| 条目 | 新应用 | 证据 |
| --- | --- | --- |
| 6 项功能完整闭环（建档→入库→出库→盘点→报表） | 完成 | 五类写操作全部经 API 实测走通（见「写操作清单」），报表数字随后刷新（本月入库 308→310、出库 808→811、总量 1900→1899） |
| 库存口径一致（入库加、出库减、盘点调账） | 完成 | SQL 全量核对：48/48 商品 `products.stock = SUM(stock_movements.quantity)`（出库记负数、盘点记差异），0 笔不符（写测试前后各核一次均 0） |
| 演示数据 ≥40 SKU、≥100 出入库单分布近三个月 | 完成 | 见下「演示数据核对」 |
| 待确认：库存金额按最近一次入库单价 | 按此实现 | `last_price` 与该商品最后一条 inbound movement 的 unit_price 全量比对 0 笔不符；API stockValue 2,687,513.20 == SQL `sum(stock*last_price)`（2,687,513.20）；页面标注「按最近一次入库单价计价」 |

## 四、演示数据核对（对照文档数字）

| 指标 | 要求 | 实测（写测试前） | 证据 |
| --- | --- | --- | --- |
| SKU 数 | ≥40 | 48（冰箱 12 / 空调 12 / 洗衣机 10 / 安装配件 14） | `select count(*) from products` |
| 出入库单 | ≥100 | 164（入库 67＋出库 97；含各 1 张草稿） | 两表 count |
| 分布近三个月 | 是 | 2026-08-26 → 2026-11-23（约 89 天）；按月：入库 22/11/20/15，出库 7/29/35/27 | min/max(order_date)、按月 group |
| 中文真实商品名/型号 | 是 | 「海尔 对开门冰箱 BCD-501WGHFD14S9U1」「格力 壁挂式空调 1.5匹 KFR-35GW/NhGc1B」「制冷剂 R410A 10kg」等；供应商「格力电器浙北仓」「江苏小天鹅供应链」、客户「德信地产·君宸府」「临平新城家电超市」等 | products / 两单表 distinct |

## 五、库存一致性核查

- `products.stock` vs 台账有向和：48/48 一致，0 笔偏差（写测试前、后各查一次）。
- `balance_after` 链：374 条台账中发现 **6 条**（商品 5、21，均为种子数据）同日两笔的 `occurred_at` 时间顺序与余额衔接顺序相反（例：商品 5 于 10-29 的 CK…067 13:19 余 34、CK…066 16:54 余 35）。总额与期末余额不受影响，页面无按时间翻台账的视图，属种子数据小瑕疵。

## 六、探测过的路由/API

页面（全部 200，除 404 页）：`/`、`/products`、`/products/new`、`/products/1/edit`、`/inbound`、`/inbound/new`、`/inbound/1`、`/inbound/66`、`/outbound`、`/outbound/new`、`/outbound/1`、`/outbound/97`、`/inventory`、`/stocktakes`、`/stocktakes/new`、`/stocktakes/1`、`/stocktakes/2`、`/reports`；`/nonexistent` → 404（正常）。
API（全 200/201，除刻意触发的 400/422）：`/api/health`、`/api/dashboard`、`/api/inventory`（含 status/keyword/category 参数）、`/api/products`（含 search/category；POST/DELETE 实测）、`/api/inbound-orders`（含 status/search；POST 实测）、`/api/outbound-orders`（含 search；POST 实测）、`/api/stocktakes`（POST 实测）、`/api/stocktakes/2|3`（GET/PATCH 实测）、`/api/stocktakes/3/confirm`（POST 实测）、`/api/reports`（含 month 参数）。未调用 `/api/admin/seed?force=1`（会重写演示数据）。

## 七、缺陷与观察

1. **种子台账时序小瑕疵（轻微）**：上述 6 条同日 movement 的时间戳与余额链顺序相反。UI 不可见，不影响口径。
2. **容器里有两个僵尸 node 进程**（pid 178、3269 `[node] <defunct>`），与生成期 dev server 崩过一次、模型自行重启的经过相符；当前 `next dev`（pid 3962）运行正常，全部页面 200。
3. **无 HTTP 500、无浏览器控制台报错**：16 个页面截图过程中 console error 为空（`CONSOLE_ERRORS {}`）。
4. 应用无登录页（PRD 也不要求）；顶栏固定显示「仓管员 · 张伟」。
5. 我的写测试自身带来、非应用缺陷的数据痕迹见下节（其中入库单 68 供应商乱码是我宿主 curl 的 GBK 编码问题，应用收什么存什么；种子中文数据在库内均正常）。

## 八、我在新应用上做过的写操作（全部留痕）

1. `POST /api/outbound-orders`（库存不足腿，KT-2007×5）→ 422 拒绝，**未落任何数据**。
2. `POST /api/inbound-orders` confirm:true → 入库单 **68 RK202611231000**（1 行：PJ-4001×2 @¥10，¥20，已确认）；商品 35 库存 114→116、last_price 27.29→**10.00**（后续被下面出库不冲回）；movement id 373 (+2)。供应商名因我的客户端编码存成乱码。
3. `POST /api/outbound-orders` confirm:true → 出库单 **98 CK202611231000**（客户 verify-test，PJ-4001×3，已确认）；商品 35 库存 116→113；movement id 374 (-3)。
4. `POST /api/stocktakes`（冰箱）→ 盘点单 **3 PD202611231000**（12 项）；`PATCH` 录实盘=账面；`POST confirm` → 已确认、差异 0、**库存与台账零变化**。
5. `POST /api/products` → 商品 **50 VT-9001**；随后 `DELETE` 已删除（库内无残留，仅序列号前移）。

## 九、与旧应用的对比（只在我实际查过的点上）

| 对比点 | 旧应用 v0-run3（我实测） | 新应用 | 结论 |
| --- | --- | --- | --- |
| 演示单据量 | 106 张（53+53），2026-08-24→11-21 | 164 张（67+97） | 新应用更多 |
| SKU 数 | 51 | 48 | 都达标，旧略多 |
| 首页缺货预警 | 有（HTML 含缺货/预警/低库存词条与看板结构） | 有（逐条商品列表＋去补货） | 打平（新应用的预警区逐条列到商品粒度） |
| 盘点差异表 盘盈/盘亏/一致 | 有（/stocktakes/1、/2 均含三类标签） | 有（汇总卡＋逐行三色标签） | 打平 |
| 报表四要素 | 有（TOP10/库存金额/缺货 词条在） | 有（四卡＋TOP10＋15 项缺货清单＋月份切换） | 打平 |
| 库存存储方式 | products 无 stock 列，由 movements 现算 | stock 快照＋movements 台账，一致性实测 0 偏差 | 两种做法都自洽；新应用额外留 balance_after（有上述 6 条时序瑕疵） |
| 登录 | 无 | 无 | 打平 |

我抽查的点上没有发现新应用比旧应用差的地方。

## 十、截图清单（`C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/shots-new4-q3/`）

home.png、products.png、product-new.png、inbound.png、inbound-new.png、inbound-detail.png、outbound.png、outbound-new.png、outbound-detail.png、inventory.png、inventory-low.png、stocktakes.png、stocktake-new.png、stocktake-1.png、stocktake-2.png、reports.png（均 1366×900；另有 home-small.png 为顶部局部）。截图过程中未发现空白页、报错弹层、布局破损或乱码。
