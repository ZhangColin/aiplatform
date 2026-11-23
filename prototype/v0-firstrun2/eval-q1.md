# 问题 1「团队工单与任务看板」：旧应用 vs 新应用 独立核验报告

- 核验时间：2026-11-23 00:30–00:50 UTC（北京时间 08:30–08:50）
- 需求文档：`C:/Users/zhang/AppData/Local/Temp/v0-firstrun/prd-run1-helpdesk.md`
- 旧应用：容器 `v0-run1`，宿主 http://localhost:18091（2026-11-21 生成，保持原样）
- 新应用：容器 `v0r2-q1`，宿主 http://localhost:18111（今日生成）
- 核验方式：只读为主——HTTP 请求、数据库查询（psql）、浏览器截图＋DOM 检查；源码仅用来找接口、账号和确认未执行的写路径。旧应用零写入（只登录）；新应用执行了少量写入（见第五节，全部列出）。
- 两个容器内都是 PostgreSQL 15 ＋ `next dev`（开发模式）在跑；截图为 1366×900。

判定口径：**完成**＝文档要求点都能观察到；**部分**＝做了但不完整；**未做**＝没观察到。标「只读核验」的条目表示没有实际写数据，依据是页面按钮、源码逻辑和种子数据里的事件轨迹。

---

## 一、逐条对照表

