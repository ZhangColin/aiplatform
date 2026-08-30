## Parent

#36 · v1 平台完整落地（spec）

## What to build

死代码接线——GateCard 挂到对话模式流底 + 右栏阶段面板（spec 0001 §5）+ 需求端顾问对话流底（spec 0002 §4）；useApproveStage / useRejectStage 已就绪（`POST stage/approve` / `stage/reject`）。验收门专用动作界面（验收通过 / 驳回反馈）。驳回后 StageRejectionBanner 展示理由（store 已由桥写入 stage-changed rejected 载荷，只差消费）。locked 态展示解锁条件（gate.ready === false，如「测试循环还有 N 条未关闭 Bug」）。

## Acceptance criteria

- [ ] 门就绪时工作台出现 GateCard，通过 / 驳回必填意见回传智能体
- [ ] 验收门有专门验收动作界面（需求端口径：验收）
- [ ] 驳回后显示驳回理由（StageRejectionBanner 消费 store 载荷）
- [ ] locked 态展示解锁条件
- [ ] 需求端文案走禁词口径（不出现「门 / 阶段」）

## Blocked by

- #37 · 工作台三栏壳（门卡渲染在右栏 + 流底）
- #40 · 下任务 + 对话模式消息流（门卡嵌流底需对话模式）
