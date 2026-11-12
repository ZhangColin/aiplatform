package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceOverviewResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceSweepResponse;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceSweepAction;
import com.semgravio.aiplatform.base.workspace.domain.model.OrphanSweepReport;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.HostObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceSweepActionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 资源面用例（#489，ADR-0045「后台新增资源大盘＋手动清扫」）：
 * <ul>
 * <li><b>overview（大盘）</b>：主机盘＋docker 四类用量＋无主计数＋在册失联计数
 * ＋最近清扫——纯读库零 docker 调用（#487 治超时口径延伸：数据取扫描采样
 * 〔{@code WorkspaceObservationSamplingAppService} 轮末主机帧〕与清扫留痕，
 * 请求路径零 docker 长操作、秒级返回）；</li>
 * <li><b>sweep（手动清扫）</b>：手动触发一次无主收编并回逐项处置报告——<b>复用
 * #486 收编面</b>（{@link EnvironmentBackend#sweepOrphanWorkspaceContainers}/
 * {@link EnvironmentBackend#sweepOrphanWorkspaceVolumes} 同一实现同一准绳，不另写
 * 一套）：保留集＝注册表全量编号、宽限线＝触发时刻减 {@link
 * WorkspaceHibernationAppService#ORPHAN_SWEEP_GRACE}（10 分钟，与扫描轮同常量）。
 * 库不可达双闸：一刀未动、如实呈报（registryReachable=false）；docker 面失败
 * 即时抛 WSP_002（未执行完的清扫不留痕不定格）。append-only 留痕
 * （{@code wsp_sweep_actions}）：操作者＋逐项报告 JSON 全量落档——照
 * {@code WorkspaceAction} 先例（缺透传头落空 NULL、被拒不落行）。</li>
 * </ul>
 *
 * <p>手动清扫<b>不受休眠总开关静默</b>（{@code WorkspaceProperties
 * #isHibernationEnabled()} 只静默自动扫描轮）：管理员按需对账是显式动作，ADR-0045
 * 「动刀类走 admin 手动＋留痕」。在册失联的裁决出口不在此（#490 一键回收），
 * 本面只收无主（注册表外的命名资源）。</p>
 */
@Service
@Slf4j
public class WorkspaceResourceAppService {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final HostObservationSampleRepository hostRepository;
    private final WorkspaceLostMarkRepository lostMarkRepository;
    private final WorkspaceSweepActionRepository sweepActionRepository;

    private static final ObjectMapper JSON = new ObjectMapper();

    public WorkspaceResourceAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            HostObservationSampleRepository hostRepository,
            WorkspaceLostMarkRepository lostMarkRepository,
            WorkspaceSweepActionRepository sweepActionRepository) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.hostRepository = hostRepository;
        this.lostMarkRepository = lostMarkRepository;
        this.sweepActionRepository = sweepActionRepository;
    }

    /**
     * 资源大盘（GET resources/overview）：采样帧＋失联计数＋最近清扫——纯读库，
     * 零 docker 调用（未采样的首个扫描窗内各面如实 null）。
     */
    @Transactional(readOnly = true)
    public BackofficeResourceOverviewResponse overview() {
        HostObservationSample sample = hostRepository
                .findById(HostObservationSample.SINGLETON_ID).orElse(null);
        return BackofficeResourceOverviewResponse.of(sample, lostMarkRepository.count(),
                sweepActionRepository.findTopByOrderBySweptAtDescIdDesc().orElse(null));
    }

    /**
     * 手动清扫（POST resources/sweep）：收编容器 → 收编卷（#486 同序——占卷容器
     * 在场则卷下轮再收）→ 合流报告＋留痕。库不可达：一刀未动、如实呈报（留痕行
     * 照落——「发了清扫而没动刀」本身是要可审计的事实）。
     *
     * @param operator 操作者（透传头；缺头 null 落空口径）
     * @param now      清扫时刻（宽限线＝now 减 10 分钟；调用方时钟——照
     *                 {@code WorkspaceHibernationAppService#scanOnce} 注入先例）
     */
    public BackofficeResourceSweepResponse sweep(Operator operator, LocalDateTime now) {
        LocalDateTime graceLine = now.minus(WorkspaceHibernationAppService.ORPHAN_SWEEP_GRACE);
        List<Workspace> registered;
        boolean registryReachable;
        try {
            registered = workspaceRepository.findAll();
            registryReachable = true;
        } catch (RuntimeException e) {
            log.warn("[workspace] 手动清扫：注册表不可达（双闸——准绳无从构成，一刀未动）", e);
            registered = List.of();
            registryReachable = false;
        }
        OrphanSweepReport report = registryReachable
                ? sweepAll(idsOf(registered), graceLine)
                : new OrphanSweepReport(List.of());
        sweepActionRepository.save(WorkspaceSweepAction.record(operator, registryReachable,
                report.sweptCountOf(OrphanSweepReport.Kind.CONTAINER),
                report.sweptCountOf(OrphanSweepReport.Kind.VOLUME),
                report.skippedCount(), report.failedCount(),
                itemsJsonOf(report), now));
        log.info("[workspace] 手动清扫收口（操作者={}）：容器收编 {}、卷收编 {}、跳过 {}、未成 {}",
                operator != null ? operator.id() : "（缺操作者头）",
                report.sweptCountOf(OrphanSweepReport.Kind.CONTAINER),
                report.sweptCountOf(OrphanSweepReport.Kind.VOLUME),
                report.skippedCount(), report.failedCount());
        return BackofficeResourceSweepResponse.of(report, now,
                operator != null ? operator.id() : null,
                operator != null ? operator.name() : null,
                registryReachable, graceLine);
    }

    /** 容器先卷后（#486 同序）＋报告合流（逐项全量——收编/跳过/未成各列示）。 */
    private OrphanSweepReport sweepAll(Set<Long> keepIds, LocalDateTime graceLine) {
        OrphanSweepReport containers = environmentBackend
                .sweepOrphanWorkspaceContainers(keepIds, graceLine);
        OrphanSweepReport volumes = environmentBackend
                .sweepOrphanWorkspaceVolumes(keepIds, graceLine);
        List<OrphanSweepReport.Item> merged = new ArrayList<>(containers.items());
        merged.addAll(volumes.items());
        return new OrphanSweepReport(merged);
    }

    private static Set<Long> idsOf(List<Workspace> registered) {
        return registered.stream().map(Workspace::getId).collect(Collectors.toSet());
    }

    /** 逐项报告 JSON（留档审计面；序列化失败落 "[]"——回执仍全量呈现，不因留痕炸清扫）。 */
    private static String itemsJsonOf(OrphanSweepReport report) {
        try {
            return JSON.writeValueAsString(report.items().stream()
                    .map(item -> Map.of(
                            "kind", item.kind().name(),
                            "identifier", item.identifier(),
                            "disposition", item.disposition().name(),
                            "skipReason", item.skipReason() != null
                                    ? item.skipReason().name() : "",
                            "resourceCreatedAt", item.resourceCreatedAt() != null
                                    ? item.resourceCreatedAt().toString() : ""))
                    .toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
