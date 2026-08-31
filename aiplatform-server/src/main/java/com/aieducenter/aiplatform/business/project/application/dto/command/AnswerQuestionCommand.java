package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 问答卡作答命令（#19 需求环①）：把挂起轮续跑所需的全部事实回传——runId +
 * 待确认工具清单（wait-raised 帧 {@code data.toolCalls} 原样回传，恢复私货从
 * 项目侧事实重建、不信前端）+ 用户答复文本（单选 label / 多选 label 拼接 /
 * 自由输入，可与已勾选合并——拼接归前端）。
 *
 * <p>路径 {@code /questions/{qid}/answer} 的 qid = 挂起帧 {@code engineRef}
 * （引擎侧请求 id，续跑批复的锚）。</p>
 *
 * @param runId     挂起轮的运行标识
 * @param toolCalls 待确认工具清单（{id, name, input}，挂起帧原样）
 * @param answer    用户答复文本
 */
public record AnswerQuestionCommand(

        @NotBlank(message = "runId 不能为空")
        String runId,

        @NotEmpty(message = "待确认工具清单不能为空")
        List<ToolCall> toolCalls,

        @NotBlank(message = "答复内容不能为空")
        String answer
        ) {

    /**
     * 待确认工具最小面（挂起帧 {@code data.toolCalls} 元素形状原样回传）。
     */
    public record ToolCall(
            String id,
            String name,
            Map<String, Object> input
    ) {

        /** 回挂起帧元素形状（{id, name, input}，null 兜空串/空 map——续跑重建口径）。 */
        public Map<String, Object> toMap() {
            return Map.of(
                    "id", id != null ? id : "",
                    "name", name != null ? name : "",
                    "input", input != null ? input : Map.of());
        }
    }
}
