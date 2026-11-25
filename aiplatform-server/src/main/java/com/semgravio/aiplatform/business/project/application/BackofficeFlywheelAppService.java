package com.semgravio.aiplatform.business.project.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.web.request.Pagination;

import com.semgravio.aiplatform.base.knowledge.application.KnowledgeFlywheelStatsAppService;
import com.semgravio.aiplatform.base.knowledge.domain.model.ProjectKnowledgeFlywheelStats;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeFlywheelStatsResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 后台飞轮三个数统计（#643，阶段六-6；父票 #637，ADR-0058）：只读、只看趋势，
 * 不打分、不设目标、不做排名。项目清单行取项目表（每个项目一行），修改轮数、
 * 没做对轮数与命中三件取知识侧统计读面（{@link KnowledgeFlywheelStatsAppService}
 * 合并命中留痕与事后提炼处理记录）——不新增埋点表、不改提炼与检索写入路径。
 *
 * <p>口径：修改轮数＝提炼记录里版本号大于 1 的条数；没做对轮数＝其中
 * missed_count 大于 0 的条数（记录以版本为唯一键，一条记录即一轮）；命中次数＝
 * 含命中条目的留痕条数（成功 0 条与降级都不计，是否有命中据此推导）。没有版本或
 * 没有提炼记录的项目按 0 呈现，不报错。项目名取提炼记录里的快照名（项目改名后
 * 仍可读），无记录时回落当前项目名。</p>
 *
 * <p>行序与服务端分页：项目 id 倒序（TSID 时间有序＝新项目在前），分页钳制/换算
 * 全部来自框架 {@link Pagination}；汇总始终按全量项目口径计算，不随当页变化。
 * 只读事务内完成知识侧聚合与项目清单读取。</p>
 */
@Service
public class BackofficeFlywheelAppService {

    private final ProjectRepository projectRepository;
    private final KnowledgeFlywheelStatsAppService knowledgeFlywheelStatsAppService;

    public BackofficeFlywheelAppService(ProjectRepository projectRepository,
            KnowledgeFlywheelStatsAppService knowledgeFlywheelStatsAppService) {
        this.projectRepository = projectRepository;
        this.knowledgeFlywheelStatsAppService = knowledgeFlywheelStatsAppService;
    }

    /** 飞轮统计：项目行（分页）＋有命中／无命中分组汇总（全量口径）。 */
    @Transactional(readOnly = true)
    public BackofficeFlywheelStatsResponse stats(Pagination pagination) {
        Map<String, ProjectKnowledgeFlywheelStats> knowledgeStats = knowledgeFlywheelStatsAppService
                .projectStats().stream()
                .collect(Collectors.toMap(ProjectKnowledgeFlywheelStats::projectId,
                        Function.identity()));
        List<Project> projects = projectRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        List<BackofficeFlywheelStatsResponse.ProjectRow> rows = new ArrayList<>(projects.size());
        for (Project project : projects) {
            rows.add(rowOf(project, knowledgeStats.get(project.getId().toString())));
        }
        // offset() 出 long，先与行数取 min 再收窄（同 #164 项目成本清单先例）
        int fromIndex = (int) Math.min(pagination.offset(), rows.size());
        int toIndex = Math.min(fromIndex + pagination.limit(), rows.size());
        return new BackofficeFlywheelStatsResponse(rows.subList(fromIndex, toIndex), rows.size(),
                pagination.page(), pagination.size(), summaryOf(rows));
    }

    private static BackofficeFlywheelStatsResponse.ProjectRow rowOf(Project project,
            ProjectKnowledgeFlywheelStats stats) {
        int hitCount = stats == null ? 0 : stats.hitCount();
        Instant latestHitAt = stats == null ? null : stats.latestHitAt();
        return new BackofficeFlywheelStatsResponse.ProjectRow(
                project.getId().toString(),
                stats == null || stats.projectNameSnapshot() == null
                        ? project.getName() : stats.projectNameSnapshot(),
                stats == null ? 0 : stats.modificationRounds(),
                stats == null ? 0 : stats.missedRounds(),
                hitCount,
                latestHitAt,
                hitCount > 0);
    }

    /** 有命中／无命中两组：项目数＋组内平均修改轮数、平均没做对轮数。 */
    private static BackofficeFlywheelStatsResponse.Summary summaryOf(
            List<BackofficeFlywheelStatsResponse.ProjectRow> rows) {
        int withHitCount = 0;
        int withoutHitCount = 0;
        long withHitModificationSum = 0;
        long withHitMissedSum = 0;
        long withoutHitModificationSum = 0;
        long withoutHitMissedSum = 0;
        for (BackofficeFlywheelStatsResponse.ProjectRow row : rows) {
            if (row.hasHit()) {
                withHitCount++;
                withHitModificationSum += row.modificationRounds();
                withHitMissedSum += row.missedRounds();
            }
            else {
                withoutHitCount++;
                withoutHitModificationSum += row.modificationRounds();
                withoutHitMissedSum += row.missedRounds();
            }
        }
        return new BackofficeFlywheelStatsResponse.Summary(
                withHitCount,
                withoutHitCount,
                average(withHitModificationSum, withHitCount),
                average(withHitMissedSum, withHitCount),
                average(withoutHitModificationSum, withoutHitCount),
                average(withoutHitMissedSum, withoutHitCount));
    }

    private static double average(long sum, int count) {
        return count == 0 ? 0.0 : (double) sum / count;
    }
}
