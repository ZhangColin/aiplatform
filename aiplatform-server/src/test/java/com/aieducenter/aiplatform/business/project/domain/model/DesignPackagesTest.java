package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DesignPackages} 纯函数单测（#297 设计资产包的规则单点）：选件矩阵
 * （灵魂用例＝内容集正本：选定稿双形态＋规范＋衍生入、落选稿结构性不入）、
 * 命令正本（选件式 tar／系统＋设计统一容器／落名）、路径派生、清单解析。
 */
class DesignPackagesTest {

    @Test
    void given_finalized_and_listing_when_selection_then_content_set_without_unselected() {
        // 灵魂用例：内容集＝选定稿自包含形态（html+png 双形态）＋衍生；落选稿/侧车不入
        List<String> selection = DesignPackages.selectionOf(
                List.of("/design/logo.png", "/design/hero.html"),
                List.of(
                        "design/logo.html", "design/logo.png", "design/logo.spec.json",
                        "design/hero.html", "design/hero-v2.html",
                        "exports/hero-1280x800.png", "exports/logo-990x600.png"));

        assertThat(selection).containsExactly(
                "design/logo.html", "design/logo.png", // 平面类：源＋位图双形态同存
                "design/hero.html", "exports/hero-1280x800.png"); // 界面类：稿＋帧衍生
    }

    @Test
    void given_same_inputs_when_selection_then_deterministic_order_by_item_sequence() {
        // 确定性＝同输入同产出：件序驱动成员序（与清单输入序无关）、同词干内字典序
        List<String> first = DesignPackages.selectionOf(
                List.of("/design/b.png", "/design/a.html"),
                List.of("design/b.html", "design/b.png", "design/a.html"));
        List<String> second = DesignPackages.selectionOf(
                List.of("/design/b.png", "/design/a.html"),
                List.of("design/a.html", "design/b.png", "design/b.html"));

        assertThat(first).containsExactly("design/b.html", "design/b.png", "design/a.html");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void given_no_matching_files_or_empty_when_selection_then_empty_or_absent_only() {
        // 无定稿＝零选件；清单空＝只有定稿锚也选不出成员（容器里没有的事实面）
        assertThat(DesignPackages.selectionOf(List.of(), List.of("design/x.html"))).isEmpty();
        assertThat(DesignPackages.selectionOf(List.of("/design/x.html"), List.of())).isEmpty();
        // 定稿锚词干在、清单缺帧衍生：衍生如实缺席
        assertThat(DesignPackages.selectionOf(List.of("/design/x.html"), List.of("design/x.html")))
                .containsExactly("design/x.html");
    }

    @Test
    void given_derivative_naming_when_frame_png_then_export_anchored_with_frame_suffix() {
        assertThat(DesignPackages.framePngOf("hero")).isEqualTo("exports/hero-1280x800.png");
        assertThat(DesignPackages.packagePathOf(123L))
                .isEqualTo("exports/design-package-123.tar.gz");
        assertThat(DesignPackages.stagingPathOf(456L))
                .isEqualTo("exports/.design-package-456.staging.tar.gz");
    }

    @Test
    void given_design_only_when_tar_command_then_selection_based_without_tree() {
        // 设计单命令正本：纯选件面（无整树、无排除），-T /dev/null＝空选件基线
        assertThat(DesignPackages.tarCommand("exports/.design-package-1.staging.tar.gz",
                List.of("DESIGN.md", "design/a.html"), false))
                .isEqualTo("mkdir -p '/workspace/exports'"
                        + " && tar czf '/workspace/exports/.design-package-1.staging.tar.gz'"
                        + " -C /workspace -T /dev/null './DESIGN.md' './design/a.html'");
    }

    @Test
    void given_system_design_when_tar_command_then_unified_container_find_pruned_tree() {
        // 系统＋设计单命令正本：源码部件面＝find 剪枝整树（源码包同款非交付名单＋
        // 设计线两目录＋机密）NUL 分隔喂 tar；设计部件以显式成员追加——统一部件
        // 容器、目录即类型边界；exports 兼自排除（暂存件不进包）。不走 tar
        // --exclude（祖先目录命中会连坐剪掉显式设计成员，活体实测）
        String command = DesignPackages.tarCommand("exports/.design-package-1.staging.tar.gz",
                List.of("design/a.html", "exports/a-1280x800.png"), true);
        assertThat(command)
                .isEqualTo("mkdir -p '/workspace/exports'"
                        + " && cd /workspace && find . \\( -path ./node_modules"
                        + " -o -path ./.pnpm-store -o -path ./.next -o -path ./data"
                        + " -o -path ./.platform -o -path ./agents -o -path ./external"
                        + " -o -path ./.git -o -path ./design -o -path ./exports \\)"
                        + " -prune -o -type f ! -path ./.env -printf './%P\\0'"
                        + " | tar czf '/workspace/exports/.design-package-1.staging.tar.gz'"
                        + " --null -T - './design/a.html' './exports/a-1280x800.png'");
    }

    @Test
    void given_paths_with_special_chars_when_tar_command_then_quoted_no_injection() {
        // 成员名经单引号包裹＋转义（稿名可含空格/引号——设计执行体产物非受控词面）
        assertThat(DesignPackages.tarCommand("exports/.design-package-1.staging.tar.gz",
                List.of("design/首屏 '改'.html"), false))
                .contains("'./design/首屏 '\\''改'\\''.html'");
    }

    @Test
    void given_seal_when_command_then_atomic_rename() {
        assertThat(DesignPackages.sealCommand("exports/.design-package-1.staging.tar.gz",
                "exports/design-package-99.tar.gz"))
                .isEqualTo("mv -f '/workspace/exports/.design-package-1.staging.tar.gz'"
                        + " '/workspace/exports/design-package-99.tar.gz'");
    }

    @Test
    void given_listing_output_when_parse_then_relative_form_only() {
        assertThat(DesignPackages.listedRelativeOf(
                "/workspace/design/a.html\n/workspace/exports/b-1280x800.png\n碎行\n"))
                .containsExactly("design/a.html", "exports/b-1280x800.png");
    }

    @Test
    void given_listing_command_when_built_then_both_dirs_flat_files() {
        assertThat(DesignPackages.listingCommand())
                .isEqualTo("find /workspace/design /workspace/exports"
                        + " -maxdepth 1 -type f 2>/dev/null || true");
    }
}
