## Parent

#36 · v1 平台完整落地（spec）

## What to build

直播模式从最小占位（知识命中 + 一行状态）升级为全量舞台时间线（spec 0001 §4.2）：text 流式气泡 / reasoning 折叠 / patch diff 行级块（+/- 染色 + 摘要行）/ tool 卡（icon + 名称 + 入参截断 + spinner→✓）/ step 边界 / role 段 / knowledge 横幅（双卡网格，保留现状）/ error / finish。纯读改动——分段已在 streams store 全量写入（桥为唯一写入方），只差渲染。

## Acceptance criteria

- [ ] 直播 tab 渲染全部平台事件 + 引擎透传分段，非仅知识命中
- [ ] tool / patch / reasoning / text / step / error / finish 各有独立渲染块
- [ ] 未知透传类型（passthrough）兜底呈现
- [ ] 各分段渲染组件有 renderToStaticMarkup 断言

## Blocked by

- #37 · 工作台三栏壳（直播模式是 Agent 区的一副面孔，需壳就位）
