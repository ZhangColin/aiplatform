# 领域文档

工程类 skill 在探索代码库时应如何消费本仓库的领域文档。

## 探索前先读

- 根目录的 **`CONTEXT.md`**
- **`docs/adr/`** —— 读与你要改动区域相关的 ADR

这些文件若不存在，**静默继续**。不要指出缺失；不要建议预先创建。`/domain-modeling` skill 会在术语或决策真正落定时 lazily 创建它们。

## 本仓库专属说明

本仓库由两个原本独立的仓库（`aiplatform-server`、`aiplatform-web`）合并而来，合并原因正是并行开发导致业务理解漂移。领域按 **单一上下文** 处理：

- 业务术语（项目/期/门/任务/HITL……）前后端共享。在根级 `CONTEXT.md` 中只定义一次；只有真正单侧专属的术语才标注 前端侧 / 后端侧。
- `aiplatform-server/CONTEXT.md` 与 `aiplatform-web/CONTEXT.md` 为**历史遗留，仅供参考**。任何冲突以根级为准。
- `aiplatform-server/docs/adr/` 与 `aiplatform-web/docs/adr/` 保留作历史可读。**新 ADR 一律写入根级 `docs/adr/`。**

## 使用词汇表的语言

当你的产出提到领域概念（issue 标题、重构提案、假设、测试名），使用 `CONTEXT.md` 定义的术语。不要漂移到词汇表明确回避的同义词。

若所需概念还不在词汇表里，这是个信号——要么你在发明项目不用的语言（重新考虑），要么存在真实缺口（记下来交给 `/domain-modeling`）。

## 标记 ADR 冲突

若你的产出与现有 ADR 矛盾，显式指出而不是静默覆盖：

> _与 ADR-0007（event-sourced orders）矛盾——但值得重开，因为……_
