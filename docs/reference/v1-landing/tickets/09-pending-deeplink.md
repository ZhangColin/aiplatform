## Parent

#36 · v1 平台完整落地（spec）

## What to build

待处理模式从占位到决策队列（spec 0001 §4.3）：HITL 等待 + 门拍板聚合为决策队列大卡纵排（类型 + 来源 + 时间 + 计数徽章），处理后收为「已处理」一行，空态「一切自动运行中」。待办中心各类型点击深链（spec 0003 §3）：AGENT_WAIT → 工作台对话模式定位等待点（消费 waitId）、GATE_PENDING → 工作台门卡、TASK_SUBMITTED / RETEST_READY → 工作台任务面板、NEW_TASK / TASK_REJECTED → 任务详情。

## Acceptance criteria

- [ ] 待处理模式聚合 HITL 等待 + 门拍板，徽章计数联动
- [ ] 处理后收为已处理一行，空态正确
- [ ] 待办中心各类型点击直达对应面板 / 页面，AGENT_WAIT 深链消费 waitId 定位

## Blocked by

- #45 · HITL 接线（待处理队列需问答卡 / 审批卡内容）
- #43 · 门拍板接线（待处理队列需门卡内容）
