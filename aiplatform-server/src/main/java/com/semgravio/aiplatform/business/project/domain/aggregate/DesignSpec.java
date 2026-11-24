package com.semgravio.aiplatform.business.project.domain.aggregate;

import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.project.domain.model.StyleDirections;

/**
 * 项目设计规范正本行（{@code prj_design_specs}，#295 ADR-0028）：项目级的设计
 * 一致性正本——<b>单一一份、各设计物共用、随定稿刷新</b>（新定稿覆盖旧规范＝
 * 整行覆写，提炼无所得不刷新——见 {@code DesignSpecAppService} 口径）。两面互斥：
 * 界面类＝{@code tokens}（定稿时平台从稿内 :root 确定性直提）；平面类＝
 * {@code palette}＋{@code style}（出图设计参数随稿物化为规范草稿、定稿转正
 * ——参数即设计意图正身）。消费面＝规范遵守三件套（#296）与设计资产包（#297）。
 */
@Entity
@Table(name = "prj_design_specs")
@Aggregate
@Getter
public class DesignSpec extends Auditable implements AggregateRoot<DesignSpec, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 提炼源：定稿选定的稿（工作区锚定形——与 DesignItem.finalizedPath 同形）。 */
    @Column(name = "source_draft_path", nullable = false, length = 500)
    private String sourceDraftPath;

    /** 提炼锚：定稿 runId（与定稿收尾卡同锚——溯源「哪次定稿产出的规范」；成版 Run-Id 已退役）。 */
    @Column(name = "source_run_id", nullable = false, length = 100)
    private String sourceRunId;

    /** 界面类直提面：:root token（名→值；jsonb 落库不保序、集合等值消费；
     *  NULL＝本正本是参数形）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tokens", columnDefinition = "jsonb")
    private Map<String, String> tokens;

    /** 平面类转正面：色板（NULL＝本正本是 token 形）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "palette", columnDefinition = "jsonb")
    private List<String> palette;

    /** 平面类转正面：风格短语。 */
    @Column(name = "style", length = 500)
    private String style;

    /**
     * 风格方向来源标记（#543，ADR-0052——NULL＝设计线来源行〔定稿提炼/出图转正〕；
     * 非空＝生成式来源行〔键＝{@link com.semgravio.aiplatform.business.project.domain.model.StyleDirections}
     * 库稳定键〕：物化通道据此识别「PRD 风格方向字段→库值映射」正本，首个构建
     * 派发物化、其后派发按 PRD 字段重物化）。
     */
    @Column(name = "direction_key", length = 50)
    private String directionKey;

    protected DesignSpec() {
    }

    private DesignSpec(Long projectId) {
        this.projectId = projectId;
    }

    /** 界面类正本行（首次刷新建行：:root 直提面）。 */
    public static DesignSpec tokensOf(Long projectId, String draftPath, String runId,
            Map<String, String> tokens) {
        DesignSpec spec = new DesignSpec(projectId);
        spec.refreshTokens(draftPath, runId, tokens);
        return spec;
    }

    /** 平面类正本行（首次刷新建行：出图参数转正面）。 */
    public static DesignSpec paramsOf(Long projectId, String draftPath, String runId,
            List<String> palette, String style) {
        DesignSpec spec = new DesignSpec(projectId);
        spec.refreshParams(draftPath, runId, palette, style);
        return spec;
    }

    /**
     * 风格方向来源正本行（#543——生成式来源：token 面＝库值集直刷，style＝方向
     * 人读名，directionKey＝方向稳定键；源锚＝PRD 正本路径＋物化锚）。改风格＝
     * PRD 修订→派发重物化（{@code refreshStyle} 覆写）。
     */
    public static DesignSpec styleOf(Long projectId, String sourcePath, String runId,
            StyleDirections.Direction direction) {
        DesignSpec spec = new DesignSpec(projectId);
        spec.refreshStyle(sourcePath, runId, direction.key(), direction.name(), direction.tokens());
        return spec;
    }

    /**
     * 刷新为 token 面（覆盖旧规范＝整行覆写：参数面清空）；空 token 集是调用方
     * 「提炼无所得不刷新」口径的防御位（如实拒收——正本行恒有且恰有一面）。
     */
    public void refreshTokens(String draftPath, String runId, Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("token 面不能为空（提炼无所得不建行）");
        }
        this.sourceDraftPath = draftPath;
        this.sourceRunId = runId;
        this.tokens = tokens;
        this.palette = null;
        this.style = null;
        this.directionKey = null;
    }

    /** 刷新为参数面（覆盖旧规范＝整行覆写：token 面清空）；两面皆空同上防御拒收。 */
    public void refreshParams(String draftPath, String runId, List<String> palette, String style) {
        if ((palette == null || palette.isEmpty()) && (style == null || style.isBlank())) {
            throw new IllegalArgumentException("参数面不能为空（提炼无所得不建行）");
        }
        this.sourceDraftPath = draftPath;
        this.sourceRunId = runId;
        this.tokens = null;
        this.palette = palette;
        this.style = style;
        this.directionKey = null;
    }

    /**
     * 刷新为风格方向面（#543——生成式来源覆写：token 面＝库值集、风格短语＝方向
     * 人读名、方向标记随写；空 token 集同上防御拒收）。设计线来源行被风格面
     * 覆写后不回落（正本恒一行、新覆盖旧——同「随定稿刷新」语义）。
     */
    public void refreshStyle(String sourcePath, String runId, String directionKey,
            String styleName, Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("风格方向面 token 集不能为空（不建行）");
        }
        this.sourceDraftPath = sourcePath;
        this.sourceRunId = runId;
        this.tokens = tokens;
        this.palette = null;
        this.style = styleName;
        this.directionKey = directionKey;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
