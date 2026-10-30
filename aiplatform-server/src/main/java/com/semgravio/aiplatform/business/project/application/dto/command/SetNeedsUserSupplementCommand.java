package com.semgravio.aiplatform.business.project.application.dto.command;

/**
 * 「需用户补充」置位命令（#338 后台机机面）：置位语义（何时需要用户补充、
 * 补充什么——对话区沟通承载）归后台人工判定，命令只携布尔。
 *
 * @param required true＝置位（前台六态盖帽「需用户补充」）、false＝清零
 */
public record SetNeedsUserSupplementCommand(
        boolean required
) {
}
