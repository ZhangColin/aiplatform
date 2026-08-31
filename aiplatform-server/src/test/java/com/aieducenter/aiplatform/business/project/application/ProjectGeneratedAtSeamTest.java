package com.aieducenter.aiplatform.business.project.application;

import java.sql.Timestamp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code generated_at} 持久化缝测试（#18 验收）：首次生成时点的单向置位语义在
 * 库内真实行为上验证——置位、持久化、再置位不刷新（聚合置位语义的单测见
 * {@code ProjectTest}）。生成编排（何时调用 markGenerated）归生成环（#22）接线。
 */
@SpringBootTest
class ProjectGeneratedAtSeamTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long projectId;

    @AfterEach
    void tearDown() {
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
    }

    @Test
    void given_new_project_when_saved_then_generated_at_null_in_db() {
        Project project = persisted();

        assertThat(project.getGeneratedAt()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?", Timestamp.class,
                project.getId())).isNull(); // 列在、值为空（未生成过）
    }

    @Test
    void given_markGenerated_when_saved_then_timestamp_persisted() {
        Project project = persisted();
        project.markGenerated();
        projectRepository.save(project);

        assertThat(project.getGeneratedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?", Timestamp.class,
                project.getId())).isNotNull();
    }

    @Test
    void given_generated_when_mark_again_after_reload_then_timestamp_kept_in_db() {
        // 单向置位跨持久化边界成立：重载后再置位不刷新（迭代不重置首次生成时点）
        Project project = persisted();
        project.markGenerated();
        projectRepository.save(project);
        Timestamp first = jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?", Timestamp.class,
                project.getId());

        Project reloaded = projectRepository.findById(project.getId()).orElseThrow();
        reloaded.markGenerated();
        projectRepository.save(reloaded);

        assertThat(reloaded.getGeneratedAt()).isEqualTo(first.toLocalDateTime());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?", Timestamp.class,
                project.getId())).isEqualTo(first);
    }

    private Project persisted() {
        Project project = projectRepository.save(
                Project.create("生成时点缝测试", ProjectType.WEBSITE, 900200L, null));
        projectId = project.getId();
        return project;
    }
}
