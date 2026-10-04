package com.aieducenter.aiplatform.base.workspace.application.dto.response;

/**
 * 工作区命令执行结果的字节形（#283 图片 raw 直出）：stdout 按原始字节归一化
 * （{@link ExecResultResponse} 的 String 形经字符集解释，二进制有损）；exitCode
 * 非 0 是命令自身失败，不是环境故障。字节止于调用方（REST 层直出，无 JSON 信封形）。
 */
public record BinaryExecResponse(byte[] stdout, String stderr, int exitCode) {
}
