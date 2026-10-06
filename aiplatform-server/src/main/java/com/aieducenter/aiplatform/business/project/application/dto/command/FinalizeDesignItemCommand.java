package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 定稿命令（#291 定稿机制）：候选中锁定的那一稿——工作区锚定形路径（收尾卡
 * drafts[].path / 画布稿卡同形，如 {@code /design/home-2.html}）。
 *
 * @param path 定稿稿的工作区锚定形路径（非空；须锚定 design/ 且在工作区——
 *             候选可被悬卡删除，定稿对象以容器事实为准）
 */
public record FinalizeDesignItemCommand(

        @NotBlank(message = "定稿稿路径不能为空")
        @Size(max = 500, message = "定稿稿路径长度不能超过500")
        String path
) {
}
