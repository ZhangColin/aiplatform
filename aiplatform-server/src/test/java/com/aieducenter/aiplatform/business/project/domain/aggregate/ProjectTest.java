package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目聚合不变量（业务字段 + 工作区引用 + 归属；PRD 产出与首次生成两个单向事实位）。
 */
class ProjectTest {

    @Test
    void given_valid_fields_when_create_then_defaults_applied() {
        Project project = Project.create("官网 demo", null, 100L, 200L);

        assertThat(project.getName()).isEqualTo("官网 demo");
        assertThat(project.getType()).isEqualTo(ProjectType.WEBSITE); // 类型缺省官网
        assertThat(project.getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM); // 终点缺省系统（主链路）
        assertThat(project.getWorkspaceId()).isEqualTo(100L);
        assertThat(project.getOwnerAccountId()).isEqualTo(200L);
        assertThat(project.getArchivedAt()).isNull(); // 未归档
    }

    @Test
    void given_design_endpoint_when_create_then_initial_type_kept() {
        // #299 入口两档定初值：终显形携终点、null 走缺省（orDefault）
        Project design = Project.create("咖啡店视觉", null, ProjectEndpointType.DESIGN, 1L, null);
        assertThat(design.getEndpointType()).isEqualTo(ProjectEndpointType.DESIGN);

        Project mainline = Project.create("官网", null, null, 1L, null);
        assertThat(mainline.getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM);
    }

    @Test
    void given_explicit_type_when_create_then_kept() {
        Project project = Project.create("商城", ProjectType.ECOMMERCE, 1L, null);

        assertThat(project.getType()).isEqualTo(ProjectType.ECOMMERCE);
        assertThat(project.getOwnerAccountId()).isNull(); // 归属可空（无会话上下文）
    }

    @Test
    void given_blank_name_when_create_then_domain_error() {
        assertThatThrownBy(() -> Project.create(" ", ProjectType.WEBSITE, 1L, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NAME_BLANK.message());
    }

    @Test
    void given_null_workspace_when_create_then_domain_error() {
        assertThatThrownBy(() -> Project.create("官网", ProjectType.WEBSITE, null, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_new_project_when_create_then_prd_not_produced() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);

        assertThat(project.getPrdProducedAt()).isNull(); // NULL = PRD 未产出（#41 状态位）
    }

    @Test
    void given_prd_saved_when_markPrdProduced_then_timestamp_set() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);

        project.markPrdProduced();

        // 置位含产出/更新时间戳；savePrd 修订再执行即刷新为最近写出（G1 谓词查非空）
        assertThat(project.getPrdProducedAt()).isNotNull();
    }

