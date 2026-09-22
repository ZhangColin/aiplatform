package com.aieducenter.aiplatform.base.skills.endpoints.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import com.aieducenter.aiplatform.base.skills.application.BackofficeSkillAppService;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.infrastructure.agentscope.ProfileSkillRepositorySupplier;
import com.aieducenter.aiplatform.business.project.infrastructure.agentscope.ProfileSubagentSupplier;

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
 * ＋内置柄 404——有指派拒绝 SKL_008（#249 指派表落地接真检查）；</li>
 * <li><b>鉴权</b>：写口非签名请求被拒（401）。</li>
 * </ul>
 *
 * <p>#249 槽位指派与装配合成六面钉死：</p>
 * <ul>
 * <li><b>指派读写</b>：GET/PUT 三槽各自独立（整包替换——清单即终态，空清单＝
 * 清空）＋回执与 GET 同形（含停用行实态）；</li>
 * <li><b>负例族</b>：未知槽位 404 SKL_010＋内置柄不可指派 400 SKL_011＋未寻址
 * TSID 404 SKL_001＋缺操作者 400 SKL_009；</li>
 * <li><b>装配合成（技能装配缝真链路）</b>：某槽位装配面＝内置∪该槽位已指派且
 * 启用的库技能（supplier 视图直断言）、他槽位技能不出现；</li>
 * <li><b>动态性</b>：REST 指派/启停变更后同一 supplier 视图重读即反映最新
 * （动态查库、非装配时固化）＋停用即时退出装配候选；</li>
 * <li><b>卸载守卫接真</b>：有指派在身（任一槽位）卸载 409 SKL_008、解绑后可卸；</li>
 * <li><b>鉴权</b>：指派面非签名请求被拒（401）。</li>
 * </ul>
 *
 * <p>#250 更新检查与显式更新五面钉死（fixture 仓库追加 commit 模拟远端前进
 * ——验收面）：</p>
 * <ul>
 * <li><b>检查轮</b>：装后即查（装＝事实检查，标记 false）→ 追加 commit →
 * 扫描（直调应用服务）标记亮（清单 updateAvailable=true）→ 更新后复位 →
 * 再扫描不亮（远端未前进不标）；</li>
 * <li><b>显式更新</b>：重拉快照入库——同名行原地翻新（id/停用状态/指派跨更新
 * 保留）＋新技能插入＋装时排除名单同口径（排除目录不随更新还魂）＋版本留痕
 * 追加（from→to＋操作者，留痕读面可查）＋未前进幂等回执（不落痕）；</li>
 * <li><b>移除守卫</b>：远端删除有指派在身的技能→整体 409 SKL_013，解绑后
 * 更新成功（回执 removedSkillNames 确认移除了什么）；</li>
 * <li><b>静默降级</b>：坏远端检查失败不炸轮、不写状态（标记留 null）、不拖垮
 * 同轮他包；</li>
 * <li><b>负例＋鉴权</b>：未装来源包 404 SKL_012＋空来源包 400 SKL_014＋缺
 * 操作者 400 SKL_009＋非签名 401。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeSkillSeamTest {

    /** 信封数字业务码：SKL 域码 7 × 1000＋序号（SKL_001～SKL_014）。 */
    private static final int SKILL_NOT_FOUND_CODE = 7001;
    private static final int SKILL_INSTALL_URL_REQUIRED_CODE = 7002;
    private static final int SKILL_SOURCE_ALREADY_INSTALLED_CODE = 7003;
    private static final int SKILL_REPOSITORY_CLONE_FAILED_CODE = 7004;
    private static final int SKILL_NO_SKILLS_PARSED_CODE = 7005;
    private static final int SKILL_MD_INVALID_CODE = 7006;
    private static final int SKILL_ASSIGNED_CODE = 7008;
    private static final int SKILL_OPERATOR_REQUIRED_CODE = 7009;
    private static final int SKILL_SLOT_NOT_FOUND_CODE = 7010;
    private static final int SKILL_BUILTIN_NOT_ASSIGNABLE_CODE = 7011;
    private static final int SKILL_SOURCE_NOT_INSTALLED_CODE = 7012;
    private static final int SKILL_UPDATE_REMOVES_ASSIGNED_CODE = 7013;
    private static final int SKILL_SOURCE_PACKAGE_REQUIRED_CODE = 7014;

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

    /** 技能装配缝（#249 缝 2 真链路断言：供应商视图即装配面）。 */
    @Autowired
    private ProfileSkillRepositorySupplier skillRepositorySupplier;

    /** 更新检查扫描轮直调口（#250：定期轮测试可触发——验收面走应用服务）。 */
    @Autowired
    private BackofficeSkillAppService appService;

    /** 已种植库条目 id（teardown 精确清理 skl_skills）。 */
    private final List<Long> plantedSkillIds = new ArrayList<>();

    /** 测试内现建的动态仓库（#250 更新线专用——每测试自持仓库互不串台；teardown 按来源包清）。 */
    private final List<Path> dynamicRepos = new ArrayList<>();

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
        // 指派行先清（FK 挡在条目删除前——守卫的库级兜底同款次序）
        jdbcTemplate.update("DELETE FROM skl_slot_assignments");
        for (Long id : plantedSkillIds) {
            jdbcTemplate.update("DELETE FROM skl_skills WHERE id = ?", id);
        }
        plantedSkillIds.clear();
        // 安装产物按来源包清理（本地 fixture 路径即规范化来源包身份）＋动态仓库
        List<Path> sources = new ArrayList<>(List.of(fixtureRepo, malformedRepo, emptyRepo));
        sources.addAll(dynamicRepos);
        for (Path repo : sources) {
            jdbcTemplate.update("DELETE FROM skl_skills WHERE source_package = ?", repo.toString());
        }
        dynamicRepos.clear();
        // #250 检查态与更新留痕（本测试类独写面——整面清即净）
        jdbcTemplate.update("DELETE FROM skl_packages");
        jdbcTemplate.update("DELETE FROM skl_update_traces");
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

    // ---------- 卸载：无指派成功＋回执终态（有指派拒绝见 #249 段） ----------

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
    }

    // ---------- #249 槽位指派：读写＋负例族＋装配合成＋动态性＋卸载守卫 ----------

    @Test
    void given_installed_skills_when_signed_put_and_get_assignments_then_slots_independent()
            throws Exception {
        var fixtureIds = installFixtureAndReturnIds("tdd", "code-review");
        String tddId = fixtureIds.get("tdd");
        String reviewId = fixtureIds.get("code-review");

        // PUT 整包替换：main=[tdd]，executor=[code-review, tdd]（整包批量勾选形制）
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, tddId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slot").value("main"))
                .andExpect(jsonPath("$.data.skills", hasSize(1)))
                .andExpect(jsonPath("$.data.skills[0].id").value(tddId))
                .andExpect(jsonPath("$.data.skills[0].name").value("tdd"))
                .andExpect(jsonPath("$.data.skills[0].status").value(1));
        signedPutAssignments("executor", OPERATOR_ID, OPERATOR_NAME, reviewId, tddId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slot").value("executor"))
                .andExpect(jsonPath("$.data.skills[0].name").value("code-review"))
                .andExpect(jsonPath("$.data.skills[1].name").value("tdd"));

        // GET 三槽各自独立（未动过的 subagent 槽为空）
        signedGet("/api/backoffice/skills/assignments/main")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slot").value("main"))
                .andExpect(jsonPath("$.data.skills", hasSize(1)))
                .andExpect(jsonPath("$.data.skills[0].id").value(tddId));
        signedGet("/api/backoffice/skills/assignments/executor")
                .andExpect(jsonPath("$.data.skills", hasSize(2)));
        signedGet("/api/backoffice/skills/assignments/subagent")
                .andExpect(jsonPath("$.data.slot").value("subagent"))
                .andExpect(jsonPath("$.data.skills", hasSize(0)));

        // 整包替换＝清单即终态：main 重指 [code-review]，tdd 即解绑；重复柄去重
        signedPutAssignments("main", OPERATOR_ID_2, OPERATOR_NAME_2, reviewId, reviewId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills", hasSize(1)))
                .andExpect(jsonPath("$.data.skills[0].name").value("code-review"));
        // 留痕＝最近动作者（整包替换行级）
        var assignmentRow = jdbcTemplate.queryForMap(
                "SELECT operator_id, operator_name FROM skl_slot_assignments WHERE slot = 'main'");
        assertThat(assignmentRow)
                .containsEntry("operator_id", OPERATOR_ID_2)
                .containsEntry("operator_name", OPERATOR_NAME_2);
        // executor 槽不受 main 重指影响（槽间独立）
        signedGet("/api/backoffice/skills/assignments/executor")
                .andExpect(jsonPath("$.data.skills", hasSize(2)));

        // 空清单＝清空该槽位
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills", hasSize(0)));
    }

    @Test
    void given_bad_slot_or_builtin_or_unknown_id_when_signed_put_then_error_family() throws Exception {
        String tddId = installFixtureAndReturnId("tdd");

        // 未知槽位：404 SKL_010（GET 同语义）
        signedPutAssignments("reviewer", OPERATOR_ID, OPERATOR_NAME, tddId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_SLOT_NOT_FOUND_CODE))
                .andExpect(jsonPath("$.message").value("职能槽位不存在"));
        signedGet("/api/backoffice/skills/assignments/reviewer")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_SLOT_NOT_FOUND_CODE));

        // 内置柄不可指派：400 SKL_011（内置随平台发版，装配按配置挂载）
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, "builtin:prd-writing")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_BUILTIN_NOT_ASSIGNABLE_CODE));

        // 未寻址/畸形 TSID：404 SKL_001 同语义（库行不在/先卸载后指派的不变窗口）
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, "999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, "not-a-tsid")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));
        // 清单含 null 柄：同 404 语义（应用层先拦——Tsid 解析对 null 是 NPE 非 404）
        String nullBody = "{\"skillIds\": [null]}";
        mockMvc.perform(BackofficeSignatures.signed(
                        put("/api/backoffice/skills/assignments/main")
                                .contentType(MediaType.APPLICATION_JSON).content(nullBody),
                        "/api/backoffice/skills/assignments/main", nullBody)
                        .header("X-User-Id", OPERATOR_ID).header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_NOT_FOUND_CODE));

        // 缺操作者透传头：400 SKL_009（指派写操作必留痕）
        signedPutAssignments("main", null, null, tddId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_OPERATOR_REQUIRED_CODE));
        assertThatAssignmentRowCount(0);
    }

    @Test
    void given_rest_assignment_and_status_changes_when_supplier_view_reread_then_dynamic()
            throws Exception {
        var fixtureIds = installFixtureAndReturnIds("tdd", "code-review");
        String tddId = fixtureIds.get("tdd");
        String reviewId = fixtureIds.get("code-review");
        AgentWorkspace dev = new AgentWorkspace.ProjectDev("42", "ws-42-dev");

        // 初始装配面：main＝内置（prd-writing），executor/subagent＝空（无 <available_skills> 注入面）
        assertThat(assemblyFace(AgentProfile.MAIN.key(), dev))
                .containsExactly("prd-writing");
        assertThat(assemblyFace(AgentProfile.EXECUTOR.key(), dev)).isEmpty();

        // 指派 main=[tdd]、executor=[code-review]：同一 supplier 视图重读即反映
        // ——装配合成＝内置∪该槽位已指派且启用、他槽位技能不出现（动态查库）
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, tddId).andExpect(status().isOk());
        signedPutAssignments("executor", OPERATOR_ID, OPERATOR_NAME, reviewId).andExpect(status().isOk());
        assertThat(assemblyFace(AgentProfile.MAIN.key(), dev))
                .containsExactlyInAnyOrder("prd-writing", "tdd");
        assertThat(assemblyFace(AgentProfile.EXECUTOR.key(), dev))
                .containsExactly("code-review");

        // 停用即退出装配候选（同一视图重读——tdd 消失，内置不动）
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/disable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(assemblyFace(AgentProfile.MAIN.key(), dev))
                .containsExactly("prd-writing");

        // 启用恢复参与合成；解绑（整包替换清空）下一读即退出
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/enable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        assertThat(assemblyFace(AgentProfile.MAIN.key(), dev))
                .containsExactlyInAnyOrder("prd-writing", "tdd");
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME).andExpect(status().isOk());
        assertThat(assemblyFace(AgentProfile.MAIN.key(), dev))
                .containsExactly("prd-writing");

        // subagent 槽视图（缝按三槽同权供读侧/未来一等化接线）：指派后即反映、
        // 不继承执行体面
        signedPutAssignments("subagent", OPERATOR_ID, OPERATOR_NAME, tddId).andExpect(status().isOk());
        assertThat(assemblyFace(ProfileSubagentSupplier.SELF_TEST_NAME, dev))
                .containsExactly("tdd");
    }

    @Test
    void given_assigned_skill_when_signed_uninstall_then_409_until_unbound() throws Exception {
        String tddId = installFixtureAndReturnId("tdd");

        // 指派在身（main 槽）：卸载被拒 409 SKL_008——先解绑再卸
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, tddId).andExpect(status().isOk());
        signedDelete("/api/backoffice/skills/" + tddId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SKILL_ASSIGNED_CODE))
                .andExpect(jsonPath("$.message").value("技能有指派在身，先解绑再卸载"));

        // 解绑后可卸（守卫只看指派，不看槽位先后）
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME).andExpect(status().isOk());
        signedDelete("/api/backoffice/skills/" + tddId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("tdd"));
    }

    // ---------- #250 更新检查与显式更新：检查轮→标记→更新→留痕 ----------

    @Test
    void given_remote_advance_when_scan_then_marked_then_explicit_update_refreshes()
            throws Exception {
        Path repo = buildDynamicRepo("skill-update-flow", r -> {
            writeSkill(r, "engineering/tdd", "tdd", "测试先行红绿重构。", "红绿重构循环。");
            writeSkill(r, "engineering/code-review", "code-review", "双轴并行审查。", "先对规范、再对需求。");
            writeSkill(r, "deprecated/legacy", "legacy", "装时排除面。", "应被排除段挡在库外。");
        });
        var ids = installAndReturnIds(repo, "deprecated");
        String tddId = ids.get("tdd");
        String reviewId = ids.get("code-review");
        String oldSha = git(repo, "rev-parse", "HEAD").trim();

        // 装后即查（安装＝事实上的检查，#250）：标记 false 非 null（包行已落）
        assertThatUpdateAvailable(repo, false);

        // 停用＋指派：平台侧状态与指派关系须跨更新保留（原地翻新不改 id/状态/指派）
        signedPostNoBody("/api/backoffice/skills/" + tddId + "/disable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk());
        signedPutAssignments("main", OPERATOR_ID, OPERATOR_NAME, reviewId).andExpect(status().isOk());

        // 远端前进：改 code-review 正文＋新增 debugging＋deprecated 类目再添新条目
        appendCommit(repo, r -> {
            writeSkill(r, "engineering/code-review", "code-review", "双轴并行审查。", "新版正文——两轴结论合并呈报。");
            writeSkill(r, "engineering/debugging", "debugging", "系统化调试。", "先复现、再隔离、后修复。");
            writeSkill(r, "deprecated/legacy-2", "legacy-2", "装后新增的排除面。", "更新不还魂装时排除目录。");
        });
        String newSha = git(repo, "rev-parse", "HEAD").trim();

        // 检查轮（测试直调）：远端 HEAD ≠ 装时版本 → 标记亮（同包两行同亮——来源包级）
        assertThat(appService.checkRemoteUpdates()).isEqualTo(1);
        assertThatUpdateAvailable(repo, true);

        // 显式更新（操作者二号）：翻新入库＋版本留痕＋标记复位
        signedPostUpdate(repo.toString(), OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourcePackage").value(repo.toString()))
                .andExpect(jsonPath("$.data.fromVersion").value(oldSha))
                .andExpect(jsonPath("$.data.toVersion").value(newSha))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID_2))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME_2))
                .andExpect(jsonPath("$.data.removedSkillNames").value(hasSize(0)))
                // 终态行集：tdd＋code-review＋debugging（名称序），排除目录不还魂
                .andExpect(jsonPath("$.data.skills", hasSize(3)))
                .andExpect(jsonPath("$.data.skills[0].name").value("code-review"))
                .andExpect(jsonPath("$.data.skills[0].id").value(reviewId))
                .andExpect(jsonPath("$.data.skills[1].name").value("debugging"))
                .andExpect(jsonPath("$.data.skills[2].name").value("tdd"))
                .andExpect(jsonPath("$.data.skills[2].id").value(tddId))
                // 原地翻新：id 不变＋停用状态跨更新保留＋版本统一翻新
                .andExpect(jsonPath("$.data.skills[2].status").value(2))
                .andExpect(jsonPath("$.data.skills[*].version", everyItem(equalTo(newSha))));
        assertThatNoRows("legacy");
        assertThatNoRows("legacy-2");

        // 正文翻新可读（审核面所见即新快照）；指派跨更新保留（id 未变）
        signedGet("/api/backoffice/skills/" + reviewId)
                .andExpect(jsonPath("$.data.content").value(containsString("新版正文")))
                .andExpect(jsonPath("$.data.version").value(newSha));
        signedGet("/api/backoffice/skills/assignments/main")
                .andExpect(jsonPath("$.data.skills", hasSize(1)))
                .andExpect(jsonPath("$.data.skills[0].id").value(reviewId));

        // 版本留痕可查：from→to＋操作者二号（append-only 历史面）
        signedGet("/api/backoffice/skills/update-traces?sourcePackage=" + repo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].sourcePackage").value(repo.toString()))
                .andExpect(jsonPath("$.data[0].fromVersion").value(oldSha))
                .andExpect(jsonPath("$.data[0].toVersion").value(newSha))
                .andExpect(jsonPath("$.data[0].operatorId").value(OPERATOR_ID_2));

        // 标记复位（更新即一次事实检查）＋再扫描不亮（远端未前进不标）
        assertThatUpdateAvailable(repo, false);
        assertThat(appService.checkRemoteUpdates()).isZero();
        assertThatUpdateAvailable(repo, false);

        // 未前进再更新：幂等回执（from==to、不落痕不写行）
        signedPostUpdate(repo.toString(), OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromVersion").value(newSha))
                .andExpect(jsonPath("$.data.toVersion").value(newSha))
                .andExpect(jsonPath("$.data.skills", hasSize(3)));
        signedGet("/api/backoffice/skills/update-traces?sourcePackage=" + repo)
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void given_update_removes_assigned_skill_when_signed_update_then_409_until_unbound()
            throws Exception {
        Path repo = buildDynamicRepo("skill-update-remove", r -> {
            writeSkill(r, "engineering/tdd", "tdd", "测试先行红绿重构。", "红绿重构循环。");
        });
        String tddId = installAndReturnIds(repo).get("tdd");
        signedPutAssignments("executor", OPERATOR_ID, OPERATOR_NAME, tddId).andExpect(status().isOk());

        // 远端删 tdd 换 replacement：更新将移除有指派在身的 tdd → 整体 409 SKL_013
        appendCommit(repo, r -> {
            try {
                Files.delete(r.resolve("engineering/tdd/SKILL.md"));
            }
            catch (IOException e) {
                throw new IllegalStateException(e);
            }
            writeSkill(r, "engineering/replacement", "replacement", "顶替技能。", "新技能正文。");
        });
        signedPostUpdate(repo.toString(), OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SKILL_UPDATE_REMOVES_ASSIGNED_CODE))
                .andExpect(jsonPath("$.message").value("更新将移除有指派在身的技能，先解绑再更新"));

        // 解绑后更新成功：移除确认面（removedSkillNames）＋终态行集只剩 replacement
        signedPutAssignments("executor", OPERATOR_ID, OPERATOR_NAME).andExpect(status().isOk());
        signedPostUpdate(repo.toString(), OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removedSkillNames", hasSize(1)))
                .andExpect(jsonPath("$.data.removedSkillNames[0]").value("tdd"))
                .andExpect(jsonPath("$.data.skills", hasSize(1)))
                .andExpect(jsonPath("$.data.skills[0].name").value("replacement"));
        assertThatNoRows("tdd");
        // 留痕不因移除而缺（显式动作必留痕）
        signedGet("/api/backoffice/skills/update-traces?sourcePackage=" + repo)
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void given_unreachable_remote_when_scan_then_silent_degradation_not_throwing() throws Exception {
        // 坏远端行（T4 前存量形制：无包行、无真实仓库）
        plant(76_000_021L, "broken-skill", "/nonexistent/skill/repo-broken", "commit-broken", 1);
        Path okRepo = buildDynamicRepo("skill-update-ok", r ->
                writeSkill(r, "engineering/tdd", "tdd", "测试先行红绿重构。", "红绿重构循环。"));
        installAndReturnIds(okRepo);
        appendCommit(okRepo, r ->
                writeSkill(r, "engineering/debugging", "debugging", "系统化调试。", "先复现、再隔离。"));

        // 静默降级（#250 AC）：坏远端不炸轮、不写状态（标记留 null＝未检查过）、
        // 不拖垮同轮他包（ok 包照常标记）
        assertThat(appService.checkRemoteUpdates()).isEqualTo(1);
        assertThatUpdateAvailable(okRepo, true);
        Integer brokenRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skl_packages WHERE source_package = ?",
                Integer.class, "/nonexistent/skill/repo-broken");
        assertThat(brokenRows).as("坏远端不落检查状态").isZero();
        String listBody = signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(listBody,
                "$.data[?(@.name == 'broken-skill')].updateAvailable"))
                .as("坏远端清单行标记＝null（未检查过）")
                .containsExactly((Object) null);
    }

    @Test
    void given_blank_or_unknown_source_or_missing_operator_when_signed_update_then_error_family()
            throws Exception {
        Path repo = buildDynamicRepo("skill-update-negative", r ->
                writeSkill(r, "engineering/tdd", "tdd", "测试先行红绿重构。", "红绿重构循环。"));
        installAndReturnIds(repo);

        // 空来源包：400 SKL_014（请求自身缺陷）
        signedPostUpdate("", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_SOURCE_PACKAGE_REQUIRED_CODE))
                .andExpect(jsonPath("$.message").value("来源包标识不能为空（取清单行 sourcePackage 值）"));
        // 未装来源包：404 SKL_012（尾斜杠变体同源——规范化身份）
        signedPostUpdate("/nonexistent/skill/repo-xyz", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_SOURCE_NOT_INSTALLED_CODE))
                .andExpect(jsonPath("$.message").value("来源包未安装（更新寻址已装来源包，未装先走安装）"));
        signedPostUpdate("/nonexistent/skill/repo-xyz/", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SKILL_SOURCE_NOT_INSTALLED_CODE));
        // 缺操作者透传头（已装来源包）：400 SKL_009（显式更新必留痕）
        signedPostUpdate(repo.toString(), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_OPERATOR_REQUIRED_CODE));
        // 留痕读面空参：400 SKL_014 同语义
        signedGet("/api/backoffice/skills/update-traces")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SKILL_SOURCE_PACKAGE_REQUIRED_CODE));
    }

    @Test
    void given_no_signature_headers_when_update_or_traces_then_401_signature_required()
            throws Exception {
        mockMvc.perform(post("/api/backoffice/skills/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePackage\": \"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(get("/api/backoffice/skills/update-traces?sourcePackage=whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    @Test
    void given_no_signature_headers_when_put_assignments_then_401_signature_required() throws Exception {
        mockMvc.perform(put("/api/backoffice/skills/assignments/main")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillIds\": []}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
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
        commitAll(repo, "fixture");
        return repo;
    }

    /** 测试内现建动态仓库（#250 更新线专用）：登记 teardown 按来源包清理。 */
    private Path buildDynamicRepo(String name, DirectoryPopulator populator) throws Exception {
        Path repo = buildRepo(name, populator);
        dynamicRepos.add(repo);
        return repo;
    }

    /** fixture 仓库追加 commit（模拟远端前进——快照安装后远端 HEAD 前移的验收形制）。 */
    private static void appendCommit(Path repo, DirectoryPopulator populator) throws Exception {
        populator.populate(repo);
        git(repo, "add", "-A");
        commitAll(repo, "advance");
    }

    private static void commitAll(Path repo, String message) throws Exception {
        git(repo, "-c", "user.name=Skill Fixture", "-c", "user.email=fixture@aiplatform.local",
                "commit", "-q", "-m", message);
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
        return installFixtureAndReturnIds(skillName).get(skillName);
    }

    /** 同上，一次装包回多名（同源仓库一测试只装一次——SKL_003 同源去重）。 */
    private Map<String, String> installFixtureAndReturnIds(String... skillNames)
            throws Exception {
        String body = """
                {"repoUrl": "%s", "excludeDirs": ["deprecated"]}""".formatted(fixtureRepo);
        String response = mockMvc.perform(signedInstallBuilder(body, OPERATOR_ID, OPERATOR_NAME))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, String> ids = new LinkedHashMap<>();
        for (String skillName : skillNames) {
            ids.put(skillName, JsonPath.<List<String>>read(
                    response, "$.data[?(@.name == '" + skillName + "')].id").get(0));
        }
        return ids;
    }

    /** 走安装真链路装动态仓库（#250 通用形），回「技能名→TSID 柄」全量映射。 */
    private Map<String, String> installAndReturnIds(Path repo, String... excludeDirs)
            throws Exception {
        String response = signedInstall(repo.toString(), OPERATOR_ID, OPERATOR_NAME, excludeDirs)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, String> ids = new LinkedHashMap<>();
        for (String name : JsonPath.<List<String>>read(response, "$.data[*].name")) {
            ids.put(name, JsonPath.<List<String>>read(
                    response, "$.data[?(@.name == '" + name + "')].id").get(0));
        }
        return ids;
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

    /** 签名 PUT 指派（JSON 体＝skillIds 全量清单＋操作者透传头；null 即缺头负例形制）。 */
    private ResultActions signedPutAssignments(String slot, String operatorId,
            String operatorName, String... skillIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (String id : skillIds) {
            if (!ids.isEmpty()) {
                ids.append(", ");
            }
            ids.append('"').append(id).append('"');
        }
        String body = "{\"skillIds\": [%s]}".formatted(ids);
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                put("/api/backoffice/skills/assignments/" + slot)
                        .contentType(MediaType.APPLICATION_JSON).content(body),
                "/api/backoffice/skills/assignments/" + slot, body);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(request);
    }

    /** 签名显式更新 POST（JSON 体＝sourcePackage＋操作者透传头；null 即缺头负例形制）。 */
    private ResultActions signedPostUpdate(String sourcePackage, String operatorId,
            String operatorName) throws Exception {
        String path = "/api/backoffice/skills/update";
        String body = "{\"sourcePackage\": \"%s\"}".formatted(sourcePackage);
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                post(path).contentType(MediaType.APPLICATION_JSON).content(body), path, body);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(request);
    }

    /** 该来源包全部清单行的「有新版」标记同值断言（来源包级事实——行行同亮灭）。 */
    private void assertThatUpdateAvailable(Path repo, boolean expected) throws Exception {
        String response = signedGet("/api/backoffice/skills")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Boolean> marks = JsonPath.read(response,
                "$.data[?(@.sourcePackage == '" + repo + "')].updateAvailable");
        assertThat(marks)
                .as("来源包 %s 的清单行有新版标记", repo)
                .isNotEmpty()
                .containsOnly(expected);
    }

    /**
     * 装配缝视图（#249 缝 2 真链路）：该配置键全部仓库的技能名并集——即框架
     * {@code <available_skills>} 注入面的来源（内置∪槽位库技能）。同一 supplier
     * 实例重读（不重建供应商）＝动态查库断言形制。
     */
    private List<String> assemblyFace(String agentKey, AgentWorkspace workspace) {
        return skillRepositorySupplier.skillRepositoriesFor(agentKey, workspace).stream()
                .flatMap(repo -> repo.getAllSkills().stream())
                .map(skill -> skill.getName())
                .toList();
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

    /** 指派表全行数（负例族 fail-fast 断言：整体不落指派）。 */
    private void assertThatAssignmentRowCount(int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skl_slot_assignments", Integer.class);
        assertThat(count).as("skl_slot_assignments 行数").isEqualTo(expected);
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
