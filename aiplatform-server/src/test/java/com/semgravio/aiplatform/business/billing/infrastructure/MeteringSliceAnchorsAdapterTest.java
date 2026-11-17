package com.semgravio.aiplatform.business.billing.infrastructure;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.metering.domain.port.SubjectSliceAnchors;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本观测切片锚适配器测试（#536 计费⑥）：{@code bil_project_billing} 已翻转行
 * → 计量 subject → 翻转时点映射——已翻转行透出（锚＝flipped_at 非空）、未翻转/
 * 试用行缺席（消费端视全量获客成本桶）；projectId 十进制串化对齐计量 subject
 * 写侧口径。 metering 侧聚合以裸 Map 直注锚，端口实现只在本族钉死。
 */
@IntegrationTest
class MeteringSliceAnchorsAdapterTest {

    private static final long FLIPPED_PROJECT = 8800101L;
    private static final long SOURCE_ONLY_PROJECT = 8800102L;
    private static final long TRIAL_PROJECT = 8800103L;

    @Autowired
    private SubjectSliceAnchors sliceAnchors;

    @Autowired
    private ProjectBillingRepository projectBillingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_project_billing");
    }

    @Test
    void given_flipped_and_unflipped_rows_when_anchors_then_only_flipped_exposed() {
        // 已翻转：源选定（项目池）＋翻转
        ProjectBilling flipped = projectBillingRepository.save(
                ProjectBilling.trialOf(FLIPPED_PROJECT));
        flipped.selectSource(DeductionSource.PROJECT, null);
        projectBillingRepository.save(flipped);
        flipped.flipToPaid(FlipGate.INTERCEPT);
        projectBillingRepository.save(flipped);

        // 源已选未翻转（岔路口前态）：不出锚
        ProjectBilling sourceOnly = projectBillingRepository.save(
                ProjectBilling.trialOf(SOURCE_ONLY_PROJECT));
        sourceOnly.selectSource(DeductionSource.PROJECT, null);
        projectBillingRepository.save(sourceOnly);

        // 纯试用行：不出锚
        projectBillingRepository.save(ProjectBilling.trialOf(TRIAL_PROJECT));

        Map<String, Instant> anchors = sliceAnchors.anchorsBySubject();

        assertThat(anchors).containsOnlyKeys(String.valueOf(FLIPPED_PROJECT));
        assertThat(anchors.get(String.valueOf(FLIPPED_PROJECT))).isNotNull();
    }
}
