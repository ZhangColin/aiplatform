## Parent

#36 · v1 平台完整落地（spec）

## What to build

HITL 全家接线（spec 0001 §5）：`GET agent/waits`（跨会话 PENDING）为数据源 → 问答卡（一卡多题、单选 / 多选 / 可选自定义、选项带 description）/ 审批卡（工具 + 入参 pre + 允许 / 拒绝 + 30min 过期 +「终止任务」逃生口）/ 转任务（deferred）→ `POST agent/waits/{waitId}/settle` 三型载荷。卡渲染在对话模式嵌流底 + 待处理队列。刷新 / 切项目后等待点仍可找回。settle 三型载荷（来自 swagger，decision-rich）：

```ts
type ProjectWaitSettleCommand =
  | { type: "answer"; answers: string[][] }                          // 问答：按题序，每项=选中标签列表
  | { type: "permission"; approve: boolean }                         // 权限：true 批准(once) / false 拒绝
  | { type: "deferred"; task: { title: string; content?: string; assigneeAccountId: number } }; // 转任务
```

## Acceptance criteria

- [ ] 智能体提问渲染问答卡，作答后 settle answer，agent 续跑
- [ ] 智能体申请权限渲染审批卡，允许 / 拒绝后 settle permission
- [ ] 转任务 deferred 载荷含 task{title, content?, assigneeAccountId}
- [ ] 终止任务逃生口 UI 就位（动作待后端终止端点，发 issue 跟进）
- [ ] settle 三型载荷构造有纯逻辑单测

## Blocked by

- #37 · 工作台三栏壳（卡片容器在壳内）
- #40 · 下任务 + 对话模式消息流（卡片嵌流底的对话模式需就位）
