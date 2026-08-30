package com.aieducenter.aiplatform.base.agentengine.domain.port;

import java.util.List;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;

/**
 * 等待点答复通道（窄面）：能承接 {@code settle} 派发的智能体内核侧组件（当前
 * 唯一实现 = 对话智能体 agentscope）——settle 即续跑、deny cap 判定回报。
 *
 * <p>寻址：按 {@link #engine()} 名（与 agt_agent_sessions.engine 同值）经
 * {@code WaitResponderDirectory} 索引——答复派发只认会话自述的引擎名。</p>
 */
@Port(PortType.CLIENT)
public interface WaitResponder {

    /** 引擎名（UsageEvent.engine 同值约定）。 */
    String engine();

    /** 回答问题：answers 按问题顺序，每项 = 该问题选中的标签列表（custom 输入也作为标签）。 */
    void replyQuestions(WorkspaceHandle handle, String sessionId, String requestId,
                        List<List<String>> answers);

    /** 审批回复（人做决策：agent 请求权限时由用户批准/拒绝）。 */
    void replyPermission(WorkspaceHandle handle, String sessionId, String permissionId,
                         boolean approve);

    /**
     * 终止会话当前运行（deny cap 平台终止路径，A1 §1.3：同 run 内 permission deny
     * 计数达阈值 → 平台主动终止，防审批循环）。无运行可终止返回 false。
     */
    boolean abort(WorkspaceHandle handle, String sessionId);
}
