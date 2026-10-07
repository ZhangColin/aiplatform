package com.aieducenter.aiplatform.business.project.application.dto.command;

import jakarta.validation.constraints.Size;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;

/**
 * 建项目命令（#39 创建精简：一句话创建；#299 终点初值入载荷，ADR-0029）：
 * requirement 是主入参——项目名由 LLM 异步生成（先落占位 {@code 未命名项目}，
 * 禁截取派生）、类型单模板服务端缺省、引擎读后台全局配置（#42）。
 *
 * @param requirement   初始需求描述（可空 = 缺省开场提示；自动开场对话的展开
 *                      起点，也是 LLM 取名的输入）
 * @param endpointType  终点类型初值（可空 = 缺省系统——主链路零变化；入口选
 *                      「做设计」携 1，Integer code 经框架转枚举，不合法 400）
 */
public record CreateProjectCommand(

        @Size(max = 5000, message = "需求描述长度不能超过5000")
        String requirement,

        ProjectEndpointType endpointType
) {
}
