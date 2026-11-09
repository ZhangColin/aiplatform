package com.semgravio.aiplatform.business.project.domain.decisionhint;

/**
 * 单次工具调用结果（轨迹快照成员，#437）：判定缝的框架中立输入——中间件从
 * agentscope 消息列映射，分类器只认本记录。
 *
 * @param toolName    工具注册名
 * @param failed      是否失败（ToolResultState ERROR/INTERRUPTED/DENIED 归 true）
 * @param outputText  输出文本（失败＝错误文本——签名取首行归一化；成功＝结果文本
 *                    ——反 mock 信号扫描面之一）
 * @param resultChars 结果输出字符数（成功结果体量——上下文经济性信号源）
 */
public record ToolCallOutcome(String toolName, boolean failed, String outputText, int resultChars) {
}
