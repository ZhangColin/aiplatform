## Parent

#36 · v1 平台完整落地（spec）

## What to build

dev 项目详情 Bug 面板补齐测试循环最后两个 mutation：逐条关闭 Bug（`POST bugs/{bugId}/close`）+「派发修复」自动派开发智能体逐条修复（`POST bugs/dispatch-fixes`）。关闭 / 派发后 Bug 三态推进（待修复 → 已修复 → 复测通过），测试循环闭环到开发完成确认门解锁（gate.ready 由后端驱动）。此票独立于工作台壳，可直接开工。

## Acceptance criteria

- [ ] Bug 面板每条 Bug 可关闭，关闭后状态徽章更新
- [ ]「派发修复」按钮触发后，待修复 Bug 进入修复流程（agent 流可见）
- [ ] 派发修复后开发完成门解锁
- [ ] close / dispatch-fixes 载荷构造有纯逻辑单测

## Blocked by

None — can start immediately.