| 条目 | 旧应用 v0-run1 | 新应用 v0r2-q1 | 证据 |
|---|---|---|---|
| 功能 1 工单管理：新建/编辑/详情，字段齐全，流转按钮随状态显隐 | 完成（只读核验） | 完成 | 旧：`/tickets/new` 有客户名称/联系人/联系电话/设备/故障描述/紧急程度/指派工程师；详情页按钮 待派单→[编辑工单,派单,挂起]、已派单→[+开始处理]、处理中→[编辑工单,挂起,提交结果]、挂起→[恢复处理]、待回访→[回访关闭,挂起]、已关闭→无。新：字段同样齐全（无单独「联系人姓名」，文档只要求联系人电话）；按钮 待派单→[派单,编辑信息]、已派单→[+开始处理]、处理中→[挂起,提交结果,编辑信息]、挂起→[恢复处理]、待回访→[回访关闭,回访不通过]、已关闭→[编辑信息]；POST /api/tickets 201、PATCH 200、非法流转 409、表单校验 422 均实测 |
| 功能 2 看板视图：五列、卡片含客户＋故障摘要＋紧急程度色标、点击进详情、横向滚动 | 完成 | 完成 | 旧：五列 待派单4/处理中6/挂起3/待回访5/已关闭16；看板容器 `overflow-x-auto`（scrollWidth 1376 > clientWidth 1326）；卡片是 `<a href="/tickets/{id}">`。新：五列 5/5/2/4/18；容器 `overflow-x-auto`（1572 > 1326）；卡片带 高/中/低 色标。两者在 1366 宽下第 5 列都被截掉一截（旧约 50px，新约 246px），需横向滚动 |
| 功能 3 工单列表：表格＋筛选（客户/工程师/紧急程度/日期范围/状态） | 完成 | 完成 | 旧：关键词、状态、紧急程度、工程师、起止日期，分页 10 条/页（共 34 条）；`/list?status=processing` 生效（6 行），其它参数不进 URL。新：客户名称、关键词、工程师、紧急程度、状态、起止日期，全部参数进 URL（`/list?assigneeId=3&status=processing` → 1 行；`customer=苏州&status=processing` → 2 行）；页面 35 行不分页 |
| 功能 4 统计面板：本月总量/状态分布条形/工程师排名/平均处理时长 四块卡片 | 完成 | 完成 | 旧 `/api/stats`：本月 28、分布 4/6/3/5/10、排名 7 人、平均 10.4 小时（样本 15）。新 `/api/stats`：本月 28（写入后 29）、分布 5/5/2/4/12（写入后 closed 13）、排名 7 人、平均 5.75 小时（写入后 5.31，样本 13）。页面均有四块卡片且数字与接口一致 |
| 功能 5 成员管理：姓名/电话/启用状态维护；派单下拉数据源 | 完成（只读核验） | 完成 | 旧：成员页 9 行，新增成员表单（姓名/联系电话/登录名/初始密码/角色）＋每行 编辑/停用/启用；`/tickets/new` 指派下拉只列 7 名启用工程师（排除停用的郑晓丹和两名主管）。新：成员页 10 行；POST /api/members 201、PATCH 200、停用后 activeOnly=1 不再返回、停用账号登录 403、工程师调用 403；`/tickets/new` 指派下拉 7 人（排除停用的周婷） |
| 功能 6 工作台首页：登录后默认落看板；顶部导航切换；右上角当前用户 | 完成 | 完成 | 旧：已登录 GET / → 307 /board；导航 看板/列表/统计/成员；右上 陈志远 SUPERVISOR。新：GET / 未登录 307 /login、已登录 307 /board；导航 看板/列表/统计/成员；右上 郑海涛 团队主管 |
| 场景 1 录单→进入待派单 | 完成（只读核验） | 完成 | 新：工程师账号 POST /api/tickets → 201，status=pending。旧：表单提交路径在源码 `createTicket`（未执行） |
| 场景 2 派单→处理中→待回访→已关闭 流转主线 | 完成（只读核验） | 完成 | 新实测 WO-20261123-72DU：assign→start→submit→close 全部 200，事件表 9 条。旧：16 条已关闭种子工单都有 create→assign→start→submit→revisit 完整事件轨迹（ticket_events 143 条） |
| 场景 2 任意节点可挂起 | 完成（只读核验） | **部分** | 旧：待派单、处理中、待回访三种详情页都有「挂起」按钮；源码 suspend 记录 suspended_from，resume 回到挂起前状态。新：只有处理中能挂起——对 pending 工单 POST suspend → 409「只有处理中工单可以挂起」，待派单/待回访详情页无挂起按钮 |
| 场景 3 工程师登录后看到自己的工单看板 | 完成 | 完成 | 旧：工程师 王海涛 登录，默认「我的工单」，进行中 3 单，共 6 张卡。新：工程师 张伟 登录，默认「我的」，共 5 条、超时催办 1 条，可切「全部」 |
| 场景 6 处理中超 48 小时标红 | 完成 | 完成 | 旧：库内超时工单 5 条（21/22/23/29/30），列表页顶部「其中 5 条处理中超 48 小时」，超时行整行红底＋「催办 · 55h」等角标（首页 10 行里 3 行红，按状态=处理中筛选时 6 行里 5 行红），顶栏「超时 5」，看板卡片红框。新：库内超时 2 条（1/2），列表 2 行红底＋「2.2 天 · 超 48h」，看板顶部红色「催办提醒」条列出客户和工程师 |
| 验收 完整闭环可走通 | 完成（只读核验） | 完成 | 新：录单→派单→开始处理→挂起→恢复→提交结果→回访不通过→再提交→回访关闭，全部 200，事件轨迹完整。旧：按只读约束未写入，依据同上 |
| 验收 看板五列状态正确 | 完成 | 完成 | 五列计数与 `select status,count(*)` 完全一致（旧 4/6/3/5/16；新 5/5/2/4/18，写入后已关闭 19） |
| 验收 筛选与统计数字口径一致 | 完成 | 完成 | 列表加「本月」日期筛选后各状态条数＝统计分布：旧 4/6/3/5/10，新 5/5/2/4/12；列表「工程师=3 且本月」＝排名首行（旧 王海涛 5，新 张伟 4） |
| 验收 演示数据≥20 条、覆盖各状态、真实可信 | 完成 | 完成 | 见第二节 |
| 验收 平均处理时长按状态变更时间戳累计 | 完成 | 完成 | 旧 10.4 小时＝本月创建且已提交结果的 15 单，处理中区间合计 156h÷15（SQL 独立复算一致）。新 5.75 小时＝本月关闭 12 单，处理中区间合计 69h÷12（一致；挂起期间不计） |

**计数**：旧应用 完成 16／部分 0／未做 0（其中 6 条为只读核验）。新应用 完成 15／部分 1／未做 0。

---

## 二、演示数据核对（直接查库）

