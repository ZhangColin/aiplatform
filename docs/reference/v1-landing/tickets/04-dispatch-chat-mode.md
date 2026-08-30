## Parent

#36 · v1 平台完整落地（spec）

## What to build

对话模式从占位到可用（spec 0001 §4.1）：底部下任务输入框（可选角色卡 1–6，缺省取阶段默认）→ `POST agent/task` → runId 挂 streams store（桥已写全量分段）→ 消息流自上而下渲染（系统胶囊 / 用户右泡 / agent 段落 / 工具 chip / 思考折叠 / patch 摘要 / 知识命中卡）+ 运行条（任务号 + 计时 + 终止占位）。HITL 卡与门卡的嵌流底容器位预留（内容由 HITL / 门拍板票落）。

## Acceptance criteria

- [ ] 下任务输入 → agent 开始干活 → 消息流实时出现流式内容
- [ ] 工具 chip（进行中 → 已执行）、思考折叠、patch 摘要、知识命中卡正确渲染
- [ ] 运行条显示任务号 + 计时，终止按钮占位
- [ ] ProjectAgentTaskCommand 载荷构造（prompt + role 缺省语义）有纯逻辑单测
- [ ] 消息流从 streams store 读取，切 tab 不丢状态

## Blocked by

- #37 · 工作台三栏壳（对话模式是 Agent 区的一副面孔，需壳就位）
