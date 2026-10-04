package com.aieducenter.aiplatform.base.workspace.domain.model;

/**
 * 工作区内执行一条命令的字节结果（{@code execBinary} 的返回形，#283 图片 raw
 * 直出）：stdout 按<strong>原始字节</strong>捕获（{@link ExecResult} 的 String 形
 * 经字符集解释，二进制有损）。exitCode = 0 表示命令成功；非 0 是命令自身的失败，
 * 不是环境故障——退出码语义归调用方解释。
 */
public record BinaryExecResult(byte[] stdout, String stderr, int exitCode) {
}
