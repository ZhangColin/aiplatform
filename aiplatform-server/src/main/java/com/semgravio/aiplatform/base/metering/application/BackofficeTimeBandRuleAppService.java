package com.semgravio.aiplatform.base.metering.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.metering.application.dto.command.OpenTimeBandRuleCommand;
import com.semgravio.aiplatform.base.metering.application.dto.command.RewindowTimeBandRuleCommand;
import com.semgravio.aiplatform.base.metering.application.dto.command.TimeWindowCommand;
import com.semgravio.aiplatform.base.metering.application.dto.query.BackofficeTimeBandRuleQuery;
import com.semgravio.aiplatform.base.metering.application.dto.response.TimeBandRuleResponse;
import com.semgravio.aiplatform.base.metering.application.dto.response.TimeBandRuleRewindowResponse;
import com.semgravio.aiplatform.base.metering.domain.aggregate.TimeBandRule;
import com.semgravio.aiplatform.base.metering.domain.error.MeteringMessage;
import com.semgravio.aiplatform.base.metering.domain.model.Operator;
import com.semgravio.aiplatform.base.metering.domain.repository.TimeBandRuleRepository;

/**
 * 后台时段规则管理写口（#497／ADR-0044 峰谷 1/4）：行清单读（含历史版本）＋
 * 开行（空通道首开，种子脚本通道——一单 N 窗口行＝一个规则版本原子落）＋原子
 * 改窗（关当前版本全部行＋开新版本，可预发布未来起点）＋停用（关版本不接新行，
 * 此后通道 ts 无窗口命中＝不归带）。生命周期与单价表同构（关行开新行），时段
 * 与单价两个变化节奏分表各自生效（调窗口≠调价）。
 *
 * <p><b>重叠校验</b>（对称 METER_008，#160 事故口同款）：本表无
 * {@code (provider, effective_from)} 唯一约束（同版本多窗口行共享起点，约束
 * 会撞合法种入）——同通道全行服务端校验：新开区间 {@code [F, ∞)} 须与除被关
 * 版本外的既有区间皆不重叠，且起点不撞任何既有行（同起点含被关版本自身：
 * 关行不改写 {@code effective_from}，直插同起点行会造出平行版本）。校验在事务
 * 内读后判，并发双写残余竞态 v1 不设防（单管理员操作面，非开放写口，同单价表
 * 口径）。</p>
 */
@Service
public class BackofficeTimeBandRuleAppService {

    private final TimeBandRuleRepository timeBandRuleRepository;

    public BackofficeTimeBandRuleAppService(TimeBandRuleRepository timeBandRuleRepository) {
        this.timeBandRuleRepository = timeBandRuleRepository;
    }

    /**
     * 后台时段规则行清单（含现行与历史行）：provider 精确过滤（可缺省＝全量），
     * 排序服务端定死＝生效起点倒序（新版本在前）、id 倒序稳定同起点。分页钳制/
     * 换算全部来自框架 {@link Pagination}（1 基、缺省 1/20、上界 100 静默贴边）。
     */
    @Transactional(readOnly = true)
    public PageResponse<TimeBandRuleResponse> entries(String provider, Pagination pagination) {
        BackofficeTimeBandRuleQuery query = new BackofficeTimeBandRuleQuery(provider);
        Specification<TimeBandRule> specification = ConditionSpecifications.fromAnnotation(query);
        Pageable pageable = pagination.toPageRequest()
                .withSort(Sort.by(Sort.Direction.DESC, "effectiveFrom").and(
                        Sort.by(Sort.Direction.DESC, "id")));
        Page<TimeBandRule> result = timeBandRuleRepository.findAll(specification, pageable);
        return PageResponse.of(result.map(TimeBandRuleResponse::of));
    }

    /**
     * 开行（空通道首开，#497 种子脚本通道）：一单 N 窗口行＝一个规则版本原子
     * 落（同事务全存或全不存）。窗口项字段合法性由聚合守卫先行裁决
     * （METER_013），再过同通道区间重叠校验（METER_008 对称单价表）。
     * {@code effectiveFrom} 可回溯（种子敞口覆盖存量事件）、可未来（预发布），
     * 缺省即时；操作者随行落（种子脚本不传操作者头 → 落空）。
     *
     * @throws ApplicationException METER_013 窗口项字段不完整；METER_008 同通道
     *                              生效区间重叠（跨区间或同起点）
     */
    @Transactional
    public List<TimeBandRuleResponse> open(OpenTimeBandRuleCommand command, Operator operator) {
        Instant effectiveFrom = command.effectiveFrom() == null
                ? Instant.now() : command.effectiveFrom();
        List<TimeBandRule> opened = openWindows(command.provider(), command.windows(),
                effectiveFrom, operator);
        assertIntervalFree(command.provider(), effectiveFrom, List.of());
        opened.forEach(timeBandRuleRepository::save);
        return opened.stream().map(TimeBandRuleResponse::of).toList();
    }

