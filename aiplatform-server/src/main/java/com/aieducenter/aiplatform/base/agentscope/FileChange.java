package com.aieducenter.aiplatform.base.agentscope;

/**
 * 一次成功写动作的文件变更事实（#88 收口扩载的观察面）：path 为工作区锚定形
 * （工具入参原样，如 {@code /src/App.jsx}）；added / removed 为行数（write =
 * 新文件行数、edit = 新旧串行数——活动量口径，真 diff 归版本层）。
 */
public record FileChange(String path, int added, int removed) {
}
