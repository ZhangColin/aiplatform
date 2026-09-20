package com.aieducenter.aiplatform.base.agentscope;

import java.util.List;
import java.util.Map;

/**
 * 挂起事实（converse / resume 的软终点面）：流以 RequireUserConfirmEvent 终止、
 * run 未终态——engineRef 为续跑批复锚；ask_user 提问是唯一挂起源（编码工具面
 * 无自检 ASK，#219 透明面化后破坏性命令直通），作答走问答作答通道（意见环）；
 * {@code toolCalls} 为待答复工具最小面（{id, name, input}，同挂起事件
 * data.toolCalls——重建 ConfirmResult 的入参形状）。
 */
public record AgentSuspension(String engineRef, List<Map<String, Object>> toolCalls) {
}
