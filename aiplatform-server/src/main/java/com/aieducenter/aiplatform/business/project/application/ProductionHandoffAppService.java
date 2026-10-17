package com.aieducenter.aiplatform.business.project.application;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProductionHandoffResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.ProductionHandoff;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProductionHandoffStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.Operator;
import com.aieducenter.aiplatform.business.project.domain.repository.ProductionHandoffRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 生产承接用例（#338，spec #320 实施序④收官）：开工判定建单（系统触发、只建
 * 一次）＋后台人工承接面（两态推进/信息更新，留痕 append-only）＋「需用户
 * 补充」项目级标志置位＋跨 BC 读面（前台六态推导读源）。
 */
@Service
@Slf4j
public class ProductionHandoffAppService {

    /** 承接唯一索引名（约束撞错的判别键，与 V40__production_handoff.sql 对齐）。 */
    private static final String HANDOFF_UNIQUE_INDEX = "uk_prj_production_handoffs_project";

    private final ProductionHandoffRepository handoffRepository;
    private final ProjectRepository projectRepository;

    public ProductionHandoffAppService(ProductionHandoffRepository handoffRepository,
            ProjectRepository projectRepository) {
        this.handoffRepository = handoffRepository;
        this.projectRepository = projectRepository;
    }

    /**
     * 开工判定建单（#338「只建一次」唯一创建口——系统触发，后台无人工创建面）：
     * 事务内判定＝已在即幂等返回既有行（双触发点后到者的常态路径）；并发漏过
     * 预检撞 project_id 唯一索引时，独立事务的 save 即刻抛出——同译幂等回读
     * （加入外层事务的调用面由调用方 {@code KickoffAppService} 在事务边界吸收，
     * 见其 catch 形制）。返回聚合供调用方判定建单时点（通知等副作用归调用方）。
     */
    public ProductionHandoff createForKickoff(Long projectId, Long orderId) {
        return handoffRepository.findByProjectId(projectId).orElseGet(() -> {
            try {
                return handoffRepository.save(ProductionHandoff.createForKickoff(projectId, orderId));
            } catch (DataIntegrityViolationException e) {
                if (violatesHandoffIndex(e)) {
                    // 并发建单撞唯一索引（预检漏过）：后到者幂等回读既有行，不重复建单
                    log.info("项目 {} 并发建生产承接撞唯一索引，幂等回读", projectId);
                    return handoffRepository.findByProjectId(projectId).orElseThrow();
                }
                throw e;
            }
        });
    }

    /**
     * 后台承接详情（承接全量面）：承接字段＋两态＋动作留痕时间线（新 → 旧）。
     *
     * @throws ApplicationException PRJ_055 项目名下无承接记录（开工判定未触发）
     */
    public BackofficeProductionHandoffResponse detailForBackoffice(Long projectId) {
        return BackofficeProductionHandoffResponse.of(requireHandoff(projectId));
    }

    /**
     * 信息更新（后台人工动作，非迁移）：交付联系人/下一步说明/进展更新/预计开始
     * 时间整组替换（未录入段归一 null），状态不动、留痕行 append-only（依据/
     * 操作者必填——聚合守卫 PRJ_057/058/059）。
     */
    public BackofficeProductionHandoffResponse update(Long projectId, String contact, String nextStep,
            String progressNote, LocalDateTime estimatedStartAt, Operator operator, String basis) {
        ProductionHandoff handoff = requireHandoff(projectId);
        handoff.update(contact, nextStep, progressNote, estimatedStartAt, operator, basis);
        return BackofficeProductionHandoffResponse.of(handoffRepository.save(handoff));
    }

    /**
     * 推进（后台人工动作）：待安排生产 → 生产中（唯一迁移边），留痕行随行落。
     *
     * @throws ApplicationException PRJ_055 无承接记录；PRJ_056 生产中再推进
     */
    public BackofficeProductionHandoffResponse advance(Long projectId, String basis, Operator operator) {
        ProductionHandoff handoff = requireHandoff(projectId);
        handoff.advance(basis, operator);
        return BackofficeProductionHandoffResponse.of(handoffRepository.save(handoff));
    }

    /**
     * 「需用户补充」置位/清零（#338 后台动作，前台六态盖帽标志）：置位语义归
     * 后台人工判定（何时需要、需要什么——对话区沟通承载），本口只落标志。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public void setNeedsUserSupplement(Long projectId, boolean required) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        project.flagNeedsUserSupplement(required);
        projectRepository.save(project);
    }

    /**
     * 跨 BC 读面（#338 前台六态推读写源，order 侧投影端点消费）：项目名下承接
     * 的事实形状——未建承接（开工判定未触发）如实 null（缺席态）。
     */
    public ProductionHandoffFacts handoffFactsOf(Long projectId) {
        return handoffRepository.findByProjectId(projectId)
                .map(handoff -> new ProductionHandoffFacts(
                        handoff.getStatus().getCode(),
                        handoff.getStatus().getName(),
                        handoff.getStatus() == ProductionHandoffStatus.IN_PRODUCTION,
                        handoff.getContact(), handoff.getNextStep(), handoff.getProgressNote(),
                        handoff.getEstimatedStartAt()))
                .orElse(null);
    }

    /** 带留痕取承接（后台详情/动作共用寻址：按项目——承接挂项目唯一）。 */
    private ProductionHandoff requireHandoff(Long projectId) {
        return handoffRepository.findWithTrailByProjectId(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PRODUCTION_HANDOFF_NOT_FOUND));
    }

    /**
     * 跨 BC 读面形状（#338 前台六态推读写源，对偶
     * {@code ProjectQueryAppService.ConfirmedVersionRefs} 先例，不进 REST 面）：
     * {@code inProduction} 驱动六态分岔（待安排生产/生产中），余为承接字段直读。
     *
     * @param status      承接状态 code（1=待安排生产 2=生产中）
     * @param statusName  状态名（呈现直读）
     * @param inProduction 是否生产中（两态判定面）
     * @param contact     交付联系人（未录入 null）
     * @param nextStep    下一步说明（同上）
     * @param progressNote 进展更新（同上）
     * @param estimatedStartAt 预计开始时间（待安排 null——仅真实值显示）
     */
    public record ProductionHandoffFacts(Integer status, String statusName, boolean inProduction,
            String contact, String nextStep, String progressNote, LocalDateTime estimatedStartAt) {
    }

    private static boolean violatesHandoffIndex(DataIntegrityViolationException e) {
        return mentions(e.getMessage()) || mentions(e.getMostSpecificCause().getMessage());
    }

    private static boolean mentions(String message) {
        return message != null && message.contains(HANDOFF_UNIQUE_INDEX);
    }
}
