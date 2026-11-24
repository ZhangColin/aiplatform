package com.semgravio.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.Size;

/**
 * 建项目命令（#39 创建精简：一句话创建）：requirement 是主入参——项目名由 LLM
 * 异步生成（先落占位 {@code 未命名项目}，禁截取派生）、类型单模板服务端缺省、
 * 引擎读后台全局配置（#42）。
 *
 * <p>#612 设计线退役：终点类型收窄为系统单值——建项目不再接受终点初值（恒系统）。</p>
 *
 * @param requirement 初始需求描述（可空 = 缺省开场提示；自动开场对话的展开
 *                    起点，也是 LLM 取名的输入）
 */
public record CreateProjectCommand(

        @Size(max = 5000, message = "需求描述长度不能超过5000")
        String requirement
) {
}
