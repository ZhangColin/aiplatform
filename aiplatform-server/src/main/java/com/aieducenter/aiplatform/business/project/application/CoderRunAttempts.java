package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 编码 run 尝试环（生成与修正共用，#22 落位 / #26 迭代环复用——所有编码 run 同
 * 机制）：每次尝试新 runId + role-assigned 前置，失败有余量发 {@code run-retrying}
 * 帧（话术「遇到问题，正在重试」）续试，超限转终态失败（末次 error 帧即终态表达，
 * 由用户侧兜底——生成重新发起 / 修正恢复出口重派或再提意见，#48）。
 *
 * <p>命令全要素同构：CODER 角色卡、{@code coder-{projectId}} 会话（重试续同会话
 * ——已落盘成果保留，同工作区不丢数据）、owner 寻址、长 run 超时、开直播（过程帧
 * 外并产直播帧）、计量 dims（agentKind=coder）、项目工作区、流关联。知识命中前置
 * 注入只进首试 prompt（一次下发一次注入，重试不重检索不重块）。流桥挂逐修改刷新
 * 装饰（#49：live-step 边界 → 探活 → preview-updated 通知）。</p>
 */
@Component
@Slf4j
class CoderRunAttempts {

    /** 编码会话标识派生前缀（projectId → coder-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "coder-";

    /** 一场编码 run 的 prompt 对（首试 + 重试续作轨）。 */
    record Prompts(String first, String retry) {
    }

    private final AgentscopeAgentClient agentClient;
    private final AgentStreamBridge streamBridge;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final GenerationProperties properties;
    private final LiveStepPreviewRefresh previewRefresh;

    CoderRunAttempts(AgentscopeAgentClient agentClient, AgentStreamBridge streamBridge,
            ProjectKnowledgeAppService knowledgeAppService, GenerationProperties properties,
            LiveStepPreviewRefresh previewRefresh) {
        this.agentClient = agentClient;
        this.streamBridge = streamBridge;
        this.knowledgeAppService = knowledgeAppService;
        this.properties = properties;
        this.previewRefresh = previewRefresh;
    }

    /**
     * 跑一场编码 run（有限次尝试）：成功收口即 {@code onSuccess}（收口回调携该次
     * 尝试的 runId——修正收口帧锚定用；回调抛异常即该次尝试失败，走重试/终态——
     * 收口判据不满足的既有口径，如生成 8081 核验 / 修正 finish_fix 事实）。项目
     * 事实（工作区 / owner）从聚合派生。
     *
     * @param what       日志标签（generate / fix）
     * @param firstRunId 首试 runId（调用方预生成随响应回；重试换新 runId 经帧到达）
     * @return           true = 成功收口；false = 重试超限转终态（末次 error 帧已发，
     *                   终态后的兜底归调用方——生成重新发起 / 修正恢复出口 #48）
     */
    boolean run(Project project, String firstRunId, Prompts prompts, Consumer<String> onSuccess,
            String what) {
        Long projectId = project.getId();
        String knowledgePrefix = knowledgeAppService.dispatchInjection(prompts.first());
        int maxAttempts = properties.getMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String runId = attempt == 1 ? firstRunId : AgentStreamAppService.newRunId();
            streamBridge.emitRoleAssigned(projectId, runId, RolePreset.CODER);
            AgentCommand command = new AgentCommand(
                    runId,
                    attempt == 1 ? knowledgePrefix + prompts.first() : prompts.retry(),
                    RolePreset.CODER.systemPrompt(),
                    RolePreset.CODER.chatModelString(),
                    SESSION_PREFIX + projectId,
                    project.getOwnerAccountId() != null
                            ? project.getOwnerAccountId().toString() : null,
                    new UsageContext(Long.toString(projectId),
                            UsageDims.of(projectId, UsageDims.kindOf(RolePreset.CODER),
                                    SESSION_PREFIX + projectId)),
                    Long.toString(project.getWorkspaceId()),
                    Map.of(AgentStreamAppService.PROJECT_FIELD, projectId.toString()),
                    properties.getTimeout(),
                    /* live= */ true,
                    RolePreset.CODER.name(),
                    /* workspaceReadOnly= */ false);
            try {
                // 逐修改刷新（#49）：流桥 sink 外包直播步骤探活装饰——live-step 边界
                // （完整修改落定）→ 平台侧探活 → 通过才发 preview-updated 通知
                agentClient.converse(command, previewRefresh.decorate(
                        projectId, project.getWorkspaceId(), streamBridge.sink(projectId)));
                onSuccess.accept(runId);
                return true;
            }
            catch (RuntimeException e) {
                log.warn("[{}] 项目 {} 第 {}/{} 次尝试失败（runId={}）：{}",
                        what, projectId, attempt, maxAttempts, runId, e.toString());
                if (attempt < maxAttempts) {
                    streamBridge.emitRunRetrying(projectId, runId, attempt + 1);
                }
            }
        }
        log.error("[{}] 项目 {} 重试超限（{} 次），转终态失败——用户侧兜底（生成重新发起/修正恢复出口）",
                what, projectId, maxAttempts);
        return false;
    }
}
