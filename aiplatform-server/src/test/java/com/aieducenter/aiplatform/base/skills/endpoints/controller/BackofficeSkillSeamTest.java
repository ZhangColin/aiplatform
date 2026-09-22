package com.aieducenter.aiplatform.base.skills.endpoints.controller;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 技能库清单/详情两端点在 {@code #152} seam 上全绿：真过滤链（签名闸/会话豁免/
 * 强制拦截器）＋真应用服务＋aiplatform_test 真库。库条目夹具经 JdbcTemplate 直插
 * （T1 无写口——安装属 #248），teardown 按种植 id 精确清理。
 *
 * <p>#247 四面钉死：</p>
 * <ul>
 * <li><b>清单</b>：空库仍呈现内置技能（classpath 合成，来源＝内置、来源包/版本
 * 标识 null、状态恒启用）＋库行同权（来源＝安装，名称/简介/来源包/版本标识/状态
 * 逐字段）＋排序（内置在前名称序、安装在后来源包名称序——跨包同名共存区分呈现）
 * ＋技能柄两形制（builtin:&lt;技能名&gt;／TSID 十进制串）；</li>
 * <li><b>详情</b>：元数据＋frontmatter 全量（解析态键值）＋正文全文（frontmatter
 * 剥离后）——内置与安装两出处各自可读（审核面所见即注入面）；</li>
 * <li><b>404</b>：未寻址内置名/TSID、畸形柄同语义 404 SKL_001（信封数字码 7001）；</li>
 * <li><b>鉴权</b>：非签名请求被拒（401，后台鉴权惯例）。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeSkillSeamTest {

    /** 信封数字业务码：SKL 域码 7 × 1000＋序号（SKL_001）。 */
    private static final int SKILL_NOT_FOUND_CODE = 7001;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 已种植库条目 id（teardown 精确清理 skl_skills）。 */
    private final List<Long> plantedSkillIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : plantedSkillIds) {
            jdbcTemplate.update("DELETE FROM skl_skills WHERE id = ?", id);
        }
        plantedSkillIds.clear();
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

    // ---------- 鉴权：非签名被拒 ----------

    @Test
    void given_no_signature_headers_when_get_skills_then_401_signature_required() throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/skills"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 夹具 ----------

    /** 种植库条目（T1 无写口，JdbcTemplate 直插；description 与 frontmatter 同源）。 */
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

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null);
        return mockMvc.perform(request);
    }
}