    /**
     * 原子改窗（单调用关当前版本全部行＋开新版本，同事务）：寻址行须当前
     * （METER_016），命中即关该通道全部敞口行——关行在版本级非单行（同版本
     * 多窗口行共享区间，单行关闭会造出混合态）。新窗口清单整版本替换语义
     * （清单即终态）；{@code effectiveFrom} 可指定（含未来＝预发布，对齐供应商
     * 调窗口公告期），缺省即时。中途任一守卫失败两段都不落（事务回滚）。
     *
     * @throws ApplicationException METER_015 行不存在；METER_013 窗口项字段不
     *                              完整；METER_014 起点早于被关版本起点；
     *                              METER_016 目标非当前行；METER_008 同通道生效
     *                              区间重叠（跨区间或同起点）
     */
    @Transactional
    public TimeBandRuleRewindowResponse rewindow(Long ruleId,
                                                 RewindowTimeBandRuleCommand command,
                                                 Operator operator) {
        TimeBandRule addressed = requireRule(ruleId);
        List<TimeBandRule> currentVersion = currentVersionOf(addressed);
        Instant effectiveFrom = command.effectiveFrom() == null
                ? Instant.now() : command.effectiveFrom();

        assertIntervalFree(addressed.getProvider(), effectiveFrom, currentVersion);

        List<TimeBandRule> closed = new ArrayList<>();
        for (TimeBandRule row : currentVersion) {
            row.close(effectiveFrom); // METER_014 起点倒挂 / METER_016 非当前行
            closed.add(timeBandRuleRepository.save(row));
        }
        List<TimeBandRule> opened = openWindows(addressed.getProvider(), command.windows(),
                effectiveFrom, operator);
        opened.forEach(timeBandRuleRepository::save);
        return new TimeBandRuleRewindowResponse(
                closed.stream().map(TimeBandRuleResponse::of).toList(),
                opened.stream().map(TimeBandRuleResponse::of).toList());
    }

    /**
     * 停用（即时生效）：关当前版本全部行不开新行，此后通道 ts 无窗口命中＝不归带。
     * 对未生效版本（预发布未来起点）停用钳到自身起点成空区间（从未生效）。停用
     * 操作者落被关版本各行（唯一落点）。寻址行须当前版本成员（METER_016）。
     *
     * @throws ApplicationException METER_015 行不存在；METER_016 目标非当前行
     */
    @Transactional
    public List<TimeBandRuleResponse> deactivate(Long ruleId, Operator operator) {
        TimeBandRule addressed = requireRule(ruleId);
        List<TimeBandRule> currentVersion = currentVersionOf(addressed);
        List<TimeBandRule> closed = new ArrayList<>();
        for (TimeBandRule row : currentVersion) {
            row.deactivate(Instant.now(), operator);
            closed.add(timeBandRuleRepository.save(row));
        }
        return closed.stream().map(TimeBandRuleResponse::of).toList();
    }

    private TimeBandRule requireRule(Long ruleId) {
        return timeBandRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ApplicationException(MeteringMessage.TIME_BAND_RULE_NOT_FOUND));
    }

    /**
     * 寻址行所属当前版本：与该行同生效起点的全部敞口行。正常态＝通道全部敞口行；
     * 双敞口事故态（并发/历史遗留）下版本按起点界定——寻址谁即动谁的版本，另一
     * 平行版本原样暴露给重叠校验拦（关行不接「全通道敞口」的暗合并）。
     *
     * @throws ApplicationException METER_016 寻址行已关（不在任何敞口版本内）
     */
    private List<TimeBandRule> currentVersionOf(TimeBandRule addressed) {
        List<TimeBandRule> currentVersion = timeBandRuleRepository
                .findByProviderAndEffectiveToIsNull(addressed.getProvider())
                .stream()
                .filter(row -> row.getEffectiveFrom().equals(addressed.getEffectiveFrom()))
                .toList();
        if (currentVersion.isEmpty()) {
            throw new ApplicationException(MeteringMessage.TIME_BAND_RULE_NOT_CURRENT);
        }
        return currentVersion;
    }

    /**
     * 窗口清单 → 规则行集合（聚合构造即守卫，首个非法项即抛 METER_013——
     * 同事务语义下后续项不落）。
     */
    private List<TimeBandRule> openWindows(String provider, List<TimeWindowCommand> windows,
                                           Instant effectiveFrom, Operator operator) {
        if (windows == null || windows.isEmpty()) {
            throw new ApplicationException(MeteringMessage.TIME_BAND_RULE_FIELDS_INCOMPLETE);
        }
        List<TimeBandRule> opened = new ArrayList<>();
        for (TimeWindowCommand window : windows) {
            opened.add(TimeBandRule.open(provider, window.band(),
                    window.dayMask() == null ? -1 : window.dayMask(),
                    window.startMinute() == null ? -1 : window.startMinute(),
                    window.endMinute() == null ? -1 : window.endMinute(),
                    effectiveFrom, operator));
        }
        return opened;
    }

    /**
     * 同通道区间校验（对称单价表）：拟开区间 {@code [F, ∞)} 与既有行两查——同
     * 起点（含被关版本自身：关行不改写起点，同起点直插会造平行版本）与跨区间
     * 重叠（除被关版本外，任一行敞口或终点晚于 F 即重叠——半开区间 [a, b) 与
     * [F, ∞) 相交当且仅当 {@code F < b}）。{@code excludedRows} 为改窗被关
     * 版本（开行传空＝全行计入）。
     */
    private void assertIntervalFree(String provider, Instant effectiveFrom,
                                    List<TimeBandRule> excludedRows) {
        List<TimeBandRule> rows = timeBandRuleRepository.findByProviderOrderByEffectiveFromAsc(provider);
        for (TimeBandRule row : rows) {
            if (effectiveFrom.equals(row.getEffectiveFrom())) {
                throw new ApplicationException(MeteringMessage.PRICE_ENTRY_INTERVAL_OVERLAPPED);
            }
            boolean excluded = excludedRows.stream().anyMatch(e -> e.getId().equals(row.getId()));
            if (!excluded
                    && (row.getEffectiveTo() == null || effectiveFrom.isBefore(row.getEffectiveTo()))) {
                throw new ApplicationException(MeteringMessage.PRICE_ENTRY_INTERVAL_OVERLAPPED);
            }
        }
    }
}
