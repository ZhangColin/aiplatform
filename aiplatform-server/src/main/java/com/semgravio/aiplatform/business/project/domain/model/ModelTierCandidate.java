package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 模型档位候选＝有序候选队列的一行（ADR-0053 ③，{@code prj_model_tier_
 * candidates}）：模型配置（旧称档位）的备选通道×模型指向，priority 升序＝
 * 队列序（主备只是队列前两名），行级启停。候选行<b>不自带行为属性</b>——
 * 深度思考（#370）是档位行为属性，全队列继承档位行值。
 *
 * <p>切换单元恰好是故障的物理单元（endpoint×账号 key＝通道行）：运行期故障
 * 切换语义收口在模型包装层（T3 {@code FailoverModel}），读面（本行所属的
 * 候选队列解析）只做配置态校验——任一启用候选的通道行坏＝解析明确报错，
 * 无静默跳过（与通道停用同 Doctrine，ADR-0031）。</p>
 *
 * <p>软着陆（#556）：档位无候选行时 resolve 回退档位行现列单元素队列——
 * 候选表是档位行指向的<b>扩展</b>而非替代；配置了候选行（含全停用）则候选
 * 表是唯一队列数据源，档位行现列不再参队。</p>
 *
 * @param tierKey      档位语义键（引用腿，同档位行主键）
 * @param priority     队列序（小者先；档内唯一）
 * @param providerKey  通道键（一行＝endpoint×账号 key——模型串前缀腿）
 * @param modelName    实际模型名（计量/单价行匹配腿）
 * @param enabled      行级启停；false＝读面剔除（队列不参队）
 * @param operatorId   最近写者 id（admin 侧 TSID；null＝种子/迁移写入；写面 T5）
 * @param operatorName 最近写者名（直读展示）
 * @param updatedAt    最近写入时刻（读模型列——回读填充；写路径构造时 null）
 */
public record ModelTierCandidate(String tierKey, int priority, String providerKey,
        String modelName, boolean enabled, String operatorId, String operatorName,
        LocalDateTime updatedAt) {
}