| 项目 | 文档要求 | 旧应用 | 新应用 |
|---|---|---|---|
| 工单总数 | ≥20 | 34（本月 28 ＋ 上月 6） | 34（本月 28 ＋ 上月 6）；核验写入后 35 |
| 各状态覆盖 | 五种都要有 | 待派单 4／处理中 6／挂起 3／待回访 5／已关闭 16 | 待派单 5／处理中 5／挂起 2／待回访 4／已关闭 18 |
| 紧急程度 | 低/中/高 | 高 14／中 12／低 8 | 高 14／中 14／低 6 |
| 成员 | 约 6 工程师＋1-2 主管 | 9 人：主管 2、工程师 7（1 停用） | 10 人：主管 2、工程师 8（1 停用） |
| 流转事件 | — | 143 条，覆盖 create/assign/start/suspend/resume/submit/revisit | 135 条，覆盖 create/assign/start/suspend/submit/close |
| 中文姓名 | 要 | 客户联系人如 刘建军、陈晓、沈志强 | 成员如 郑海涛、张伟；客户为机构名（无联系人姓名字段） |
| 设备型号与故障描述真实感 | 要，不要 lorem ipsum | 如「西门子 S7-1200 PLC（CPU 1214C DC/DC/DC）」「上电后 RUN 指示灯不亮，扩展 I/O 模块全部失联」 | 如「华为 S5720-28X-SI 交换机」「3 号端口链路频繁闪断，重启交换机后恢复约 2 小时后复现」 |
| 超时工单（催办可见） | — | 5 条 | 2 条（种子写入时就已超 48 小时） |

两边数据都真实可信，无占位文本。差别：旧应用有「联系人姓名」字段，新应用只有联系电话（文档只要求电话）。

---

## 三、探测过的路由与接口

**旧应用 http://localhost:18091**

| 请求 | 结果 |
|---|---|
| GET / （未登录） | 200 登录页；（已登录）307 → /board |
| GET /login（已登录） | 307 → /board |
| GET /board /list /stats /members /tickets/new | 200 |
| GET /tickets/{1,17,19,22,32,33,34} | 200 |
| GET /tickets/999999 /tickets/abc /tickets/0 /nosuchpage | 404，深色中文「信号丢失 · 页面不存在」页 |
| GET /list?status=processing | 200，6 行 |
| POST /api/auth/login | 200；错密码 401；非法 JSON 400 |
| POST /api/auth/logout | 200，之后接口 401 |
| GET /api/health | 200 `{"ok":true,"database":"up","tickets":34,"members":9}` |
| GET /api/tickets（q/status/priority/assigneeId/from/to/page/pageSize） | 200，各筛选条数与 SQL 一致 |
| GET /api/tickets?assigneeId=abc / from=garbage | **503**，响应里带 SQL 原文 `invalid input syntax for type integer: "NaN"` |
| GET /api/tickets/{id} /abc /0 | 200 / 404 / 404 |
| GET /api/stats /api/members | 200 |
| GET /api/members/abc | 405 |
| GET /api/nosuchroute | 404 |
| GET /favicon.ico /icon.svg | 404 / 200 |

**新应用 http://localhost:18111**

| 请求 | 结果 |
|---|---|
| GET / （未登录） | 307 → /login；（已登录）307 → /board |
| GET /login（已登录） | 307 → /board |
| GET /board /list /stats /members /tickets/new | 200 |
| GET /tickets/{1,6,10,12,16,21,35} | 200 |
| GET /tickets/999999 /tickets/abc /tickets/0 | **200**，内容是 Next 默认白色英文 404 页 |
| GET /nosuchpage | 404（同样是默认英文页） |
| GET /list?status=…&customer=…&assigneeId=…&from=…&to=… | 200，参数全部生效 |
| POST /api/auth/login | 200；错密码 401；非法 JSON 422；停用账号 403 |
| POST /api/auth/logout | 200，之后 /api/me 401 |
| GET /api/me | 200 当前用户 |
| GET /api/health | **404**（没有健康检查接口） |
| GET /api/tickets（同上筛选） | 200，条数与 SQL 一致 |
| GET /api/tickets?assigneeId=abc / from=garbage | **503**「数据服务暂不可用」（不吐 SQL） |
| GET /api/tickets/abc /999999 | 400 / 404（JSON） |
| GET /api/stats /api/members /api/members?activeOnly=1 | 200 |
| POST /api/tickets、PATCH /api/tickets/35、POST /api/tickets/35/transition | 201/422、200、200/409（见第五节） |
| POST /api/members、PATCH /api/members/11 | 201/403/422、200 |
| GET /favicon.ico /icon.svg | 404 / 404（无站点图标） |

---

## 四、各应用观察到的缺陷

### 旧应用 v0-run1

