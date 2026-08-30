## Parent

#36 · v1 平台完整落地（spec）

## What to build

需求端落地页从 redirect 变为一句话 hero（spec 0002 §3.1）：渐变光晕卡式输入 + 项目名（可选）+ 引擎下拉（`GET /api/agent-engines`）+ 类型模板下拉（1 官网 / 2 电商）+ 圆角发送钮；下方「最近的项目」4 条 +「查看全部」→ 项目列表页。一句话提交 → `POST /api/projects` → 直进该项目工作台对话模式（响应 runId 挂 agent 流）。dev 场景项目列表页同形态建项目入口。

## Acceptance criteria

- [ ] 根页渲染一句话 hero，提交后创建项目并跳转工作台
- [ ] 引擎 / 类型模板下拉数据来自真实端点，缺省值可用
- [ ] 最近项目 4 条 +「查看全部」跳列表页
- [ ] CreateProjectCommand 载荷构造（name / type / engine / requirement）有纯逻辑单测
- [ ] 建项目后直进工作台对话模式（BA 首轮问答卡 / 门卡就位，若对话模式票已就绪）

## Blocked by

- #37 · 工作台三栏壳（直进工作台需要壳存在）
