package com.semgravio.aiplatform.business.project.domain.decisionhint;

import java.util.List;

/**
 * 轨迹快照（#437 判定缝输入）：执行会话当前轮的消息列经中间件映射后的框架中立
 * 形。堆叠上限的「已注入键」也承载在本快照（从合成提示消息元数据回读）——
 * 分类器无状态，栈语义全部由消息列重放得出，压缩/中断后不依赖进程内状态。
 *
 * @param iterations       迭代序列（顺序保持；首个元素为当前会话最早迭代）
 * @param injectedHintKeys 本会话（自最近一条真实用户消息起）已注入的微指令键，按注入序
 */
public record TrajectorySnapshot(List<Iteration> iterations, List<String> injectedHintKeys) {
}
