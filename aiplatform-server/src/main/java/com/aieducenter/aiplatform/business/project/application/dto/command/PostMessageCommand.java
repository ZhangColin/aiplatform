package com.aieducenter.aiplatform.business.project.application.dto.command;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 对话区发言命令（#19 需求环①；#97 起携带圈注附件、#286 扩图片物料、#291 扩
 * 设计物作用域）：content 即用户在对话区输入的这句话——平台三分类派发（意见 /
 * 咨询 / 兜底），意见与咨询都由主智能体以续同一 {@code main-{projectId}} 会话
 * 消化（催促收敛、PRD 修订意见也都从这进）。消息附件（预览上指认的圈注位置、
 * 上传的图片物料）随发言同句发送——附件是对话输入的增强不是替代。
 *
 * <p><b>设计物作用域（#291 跨件回溯的会话路由）</b>：{@code designItem} 携
 * 目标设计物件序时，发言直达该件设计会话改稿（不经三分类与主智能体轮——作用域
 * 即「这是设计意见」的显式声明；设计轨在途即排队、当前稿代收口后受理）。画布
 * 点选稿卡后的随话发送即本形态（点哪改哪，画布侧接线归 #294）；对用户隐式。</p>
 *
 * <p><b>发散度（#294 三档 chip）</b>：{@code divergence}＝微调 / 探索 / 大胆
 * （{@code REFINE/EXPLORE/REIMAGINE}，经对话或画布 chip 调——同一语义通道），
 * 只随作用域发言生效：改稿 prompt 携档位引导（对「新代相对上一代走多远」的
 * 幅度约定），非作用域发言忽略（无「相对上一代」参照）。</p>
 *
 * @param content     用户发言正文（非空；上限与需求描述同源 5000）
 * @param attachments 消息附件（可空——纯文字发言无附件；圈注与图片物料可叠加）
 * @param designItem  设计物作用域（可空——目标设计物件序 1..N；空＝常规三分类）
 * @param divergence  改稿发散度（可空——REFINE/EXPLORE/REIMAGINE；空＝不带档位
 *                    引导，仅作用域形态消费）
 */
public record PostMessageCommand(

        @NotBlank(message = "发言内容不能为空")
        @Size(max = 5000, message = "发言内容长度不能超过5000")
        String content,

        @Size(max = 20, message = "附件数量不能超过20")
        @Valid List<MessageAttachment> attachments,

        @Positive(message = "设计物作用域必须是正整数件序")
        Integer designItem,

        @Pattern(regexp = "REFINE|EXPLORE|REIMAGINE",
                message = "发散度必须是 REFINE、EXPLORE 或 REIMAGINE")
        String divergence
) {
}
