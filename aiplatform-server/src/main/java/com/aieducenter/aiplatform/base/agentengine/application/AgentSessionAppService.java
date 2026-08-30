package com.aieducenter.aiplatform.base.agentengine.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.base.agentengine.domain.aggregate.AgentSession;
import com.aieducenter.aiplatform.base.agentengine.domain.repository.AgentSessionRepository;
import com.aieducenter.aiplatform.base.agentengine.infrastructure.WorkspaceHandleClient;

/**
 * agent 会话登记用例（{@code agt_agent_sessions} 落库，跨重启存活）。
 */
@Service
public class AgentSessionAppService {

    private final AgentSessionRepository sessionRepository;
    private final WorkspaceHandleClient workspaceHandleClient;

    public AgentSessionAppService(AgentSessionRepository sessionRepository,
                                  WorkspaceHandleClient workspaceHandleClient) {
        this.sessionRepository = sessionRepository;
        this.workspaceHandleClient = workspaceHandleClient;
    }

    /**
     * 登记会话并回答「是否首见」（session-created 发射口径）：已登记则刷新最近
     * 运行（ranOn）。跨上下文登记（如 chatagent）走本应用层口——引擎自述 engine
     * 值（UsageEvent.engine 同值）。
     */
    @Transactional
    public boolean recordIfAbsent(String workspaceId, String engine, String sessionId,
                                  String runId) {
        long numericWorkspaceId = workspaceHandleClient.handleOf(workspaceId)
                .workspaceId().id();
        AgentSession existing = sessionRepository.findBySessionId(sessionId).orElse(null);
        boolean firstSeen = existing == null;
        AgentSession session = firstSeen
                ? AgentSession.open(numericWorkspaceId, engine, sessionId, runId)
                : existing;
        session.ranOn(runId);
        sessionRepository.save(session);
        return firstSeen;
    }
}
