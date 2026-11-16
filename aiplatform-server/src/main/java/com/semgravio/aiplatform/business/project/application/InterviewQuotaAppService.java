package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.InterviewQuotaResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 五问额度读面（#538 访谈①，ADR-0048「五问额度＝程序计数权威」）：试用态访谈的
 * 提问上限＝5 张问答卡（机制定 5，数值属运营面 #527 出界）。计数归对话史读口
 * （{@link ConversationHistoryAppService#answeredGapCount}，现成数据＋读口），
 * 两轨判定归 billing 单读点（{@link BillingAppService#trackOf}——协议分轨是
 * ADR-0047 四消费面之一，同源取用），两源在<b>本服务合流</b>成唯一读面：
 * 「第 N/5」进度 chip 呈现（GET 端点）与编排层收口判定（收口指令注入、合法形
 * 收窄）同读本口，前端不自算。
 *
 * <p>适用面＝试用态 ∧ 访谈期（未产出 PRD ∧ 未生成）；付费翻转后额度解除、PRD
 * 产出即访谈收口——读面整体缺席（null），chip 不呈现。归档项目照读（对话区
 * 只读终态，额度面同对话史口径）。</p>
 */
@Service
public class InterviewQuotaAppService {

    /** 试用态访谈提问额度（机制定 5——数值属运营面，#527 出界不另立配置）。 */
    public static final int TRIAL_QUESTION_QUOTA = 5;

    private final ProjectRepository projectRepository;
    private final ConversationHistoryAppService conversationHistory;
    private final BillingAppService billingAppService;

    public InterviewQuotaAppService(ProjectRepository projectRepository,
            ConversationHistoryAppService conversationHistory,
            BillingAppService billingAppService) {
        this.projectRepository = projectRepository;
        this.conversationHistory = conversationHistory;
        this.billingAppService = billingAppService;
    }

    /**
     * 额度读面：试用态访谈期返回 {已答缺口数, 5}；否则 null。项目不存在 404 PRJ_001
     * 由端点层守卫（读面缺席语义与拦截卡同族——null＝不呈现，非错误）。
     */
    @Transactional(readOnly = true)
    public InterviewQuotaResponse quotaOf(Long projectId) {
        return computeQuota(projectId);
    }

    /**
     * 额度读面计算体（quotaOf / closureRequired 共用——收口判定自调用走本体内联，
     * 绕开代理自调用陷阱：经 this.quotaOf() 调用时事务注解不生效）。
     */
    private InterviewQuotaResponse computeQuota(Long projectId) {
        if (!billingAppService.trackOf(projectId).trial()) {
            return null; // 付费翻转后额度解除（无上限访谈）
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getPrdProducedAt() != null
                || project.getGeneratedAt() != null) {
            return null; // PRD 产出即访谈收口；已生成＝迭代期——额度只约束访谈期
        }
        return new InterviewQuotaResponse(
                conversationHistory.answeredGapCount(projectId), TRIAL_QUESTION_QUOTA);
    }

    /** 收口判定：额度读面在场且已答满——编排层据此注入收口指令＋收窄合法收口形。 */
    public boolean closureRequired(Long projectId) {
        InterviewQuotaResponse quota = computeQuota(projectId);
        return quota != null && quota.answered() >= quota.total();
    }
}
