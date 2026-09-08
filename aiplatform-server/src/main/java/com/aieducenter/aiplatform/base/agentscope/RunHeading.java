package com.aieducenter.aiplatform.base.agentscope;

/**
 * 工作消息头部标题（#118 呈现改版④）：run-start 扩载的用户语言标题 + 生成轨道
 * 切片进度。底座不解释（同 {@link AgentCommand#agentKey()}——业务侧值，底座透传
 * 进 run-start 载荷的 {@code slice} 字段）。
 *
 * <p>{@code title} 恒为工作消息头部主文案；{@code index}/{@code total} 仅生成轨道
 * 切片携带（1-based 序号与总数），阶段 0 与更新 run 缺省（null）——头部只出标题、
 * 无「（n/N）」进度。无标题语境（主智能体对话轮 / 一次性调用）整对象为 null，
 * run-start 不携带 {@code slice} 字段。</p>
 *
 * @param title 用户语言标题（生成轨道 = 切片标题，阶段 0 = 「系统初始化」，更新 run = 「系统更新」）
 * @param index 生成轨道切片序（1-based；阶段 0 与更新 run 为 null）
 * @param total 生成轨道切片总数（同 index 缺省口径）
 */
public record RunHeading(String title, Integer index, Integer total) {

    /** 无进度标题（阶段 0 / 更新 run）：只带标题。 */
    public static RunHeading titled(String title) {
        return new RunHeading(title, null, null);
    }

    /** 生成轨道切片标题 + 进度（index 1-based）。 */
    public static RunHeading slice(String title, int index, int total) {
        return new RunHeading(title, index, total);
    }
}
