package com.semgravio.aiplatform.base.agentscope;

/**
 * 工作消息头部标题（#118 呈现改版④）：run-start 扩载的用户语言标题。底座不解释
 * （同 {@link AgentCommand#agentKey()}——业务侧值，底座透传进 run-start 载荷的
 * {@code slice} 字段）。
 *
 * <p>{@code title} 恒为工作消息头部主文案；切片序号与进度（{@code index}/{@code
 * total}）已随切片机器退役（#602/#608，SSE 事件清单正本：{@code slice} ＝
 * {@code { title } }）。无标题语境（主智能体对话轮 / 一次性调用）整对象为 null，
 * run-start 不携带 {@code slice} 字段。</p>
 *
 * @param title 用户语言标题（阶段 0 = 「系统初始化」，更新 run = 「系统更新」）
 */
public record RunHeading(String title) {

    /** 工作消息头部标题（唯一构造面）。 */
    public static RunHeading titled(String title) {
        return new RunHeading(title);
    }
}
