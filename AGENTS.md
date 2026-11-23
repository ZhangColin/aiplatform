## Agent skills

### Issue tracker

Issue 跟踪在 GitHub Issues（`ZhangColin/aiplatform`），通过 `gh` CLI 操作。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context：根级 `CONTEXT.md` + `docs/adr/` 为权威（子项目旧词汇表已删除，历史见 git）。见 `docs/agents/domain.md`。

### Skill audit guide

技能安装/更新/指派的审核口径与候选经验人审三问（自产草稿审核已出局，ADR-0058）。见 `docs/agents/skill-audit-guide.md`。

### 从零重设施工交接

从零重设（总索引 #573）决议已定案（ADR-0054–0058），施工按七阶段顺序开常规 issue：内核接线→旧栈清场→过程呈现→对话面·迭代·版本→底座修复→飞轮与技能清场→实测验证。见 `docs/agents/从零重设施工交接清单.md`。

### Demo 阶段模型档

Demo 阶段（首 Demo ＋ 迭代，run 执行体承载）模型配置的初档与改配置 SOP（运营配置面改配置即生效、不发版、进行中 run 不定格）。见 `docs/agents/demo-stage-model-config.md`。

### Live 车道防腐

触碰 workspace/docker/探针集成点的票，收尾前必须本地跑相关 live 类并在票内评论留结果；①组 Windows argv 双引号平台性口径（防呆 os.name 条件化、命令一律单引号）。见 `docs/agents/live-test-lane.md`。

### 自造词对照表

读旧 issue／决议／ADR 时遇到的从零重设自造词（壳、缝、腿、过堂、喂料等）与白话对照。2026-11-22 起新文档不再使用自造词。见 `docs/agents/自造词对照表.md`。
