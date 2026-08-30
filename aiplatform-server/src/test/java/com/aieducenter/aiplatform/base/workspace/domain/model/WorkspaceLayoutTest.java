package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作区布局常量表的约定断言（#15 布局定盘）：根路径、布局、.env 唯一注入通道、
 * 会话寻址分层、可重建性断言五条约定以本表为正本——物理断言见
 * {@code DockerEnvironmentBackendTest}（真实容器内骨架落位）。
 */
class WorkspaceLayoutTest {

    @Test
    void given_layout_constants_when_inspect_then_all_relative_paths_under_root() {
        // 根是唯一绝对锚点；布局条目一律工作区锚定形（相对根，无 ..），供文件面与 init 骨架共用
        assertThat(WorkspaceLayout.ROOT).isEqualTo("/workspace");
        List.of(WorkspaceLayout.AGENTS_MD, WorkspaceLayout.PRD, WorkspaceLayout.ENV_FILE,
                        WorkspaceLayout.DOCS_DIR, WorkspaceLayout.DATA_DIR, WorkspaceLayout.PG_DATA_DIR,
                        WorkspaceLayout.PLATFORM_DIR, WorkspaceLayout.SKILLS_DIR,
                        WorkspaceLayout.RULES_DIR, WorkspaceLayout.SESSIONS_DIR,
                        WorkspaceLayout.LOGS_DIR)
                .forEach(path -> {
                    assertThat(path).doesNotStartWith("/");
                    assertThat(path).doesNotContain("..");
                    assertThat(path).isNotBlank();
                });
    }

    @Test
    void given_layout_constants_when_inspect_then_directory_nesting_pinned() {
        // 布局定盘：AGENTS.md 占根、PRD 在 docs、pg 数据在 data、平台产物四目录在 .platform
        assertThat(WorkspaceLayout.PRD).startsWith(WorkspaceLayout.DOCS_DIR + "/");
        assertThat(WorkspaceLayout.PG_DATA_DIR).startsWith(WorkspaceLayout.DATA_DIR + "/");
        List.of(WorkspaceLayout.SKILLS_DIR, WorkspaceLayout.RULES_DIR,
                        WorkspaceLayout.SESSIONS_DIR, WorkspaceLayout.LOGS_DIR)
                .forEach(dir -> assertThat(dir).startsWith(WorkspaceLayout.PLATFORM_DIR + "/"));
        // AGENTS.md 与 .env 都是根级文件，不在任何目录约定之下
        assertThat(WorkspaceLayout.AGENTS_MD).doesNotContain("/");
        assertThat(WorkspaceLayout.ENV_FILE).doesNotContain("/");
    }

    @Test
    void given_session_addressing_when_inspect_then_engine_data_redirected_into_platform_dir() {
        // 会话寻址分层：引擎侧会话数据经 XDG_DATA_HOME 重定向落 .platform（平台侧会话在
        // 库表 cat_agent_state，不经文件面）——重定向目标是平台产物目录本身
        assertThat(WorkspaceLayout.XDG_DATA_HOME).isEqualTo(WorkspaceLayout.PLATFORM_DIR);
    }

    @Test
    void given_skeleton_when_inspect_then_covers_pinned_directories_only() {
        // init 落位骨架 = 布局中的全部目录（应用代码占根无目录、AGENTS.md 内容归生成环）
        assertThat(WorkspaceLayout.SKELETON_DIRS).containsExactlyInAnyOrder(
                WorkspaceLayout.DOCS_DIR, WorkspaceLayout.PG_DATA_DIR,
                WorkspaceLayout.SKILLS_DIR, WorkspaceLayout.RULES_DIR,
                WorkspaceLayout.SESSIONS_DIR, WorkspaceLayout.LOGS_DIR);
    }

    @Test
    void given_relative_path_when_absolute_then_anchored_under_root() {
        assertThat(WorkspaceLayout.absolute(WorkspaceLayout.PRD))
                .isEqualTo("/workspace/docs/PRD.md");
        assertThat(WorkspaceLayout.absolute(".")).isEqualTo("/workspace");
    }

    @Test
    void given_absolute_or_escaping_path_when_absolute_then_rejected() {
        assertThatThrownBy(() -> WorkspaceLayout.absolute("/docs/PRD.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceLayout.absolute("docs/../.env"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
