package com.semgravio.aiplatform.base.knowledge.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.base.knowledge.domain.model.ProjectDistillationStats;
import com.semgravio.aiplatform.base.knowledge.domain.model.ProjectKnowledgeFlywheelStats;
import com.semgravio.aiplatform.base.knowledge.domain.model.ProjectRetrievalStats;
import com.semgravio.aiplatform.base.knowledge.domain.repository.ExperienceDistillationStore;
import com.semgravio.aiplatform.base.knowledge.domain.repository.KnowledgeStore;

/**
 * 飞轮知识侧统计读面（#643，阶段六-6；父票 #637，ADR-0058）：把命中留痕
 * （{@code knw_retrievals}）与事后提炼处理记录（{@code knw_experience_distillations}）
 * 按项目合并成只读读模型，供后台飞轮统计消费。只读聚合，不新增埋点表、不改提炼与
 * 检索写入路径；两表项目键形态不同（留痕为字符串、提炼记录为 BIGINT），合并在此
 * 收口，消费方无需感知。
 *
 * <p>口径：修改轮数＝提炼记录里版本号大于 1 的条数；没做对轮数＝其中
 * missed_count 大于 0 的条数；命中次数＝含命中条目的留痕条数（成功 0 条与降级都
 * 不计）。任一侧没有数据的项目照常出现在结果里（按 0／null 呈现）。</p>
 */
@Service
public class KnowledgeFlywheelStatsAppService {

    private final ExperienceDistillationStore distillationStore;
    private final KnowledgeStore knowledgeStore;

    public KnowledgeFlywheelStatsAppService(ExperienceDistillationStore distillationStore,
            KnowledgeStore knowledgeStore) {
        this.distillationStore = distillationStore;
        this.knowledgeStore = knowledgeStore;
    }

    /** 按项目合并的飞轮知识侧统计（两表并集；任一侧缺失按 0／null 呈现）。 */
    @Transactional(readOnly = true)
    public List<ProjectKnowledgeFlywheelStats> projectStats() {
        Map<String, ProjectDistillationStats> distillations = distillationStore.projectStats()
                .stream()
                .collect(Collectors.toMap(
                        stats -> Long.toString(stats.projectId()), Function.identity()));
        Map<String, ProjectRetrievalStats> retrievals = knowledgeStore.retrievalStats().stream()
                .collect(Collectors.toMap(ProjectRetrievalStats::projectId, Function.identity(),
                        (first, second) -> first));
        return Stream.concat(distillations.keySet().stream(), retrievals.keySet().stream())
                .distinct()
                .map(projectId -> {
                    ProjectDistillationStats distillation = distillations.get(projectId);
                    ProjectRetrievalStats retrieval = retrievals.get(projectId);
                    return new ProjectKnowledgeFlywheelStats(
                            projectId,
                            distillation == null ? null : distillation.projectName(),
                            distillation == null ? 0 : distillation.modificationRounds(),
                            distillation == null ? 0 : distillation.missedRounds(),
                            retrieval == null ? 0 : retrieval.hitCount(),
                            retrieval == null ? null : retrieval.latestHitAt());
                })
                .toList();
    }
}
