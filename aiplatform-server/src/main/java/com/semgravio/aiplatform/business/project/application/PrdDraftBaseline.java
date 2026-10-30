package com.semgravio.aiplatform.business.project.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.project.application.dto.response.PrdResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.PrdVersion;
import com.semgravio.aiplatform.business.project.domain.repository.PrdVersionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * PRD 草稿基准（#334 改需求回路）：「工作区活草稿是否偏离最新已确认版本」的
 * 漂移判定单点——大改小改分流（轮收口停派 or 照旧迭代）与修正 run 起跑基准
 * （小迭代基准＝当前已确认版本）共用同一判定。判定正本＝快照全文比对（确认
 * 幂等 #330 同源：不拿 prd_produced_at 锚判定——锚随每次 savePrd 刷新、同文
 * 重存锚漂）；无已确认版本（访谈期/legacy）即无版本制基准，恒「未漂移」
 * （照旧读工作区正本——legacy 项目不虚构确认制）。
 *
 * <p><b>读失败 fail-closed</b>：草稿读不出（环境故障/文件缺失）时按「已漂移」
 * 处理、返回最新已确认版——两侧消费都安全：轮收口停派待确认（好过在漂移未知
 * 的草稿上派小迭代）、修正 run 起跑以已确认版为基准（已确认版作为基准永不
 * 错——最坏与草稿同文）。</p>
 *
 * <p>独立组件而非 {@link PrdVersionAppService} 方法：后者确认时需派修正 run
 * （下一版 Demo）依赖 {@link IterationAppService}，而迭代轨起跑又要本判定
 * ——独立放置免成环。</p>
 */
@Component
@Slf4j
public class PrdDraftBaseline {

    private final PrdVersionRepository prdVersions;
    private final ProjectQueryAppService projectQueryAppService;

    public PrdDraftBaseline(PrdVersionRepository prdVersions,
            ProjectQueryAppService projectQueryAppService) {
        this.prdVersions = prdVersions;
        this.projectQueryAppService = projectQueryAppService;
    }

    /**
     * 草稿偏离判定（#334）：返回非空 = 工作区草稿与最新已确认版不同文（或读
     * 不出——fail-closed），值为偏离中的基准（最新已确认版本行，含全文）；
     * 空 = 无已确认版或草稿与之一致（无新草稿在场）。
     */
    public Optional<PrdVersion> driftedFrom(Long projectId) {
        PrdVersion latest = prdVersions.findFirstByProjectIdOrderByVersionNoDesc(projectId)
                .orElse(null);
        if (latest == null) {
            return Optional.empty(); // 无已确认版＝无版本制基准（访谈期/legacy 照旧）
        }
        try {
            PrdResponse draft = projectQueryAppService.prd(projectId);
            if (latest.getContent().equals(draft.content())) {
                return Optional.empty(); // 同文＝无新草稿（全文比对正本，锚不作数）
            }
            return Optional.of(latest);
        }
        catch (RuntimeException e) {
            // 读失败 fail-closed：按已漂移返回基准（两侧消费都安全，见类注）
            log.warn("[prd-baseline] 项目 {} 草稿读不出（{}）——按已漂移处理，基准＝已确认 v{}",
                    projectId, e.toString(), latest.getVersionNo());
            return Optional.of(latest);
        }
    }
}