1. **统计页「近 14 天录单趋势」柱状图不显示**：DOM 里 14 根柱子都在，但渲染高度全部为 0（百分比高度落在没有固定高度的父容器上），页面上只剩日期和悬停才出现的数字。这是四块卡片之外的附加卡片，不影响文档四项，但肉眼可见是坏的。
2. **筛选参数非法时返回 503 并泄露 SQL 报错原文**（`/api/tickets?assigneeId=abc`、`from=garbage`）。界面用的是下拉和日期控件，正常操作碰不到。
3. 1366 宽下看板第 5 列被截约 50px，需横向滚动（符合「横向滚动」要求，但第 5 列标题「已关闭」看不全）。
4. 成员页对工程师也显示「编辑/停用」按钮，接口也没有角色判断（文档说不建细粒度权限，算可接受，但和界面提示不一致）。
5. 工程师排名里列出了已停用的郑晓丹（0 单）。
6. 列表页 URL 只认 `status` 一个参数，其它筛选不能通过链接直达（文档未要求）。
7. 控制台：仅访问 404 页时一条资源 404 记录。未发现 HTTP 500。

### 新应用 v0r2-q1

1. **「任意节点可挂起」只做了处理中一个节点**：待派单、待回访不能挂起（接口 409，页面无按钮）。这是文档「三、核心场景 2」明确写的。
2. **找不到工单时返回 HTTP 200 ＋ Next 默认白色英文 404 页**（`/tickets/999999`、`/tickets/abc`、`/tickets/0`），带导航栏但正文是英文「This page could not be found.」。
3. **时间按服务器时区显示**：容器时区是 UTC，页面上的创建/派单时间比北京时间慢 8 小时（旧应用固定按北京时间）。部署在国内服务器时不会出现。
4. **列表筛选条的小问题**：刚改完一个下拉就立刻在「客户名称」里打字，导航完成时输入会被清空（复现 1 次，正常节奏下不出现）。
5. 已关闭工单仍可编辑（PATCH 200；旧应用会拒绝）。
6. 没有站点图标 → 首次打开页面控制台一条 `favicon.ico` 404 错误；没有 `/api/health`。
7. 1366 宽下看板第 5 列被截约 246px，基本只露一条边。
8. 列表页工程师筛选下拉里含「周婷（停用）」。
9. 筛选参数非法时 503「数据服务暂不可用」。
10. 控制台：除 favicon 404 外无错误。未发现 HTTP 500。

---

## 五、在新应用上执行的写操作（全部列出）

| 时间（UTC） | 操作 | 结果 | 留下的记录 |
|---|---|---|---|
| 00:41:19 | 工程师 张伟 POST /api/tickets | 201 | 工单 #35 `WO-20261123-72DU`，客户「验收测试客户（自动化核验，请忽略）」 |
| 00:41 | 主管 郑海涛 对 #35 依次 assign→start→suspend→resume→submit→reopen→submit→close | 全部 200；期间 5 次故意非法流转返回 409 | ticket_events #136–144 共 9 条；工单现为 已关闭 |
| 00:41 | 主管 PATCH /api/tickets/35 改故障描述/紧急程度/设备 | 200 | 字段已改 |
| 00:41 | 主管 POST /api/members | 201 | 成员 #11「验收测试工程师」账号 `evaltest01` |
| 00:41 | 主管 PATCH /api/members/11 改电话→停用 | 200 | 现为停用状态 |

副作用：新应用本月工单量 28→29、本月关闭 12→13、平均处理时长 5.75→5.31 小时；成员 10→11（新增的已停用）。旧应用零写入。

---

## 六、截图清单

目录：`C:/Users/zhang/AppData/Local/Temp/v0-firstrun2/`

旧应用 `shots-old-q1/`：01-login、02-anon-board-redirect、03-board、04-list、05-stats、06-members、07-detail-processing-overdue（/tickets/22）、08-detail-pending（/tickets/34）、09-ticket-new、10-detail-notfound、11-list-status-processing、12-board-fullpage、13-eng-board、14-eng-list、15-eng-members、16-eng-stats、17-stats-trend-card（趋势卡片单独截）、18-stats-scrolled（统计整页）；另有 diagnostics.json（每页控制台/DOM 诊断）。

新应用 `shots-new-q1/`：01-login、02-anon-board-redirect、03-board、04-list、05-stats、06-members、07-detail-processing-overdue（/tickets/1）、08-detail-pending（/tickets/12）、09-ticket-new、10-detail-notfound、11-list-status-processing、12-board-fullpage、13-eng-board、14-eng-list、15-eng-members、16-eng-stats；另有 diagnostics.json。

截图复核方式：DOM 诊断（行数、背景色、滚动尺寸、按钮文字）＋图像内容描述。两边均无空白页、无报错遮罩、无空表格；上述可见问题已列在第四节。