    @Test
    void given_new_project_when_create_then_not_generated() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);

        assertThat(project.getGeneratedAt()).isNull(); // NULL = 未生成过
    }

    @Test
    void given_not_generated_when_markGenerated_then_first_time_set() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);

        project.markGenerated();

        assertThat(project.getGeneratedAt()).isNotNull(); // 首次生成时点落定
    }

    @Test
    void given_generated_when_markGenerated_again_then_timestamp_kept() {
        // 首次生成时点单向置位：后续生成/迭代不刷新（与 prdProducedAt 的随写刷新相对）
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);
        project.markGenerated();
        var first = project.getGeneratedAt();

        awaitMillis(5); // 隔开时钟精度，再置位若误刷新必可辨
        project.markGenerated();

        assertThat(project.getGeneratedAt()).isEqualTo(first);
    }

    private static void awaitMillis(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void given_llm_name_when_rename_then_name_changed() {
        // #39：LLM 取名完成落位（占位 → 生成名）；改名端点（#43）复用同一行为
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, 1L, null);

        project.rename("品牌官网");

        assertThat(project.getName()).isEqualTo("品牌官网");
    }

    @Test
    void given_blank_name_when_rename_then_domain_error() {
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, 1L, null);

        assertThatThrownBy(() -> project.rename(" "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NAME_BLANK.message());
    }

    @Test
    void given_placeholder_name_when_rename_if_placeholder_then_renamed_and_true() {
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, 1L, null);

        boolean renamed = project.renameIfPlaceholder("品牌官网");

        assertThat(renamed).isTrue(); // 占位守卫放行（LLM 取名落位）
        assertThat(project.getName()).isEqualTo("品牌官网");
    }

    @Test
    void given_user_renamed_name_when_rename_if_placeholder_then_kept_and_false() {
        // 取名在飞时用户已改名（#43）→ 不覆写（守卫是聚合规则，非编排判断）
        Project project = Project.create(Project.PLACEHOLDER_NAME, null, 1L, null);
        project.rename("我起的名字");

        boolean renamed = project.renameIfPlaceholder("LLM 的名字");

        assertThat(renamed).isFalse();
        assertThat(project.getName()).isEqualTo("我起的名字");
    }

    @Test
    void given_unarchived_when_archive_then_archived_at_set() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);

        project.archive();

        assertThat(project.getArchivedAt()).isNotNull(); // 单向终点落定
    }

    @Test
    void given_archived_when_archive_again_then_domain_error() {
        Project project = Project.create("官网 demo", ProjectType.WEBSITE, 1L, null);
        project.archive();

        assertThatThrownBy(project::archive)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
    }

    @Test
    void given_new_project_when_create_then_endpoint_defaults_system() {
        Project project = Project.create("官网 demo", null, 1L, null);

        // 入口面（门面票）落地前一律缺省系统；终点类型随建即有（NOT NULL 列语义）
        assertThat(project.getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM);
        assertThat(project.designScope()).isNull(); // 系统终点无设计范围
    }

    @Test
    void given_system_project_when_switch_to_system_design_then_scope_persisted() {
        Project project = Project.create("官网 demo", null, 1L, null);

        project.switchEndpoint(ProjectEndpointType.SYSTEM_DESIGN,
                DesignScope.selected(List.of("首页：展示产品与入口", "订单管理：下单与查看订单")));

        assertThat(project.getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM_DESIGN);
        assertThat(project.designScope().type()).isEqualTo(DesignScopeType.SELECTED_PAGES);
        assertThat(project.designScope().pages()).containsExactly(
                "首页：展示产品与入口", "订单管理：下单与查看订单");
    }

    @Test
    void given_system_project_when_switch_to_design_then_scope_not_persisted() {
        // 设计主线的计划对应物＝PRD 设计物清单章——范围不落库（防两处正本），
        // 作用域只作为切换指令的输入由编排消费（编排对设计目标传 null 进聚合）
        Project project = Project.create("官网 demo", null, 1L, null);

        project.switchEndpoint(ProjectEndpointType.DESIGN, null);

        assertThat(project.getEndpointType()).isEqualTo(ProjectEndpointType.DESIGN);
        assertThat(project.designScope()).isNull();
    }

    @Test
    void given_design_project_when_switch_to_system_design_without_scope_then_allowed() {
        // 设计主线出身（转系统开发）：不由功能清单页面锚定，范围留空合法
        Project project = Project.create("海报设计", null, 1L, null);
        project.switchEndpoint(ProjectEndpointType.DESIGN, null);

        project.switchEndpoint(ProjectEndpointType.SYSTEM_DESIGN, null);

        assertThat(project.getEndpointType()).isEqualTo(ProjectEndpointType.SYSTEM_DESIGN);
        assertThat(project.designScope()).isNull();
    }

    @Test
    void given_scope_pages_when_selected_with_blanks_then_rejected() {
        // 空白剔除后为空集即拒绝——不做设计不是部分设计（Arrays.asList 容 null 元素）
        assertThatThrownBy(() -> DesignScope.selected(java.util.Arrays.asList(" ", null, "")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_SCOPE_PAGES_REQUIRED.message());
    }

    @Test
    void given_design_scope_when_describe_then_range_sentence() {
        assertThat(DesignScope.allPages().describe()).isEqualTo("全部页面");
        assertThat(DesignScope.selected(List.of("首页", "订单页")).describe())
                .isEqualTo("勾选页面（功能清单）：首页、订单页");
    }

    @Test
    void given_non_design_target_when_switch_with_scope_then_domain_error() {
        // 范围只随系统＋设计落库——设计与系统目标携带范围即命令不完整
        Project project = Project.create("官网 demo", null, 1L, null);

        assertThatThrownBy(() -> project.switchEndpoint(ProjectEndpointType.SYSTEM,
                DesignScope.allPages()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_FIELDS_INCOMPLETE.message());
    }
}
