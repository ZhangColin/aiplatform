package com.aieducenter.aiplatform.base.skills.endpoints.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 技能库两端点（#247 读面＋#248 写口）在 {@code #152} seam 上全绿：真过滤链
 * （签名闸/会话豁免/强制拦截器）＋真应用服务＋aiplatform_test 真库。#247 期库
 * 条目夹具经 JdbcTemplate 直插（当时无写口）；#248 起写口走真链路——安装用本地
 * fixture git 仓库（{@code git} CLI 现建、不依赖外网，#248 验收面），teardown
 * 按种植 id 与安装来源包双重清理。
 *
 * <p>#247 四面钉死：</p>
 * <ul>
 * <li><b>清单</b>：空库仍呈现内置技能（classpath 合成，来源＝内置、来源包/版本
 * 标识 null、状态恒启用）＋库行同权（来源＝安装，名称/简介/来源包/版本标识/状态
 * 逐字段）＋排序（内置在前名称序、安装在后来源包名称序——跨包同名共存区分呈现）
 * ＋技能柄两形制（builtin:&lt;技能名&gt;／TSID 十进制串）；</li>
 * <li><b>详情</b>：frontmatter 全量（解析态键值）＋正文全文（frontmatter
 * 剥离后）——内置与安装两出处各自可读（审核面所见即注入面）；</li>
 * <li><b>404</b>：未寻址内置名/TSID、畸形柄同语义 404 SKL_001（信封数字码 7001）；</li>
 * <li><b>鉴权</b>：非签名请求被拒（401，后台鉴权惯例）。</li>
 * </ul>
 *
 * <p>#248 写口六面钉死：</p>
 * <ul>
 * <li><b>安装</b>：本地 fixture 仓库快照固化——全部 SKILL.md 入库（来源包＝
 * 规范化地址、版本＝装时 HEAD commit 留痕、排除目录段不入库）＋操作者＝装者
 * 落痕＋回执清单行同形；</li>
 * <li><b>安装失败族</b>：同源重复安装 409 SKL_003（尾斜杠变体同源）＋克隆失败
 * 502 SKL_004＋零技能（空仓库/全部排除）400 SKL_005＋SKILL.md 不合格 400
 * SKL_006（整体不入库——fail-fast）＋地址空 400 SKL_002＋缺操作者 400
 * SKL_009；</li>
 * <li><b>启停</b>：状态翻转且清单可见＋幂等（操作者留最近一次）＋操作者两列
 * 落痕＋缺操作者 400 SKL_009＋内置柄 404（内置非库行无状态迁移）；</li>
 * <li><b>卸载</b>：无指派成功（回执＝删除前终态、清单即不可见、重复卸载 404）
 * ＋内置柄 404——有指派拒绝 SKL_008 定码待 T3 指派表落地接真检查（结构上暂无
 * 指派可查，错误码契约先冻结）；</li>
 * <li><b>鉴权</b>：写口非签名请求被拒（401）。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeSkillSeamTest {

    /** 信封数字业务码：SKL 域码 7 × 1000＋序号（SKL_001～SKL_009）。 */
    private static final int SKILL_NOT_FOUND_CODE = 7001;
    private static final int SKILL_INSTALL_URL_REQUIRED_CODE = 7002;
    private static final int SKILL_SOURCE_ALREADY_INSTALLED_CODE = 7003;
    private static final int SKILL_REPOSITORY_CLONE_FAILED_CODE = 7004;
    private static final int SKILL_NO_SKILLS_PARSED_CODE = 7005;
    private static final int SKILL_MD_INVALID_CODE = 7006;
    private static final int SKILL_OPERATOR_REQUIRED_CODE = 7009;

    /** 操作者透传头样例（admin 侧管理员，签名面明示信任）。 */
    private static final String OPERATOR_ID = "700200";
    private static final String OPERATOR_NAME = "运营·技能管理员";
    private static final String OPERATOR_ID_2 = "700201";
    private static final String OPERATOR_NAME_2 = "运营·小刘";

    /** 主 fixture 仓库：engineering 类目两技能＋deprecated 类目一技能（排除面）。 */
    private static Path fixtureRepo;
    private static String fixtureHeadSha;

    /** 负例仓库：SKILL.md 缺 description（畸形）/ 无任何 SKILL.md（零技能）。 */
    private static Path malformedRepo;
    private static Path emptyRepo;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 已种植库条目 id（teardown 精确清理 skl_skills）。 */
    private final List<Long> plantedSkillIds = new ArrayList<>();

    @BeforeAll
    static void createFixtureRepos() throws Exception {
        fixtureRepo = buildRepo("skill-fixture", repo -> {
            writeSkill(repo, "engineering/code-review", "code-review",
                    "双轴并行审查——规范轴与需求轴互为对照。", "先对规范、再对需求，两轴结论合并呈报。");
            writeSkill(repo, "engineering/tdd", "tdd",
                    "测试先行红绿重构。", "红绿重构循环——先写失败测试，再最小实现，最后重构。");
            writeSkill(repo, "deprecated/legacy-flow", "legacy-flow",
                    "已废弃的旧流程技能。", "此技能应被安装排除段挡在库外。");
        });
        fixtureHeadSha = git(fixtureRepo, "rev-parse", "HEAD").trim();
        malformedRepo = buildRepo("skill-malformed", repo ->
                writeSkillMd(repo, "half-baked/half-baked",
                        "---\nname: half-baked\n---\n\n正文齐全但 description 缺失。\n"));
        emptyRepo = buildRepo("skill-empty", repo -> {
            try {
                Files.writeString(repo.resolve("README.md"), "无任何 SKILL.md 的空仓库\n");
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @AfterEach
    void tearDown() {
        for (Long id : plantedSkillIds) {
            jdbcTemplate.update("DELETE FROM skl_skills WHERE id = ?", id);
        }
        plantedSkillIds.clear();
        // 安装产物按来源包清理（本地 fixture 路径即规范化来源包身份）
        for (Path repo : List.of(fixtureRepo, malformedRepo, emptyRepo)) {
            jdbcTemplate.update("DELETE FROM skl_skills WHERE source_package = ?", repo.toString());
        }
    }

    // ---------- 清单：空库内置合成＋库行同权＋排序＋柄两形制 ----------

    @Test
    void given_empty_library_when_signed_list_then_builtin_skills_present() throws Exception {
        // 空库非空清单：classpath 内置技能合成进清单（#247 验收面）
        signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value("builtin:prd-writing"))
                .andExpect(jsonPath("$.data[0].name").value("prd-writing"))
                .andExpect(jsonPath("$.data[0].description").value(
                        "撰写或修订 PRD 时使用——固定七章节模板与平实写法规范。"))
                .andExpect(jsonPath("$.data[0].source").value(1))
                .andExpect(jsonPath("$.data[0].sourceName").value("内置"))
                .andExpect(jsonPath("$.data[0].sourcePackage").value(nullValue()))
                .andExpect(jsonPath("$.data[0].version").value(nullValue()))
                .andExpect(jsonPath("$.data[0].status").value(1))
                .andExpect(jsonPath("$.data[0].statusName").value("启用"));
    }

    @Test
    void given_installed_rows_when_signed_list_then_merged_and_ordered() throws Exception {
        // 跨包同名两行＋停用一行：内置在前（名称序）、安装在后（来源包、名称序）
        long mattTdd = plant(76_000_001L, "tdd", "matt 方法论包", "commit-a1", 1);
        long mattReview = plant(76_000_002L, "review", "matt 方法论包", "commit-a1", 2);
        long superTdd = plant(76_000_003L, "tdd", "superpowers 包", "commit-b2", 1);

        signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                // 内置在前
                .andExpect(jsonPath("$.data[0].id").value("builtin:prd-writing"))
                // 安装按来源包、名称序：matt 方法论包(review, tdd) → superpowers 包(tdd)
                .andExpect(jsonPath("$.data[1].id").value(Long.toString(mattReview)))
                .andExpect(jsonPath("$.data[1].name").value("review"))
                // 停用行如实呈现状态
                .andExpect(jsonPath("$.data[1].status").value(2))
                .andExpect(jsonPath("$.data[1].statusName").value("停用"))
                .andExpect(jsonPath("$.data[1].sourcePackage").value("matt 方法论包"))
                .andExpect(jsonPath("$.data[2].id").value(Long.toString(mattTdd)))
                .andExpect(jsonPath("$.data[2].name").value("tdd"))
                .andExpect(jsonPath("$.data[2].description").value("测试先行红绿重构"))
                .andExpect(jsonPath("$.data[2].source").value(2))
                .andExpect(jsonPath("$.data[2].sourceName").value("安装"))
                .andExpect(jsonPath("$.data[2].sourcePackage").value("matt 方法论包"))
                .andExpect(jsonPath("$.data[2].version").value("commit-a1"))
                .andExpect(jsonPath("$.data[2].status").value(1))
                .andExpect(jsonPath("$.data[2].statusName").value("启用"))
                // 跨包同名共存（区分呈现靠来源包）
                .andExpect(jsonPath("$.data[3].id").value(Long.toString(superTdd)))
                .andExpect(jsonPath("$.data[3].name").value("tdd"))
                .andExpect(jsonPath("$.data[3].sourcePackage").value("superpowers 包"));
    }

    // ---------- 详情：frontmatter＋正文全文可读（审核面） ----------

    @Test
    void given_builtin_skill_when_signed_detail_then_frontmatter_and_content_readable()
            throws Exception {
        signedGet("/api/backoffice/skills/builtin:prd-writing")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("builtin:prd-writing"))
                .andExpect(jsonPath("$.data.name").value("prd-writing"))
                .andExpect(jsonPath("$.data.source").value(1))
                .andExpect(jsonPath("$.data.sourceName").value("内置"))
                .andExpect(jsonPath("$.data.sourcePackage").value(nullValue()))
                .andExpect(jsonPath("$.data.version").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value(1))
                // frontmatter 全量：解析态键值（含 name/description）
                .andExpect(jsonPath("$.data.frontmatter.name").value("prd-writing"))
                .andExpect(jsonPath("$.data.frontmatter.description").value(
                        "撰写或修订 PRD 时使用——固定七章节模板与平实写法规范。"))
                // 正文＝frontmatter 剥离后全文（不以 --- 围栏开头，直落标题）
                .andExpect(jsonPath("$.data.content").value(startsWith("# PRD 写作技能")))
                .andExpect(jsonPath("$.data.content").value(containsString("七章节")));
    }

    @Test
    void given_installed_row_when_signed_detail_then_fulltext_from_store() throws Exception {
        long id = plant(76_000_011L, "tdd", "matt 方法论包", "commit-a1", 1,
                "{\"name\": \"tdd\", \"description\": \"测试先行红绿重构\", \"license\": \"MIT\"}",
                "红绿重构循环：先写失败测试，再最小实现，最后重构。");

        signedGet("/api/backoffice/skills/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.name").value("tdd"))
                .andExpect(jsonPath("$.data.description").value("测试先行红绿重构"))
                .andExpect(jsonPath("$.data.source").value(2))
                .andExpect(jsonPath("$.data.sourceName").value("安装"))
                .andExpect(jsonPath("$.data.sourcePackage").value("matt 方法论包"))
                .andExpect(jsonPath("$.data.version").value("commit-a1"))
                .andExpect(jsonPath("$.data.status").value(1))
                // frontmatter 全量直读 JSONB（含 name/description 之外的键）
                .andExpect(jsonPath("$.data.frontmatter.name").value("tdd"))
                .andExpect(jsonPath("$.data.frontmatter.license").value("MIT"))
                .andExpect(jsonPath("$.data.content").value(
                        "红绿重构循环：先写失败测试，再最小实现，最后重构。"));

        // 404 三形：未寻址 TSID、未寻址内置名、畸形柄——同语义 SKL_001
        signedGet("/api/backoffice/skills/999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE))
                .andExpect(jsonPath("$.message").value("技能不存在"));
        signedGet("/api/backoffice/skills/builtin:nosuch-skill")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
        signedGet("/api/backoffice/skills/not-a-tsid")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
    }

    // ---------- 安装：快照固化＋版本留痕＋排除段＋操作者落痕 ----------

    @Test
    void given_local_fixture_repo_when_signed_install_then_snapshot_persisted_with_version_trace()
            throws Exception {
        signedInstall(fixtureRepo.toString(), OPERATOR_ID, OPERATOR_NAME, "deprecated")
                .andExpect(status().isOk())
                // 回执＝本次装入条目（路径序稳定：code-review → tdd），清单行同形
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("code-review"))
                .andExpect(jsonPath("$.data[0].description").value("双轴并行审查——规范轴与需求轴互为对照。"))
                .andExpect(jsonPath("$.data[0].source").value(2))
                .andExpect(jsonPath("$.data[0].sourceName").value("安装"))
                .andExpect(jsonPath("$.data[0].sourcePackage").value(fixtureRepo.toString()))
                // 版本标识＝装时 HEAD commit（快照锚，逐字符等于仓库当前 HEAD）
                .andExpect(jsonPath("$.data[0].version").value(fixtureHeadSha))
                .andExpect(jsonPath("$.data[0].status").value(1))
                .andExpect(jsonPath("$.data[0].statusName").value("启用"))
                // 操作者＝装者（回执与库列同源）
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data[0].operatorName").value(OPERATOR_NAME))
                .andExpect(jsonPath("$.data[1].name").value("tdd"));

        // 清单可见：内置在前＋安装两行（同包名称序），deprecated 排除段不入库
        signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id").value("builtin:prd-writing"))
                .andExpect(jsonPath("$.data[1].name").value("code-review"))
                .andExpect(jsonPath("$.data[1].version").value(fixtureHeadSha))
                .andExpect(jsonPath("$.data[2].name").value("tdd"))
                .andExpect(jsonPath("$.data[2].sourcePackage").value(fixtureRepo.toString()));

        // 排除面整链钉死：deprecated 技能无行（库列直查）
        assertThatNoRows("legacy-flow");
    }

    @Test
    void given_installed_source_when_signed_install_again_then_409_same_source_rejected()
            throws Exception {
        signedInstall(fixtureRepo.toString(), OPERATOR_ID, OPERATOR_NAME, "deprecated")
                .andExpect(status().isOk());

        // 原样重复：409 SKL_003（更新走显式更新动作，另票——语义写进消息）
        signedInstall(fixtureRepo.toString(), OPERATOR_ID, OPERATOR_NAME, "deprecated")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SKILL_SOURCE_ALREADY_INSTALLED_CODE))
                .andExpect(jsonPath("$.message").value(
                        "该技能仓库已安装过，不能重复安装（同源去重，更新走显式更新）"));

        // 尾斜杠变体同源（规范化地址身份）：一样 409，且库行数不涨
        signedInstall(fixtureRepo + "/", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SKILL_SOURCE_ALREADY_INSTALLED_CODE));
        assertThatRowCount(2);
    }

    @Test
    void given_repos_failing_parse_when_signed_install_then_400_family_and_nothing_persisted()
            throws Exception {
        // SKILL.md 缺 description：400 SKL_006，fail-fast 整体不入库
        signedInstall(malformedRepo.toString(), OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_MD_INVALID_CODE))
                .andExpect(jsonPath("$.message").value(
                        "仓库内 SKILL.md 不合格（须含 name、description 与正文）"));
        assertThatRowCount(0);

        // 空仓库（无 SKILL.md）：400 SKL_005
        signedInstall(emptyRepo.toString(), OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_NO_SKILLS_PARSED_CODE));
        assertThatRowCount(0);

        // 全部技能被排除：同落零技能语义 400 SKL_005
        signedInstall(fixtureRepo.toString(), OPERATOR_ID, OPERATOR_NAME,
                "engineering", "deprecated")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_NO_SKILLS_PARSED_CODE));
        assertThatRowCount(0);
    }

    @Test
    void given_unreachable_repo_when_signed_install_then_502_clone_failed() throws Exception {
        signedInstall("/nonexistent/skill/repo-xyz", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(SKILL_REPOSITORY_CLONE_FAILED_CODE))
                .andExpect(jsonPath("$.message").value("技能仓库克隆失败"));
    }

    @Test
    void given_blank_url_or_missing_operator_when_signed_install_then_400() throws Exception {
        // 地址空：400 SKL_002（校验先于操作者守卫——地址是请求自身缺陷）
        signedInstall("", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_INSTALL_URL_REQUIRED_CODE))
                .andExpect(jsonPath("$.message").value("安装仓库地址不能为空"));
        // 缺操作者透传头（地址有效）：400 SKL_009（写操作必留痕，无落空通道）
        signedInstall(fixtureRepo.toString(), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_OPERATOR_REQUIRED_CODE))
                .andExpect(jsonPath("$.message").value("操作者不能为空"));
        assertThatRowCount(0);
    }

    // ---------- 启停：状态翻转清单可见＋幂等＋操作者留最近一次 ----------

    @Test
    void given_installed_skill_when_signed_disable_then_flips_visible_then_enable_restores()
            throws Exception {
        String tddId = installFixtureAndReturnId("tdd");

        // 停用：状态翻转＋操作者落痕（回执与库列双面）
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/disable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(tddId))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusName").value("停用"))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));
        assertThatOperatorColumns(tddId, 2, OPERATOR_ID, OPERATOR_NAME);

        // 清单可见（#248 验收面）：停用行如实呈现、不丢
        signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].id").value(tddId))
                .andExpect(jsonPath("$.data[2].status").value(2))
                .andExpect(jsonPath("$.data[2].statusName").value("停用"));

        // 重复停用幂等：200、操作者留最近一次
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/disable", OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2));
        assertThatOperatorColumns(tddId, 2, OPERATOR_ID_2, OPERATOR_NAME_2);

        // 启用恢复：状态回 1、操作者同样留最近动作者
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/enable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("启用"));
        assertThatOperatorColumns(tddId, 1, OPERATOR_ID, OPERATOR_NAME);

        // 缺操作者头：400 SKL_009；内置柄/未寻址 TSID：404 SKL_001（内置非库行
        // 无状态迁移——写口只寻址库行，畸形柄同语义）
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/disable", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_OPERATOR_REQUIRED_CODE));
        signedPostNoBody("/api/backoffice/skills/builtin:prd-writing/disable",
                OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
        signedPostNoBody("/api/backoffice/skills/999999999/enable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
    }

    // ---------- 卸载：无指派成功＋回执终态＋守卫错误码定契约待 T3 ----------

    @Test
    void given_unassigned_skill_when_signed_uninstall_then_deleted_with_receipt() throws Exception {
        String codeReviewId = installFixtureAndReturnId("code-review");

        // 卸载成功：回执＝删除前终态（确认移除了什么——清单行同形）
        signedDelete("/api/backoffice/skills/" + codeReviewId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(codeReviewId))
                .andExpect(jsonPath("$.data.name").value("code-review"))
                .andExpect(jsonPath("$.data.sourcePackage").value(fixtureRepo.toString()))
                .andExpect(jsonPath("$.data.version").value(fixtureHeadSha))
                .andExpect(jsonPath("$.data.status").value(1));

        // 清单即不可见（同包另一技能仍在）；重复卸载 404
        signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[1].name").value("tdd"));
        signedDelete("/api/backoffice/skills/" + codeReviewId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));

        // 内置技能不可卸（builtin 非库行）：404 SKL_001 同语义
        signedDelete("/api/backoffice/skills/builtin:prd-writing")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
        // 有指派在身拒绝卸载（SKL_008）：契约先冻结——指派表 T3/#249 落地后接真
        // 检查，彼时补拒绝分支断言；当前结构上无指派，卸载恒过守卫位
    }

    // ---------- 鉴权：写口非签名被拒 ----------

    @Test
    void given_no_signature_headers_when_post_install_then_401_signature_required() throws Exception {
        mockMvc.perform(post("/api/backoffice/skills/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    @Test
    void given_no_signature_headers_when_get_skills_then_401_signature_required() throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/skills"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 夹具 ----------

    /** 种植库条目（#247 读面夹具；description 与 frontmatter 同源）。 */
    private long plant(long id, String name, String sourcePackage, String version, int status) {
        return plant(id, name, sourcePackage, version, status,
                "{\"name\": \"" + name + "\", \"description\": \"测试先行红绿重构\"}",
                "正文占位");
    }

    private long plant(long id, String name, String sourcePackage, String version, int status,
            String frontmatterJson, String content) {
        plantedSkillIds.add(id);
        jdbcTemplate.update("""
                INSERT INTO skl_skills
                    (id, name, description, source_package, version, status, frontmatter, content)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                id, name, "测试先行红绿重构", sourcePackage, version, status,
                frontmatterJson, content);
        return id;
    }

    /** 本地 fixture git 仓库现建（git CLI，不依赖外网）：init → 落文件 → 单 commit。 */
    private static Path buildRepo(String name, DirectoryPopulator populator) throws Exception {
        Path repo = Files.createTempDirectory(name + "-");
        git(repo, "-c", "init.defaultBranch=main", "init", "-q");
        populator.populate(repo);
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=Skill Fixture", "-c", "user.email=fixture@aiplatform.local",
                "commit", "-q", "-m", "fixture");
        return repo;
    }

    @FunctionalInterface
    private interface DirectoryPopulator {
        void populate(Path repo) throws Exception;
    }

    private static void writeSkill(Path repo, String dir, String name, String description,
            String body) {
        writeSkillMd(repo, dir, """
                ---
                name: %s
                description: %s
                ---

                %s
                """.formatted(name, description, body));
    }

    private static void writeSkillMd(Path repo, String dir, String skillMd) {
        try {
            Path skillDir = repo.resolve(dir);
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), skillMd);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** git 子进程（夹具侧）：非零退出即夹具损坏，直接炸出。 */
    private static String git(Path workingDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workingDir.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("fixture git 超时: " + command);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("fixture git 失败 (%d): %s — %s".formatted(
                    process.exitValue(), command, new String(stderr, StandardCharsets.UTF_8).trim()));
        }
        return new String(stdout, StandardCharsets.UTF_8);
    }

    /** 走安装真链路装主 fixture（排除 deprecated），回指定技能名的 TSID 柄。 */
    private String installFixtureAndReturnId(String skillName) throws Exception {
        String body = """
                {"repoUrl": "%s", "excludeDirs": ["deprecated"]}""".formatted(fixtureRepo);
        String response = mockMvc.perform(signedInstallBuilder(body, OPERATOR_ID, OPERATOR_NAME))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<String>>read(
                response, "$.data[?(@.name == '" + skillName + "')].id").get(0);
    }

    /** 签名安装 POST：JSON 体＋操作者透传头（null 即缺头负例形制）。 */
    private ResultActions signedInstall(String repoUrl, String operatorId,
            String operatorName, String... excludeDirs) throws Exception {
        StringBuilder excludes = new StringBuilder();
        for (String exclude : excludeDirs) {
            if (!excludes.isEmpty()) {
                excludes.append(", ");
            }
            excludes.append('"').append(exclude).append('"');
        }
        String body = """
                {"repoUrl": "%s", "excludeDirs": [%s]}""".formatted(repoUrl, excludes);
        return mockMvc.perform(signedInstallBuilder(body, operatorId, operatorName));
    }

    private MockHttpServletRequestBuilder signedInstallBuilder(String body, String operatorId,
            String operatorName) {
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                        post("/api/backoffice/skills/install")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/skills/install", body);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return request;
    }

    /** 签名 POST（无体）＋操作者透传头；operatorId 传 null 即缺头负例形制。 */
    private ResultActions signedPostNoBody(String path, String operatorId, String operatorName)
            throws Exception {
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(post(path), path, null);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(request);
    }

    /** 签名 DELETE（无体）。 */
    private ResultActions signedDelete(String path) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(delete(path), path, null));
    }

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null);
        return mockMvc.perform(request);
    }

    // ---------- 库列直查断言 ----------

    /** 按技能名直查无行（排除段未入库的夹具面断言）。 */
    private void assertThatNoRows(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skl_skills WHERE name = ?", Integer.class, name);
        assertThat(count).as("技能 %s 应无库行", name).isZero();
    }

    /** 全表行数（安装失败族 fail-fast 断言：整体不入库）。 */
    private void assertThatRowCount(int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skl_skills", Integer.class);
        assertThat(count).as("skl_skills 行数").isEqualTo(expected);
    }

    /** 行级断言：状态与操作者两列（启停留痕面）。 */
    private void assertThatOperatorColumns(String id, int status, String operatorId,
            String operatorName) {
        var row = jdbcTemplate.queryForMap(
                "SELECT status, operator_id, operator_name FROM skl_skills WHERE id = ?",
                Long.parseLong(id));
        assertThat(row)
                .as("库行状态与操作者留痕")
                .containsEntry("status", status)
                .containsEntry("operator_id", operatorId)
                .containsEntry("operator_name", operatorName);
    }
}
