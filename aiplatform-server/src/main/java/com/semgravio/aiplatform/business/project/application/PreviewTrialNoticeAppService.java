package com.semgravio.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.TrialNoticeResponse;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 试用体验提示读面（#539 访谈④）：预览网关注入脚本（trial-notice.js）经网关反代
 * {@code /.aiplatform/trial-notice} 打本口——workspaceId 来自网关子域捕获
 * （X-Preview-Workspace 头），映射项目后经 billing 两轨判定单读点
 * （{@link BillingAppService#trackOf}，ADR-0047）投影提示态：试用（行缺席／源选定
 * 未翻转）恒显；付费翻转即熄；workspaceId 无对应项目（含空值——直打后端的畸形
 * 请求）如实 false、不炸场。访客匿名可访（裸前缀在 /api 拦截面外）。
 */
@Service
public class PreviewTrialNoticeAppService {

    private final ProjectRepository projectRepository;
    private final BillingAppService billingAppService;

    public PreviewTrialNoticeAppService(ProjectRepository projectRepository,
            BillingAppService billingAppService) {
        this.projectRepository = projectRepository;
        this.billingAppService = billingAppService;
    }

    /** 试用态判定：项目缺席/读点付费＝不提示，其余（行缺席或未翻转）＝提示。 */
    @Transactional(readOnly = true)
    public TrialNoticeResponse trialNotice(Long workspaceId) {
        if (workspaceId == null) {
            return new TrialNoticeResponse(false);
        }
        return projectRepository.findByWorkspaceId(workspaceId)
                .map(project -> new TrialNoticeResponse(
                        billingAppService.trackOf(project.getId()).trial()))
                .orElseGet(() -> new TrialNoticeResponse(false));
    }
}
