package com.aieducenter.aiplatform.business.project.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignSpec;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.DesignSpecs;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignSpecRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 设计规范提炼线（#295，ADR-0028——项目级单一正本、随定稿刷新）：<b>伴随产出＋
 * 确定性提取、不经模型判读</b>的两个接线点——
 * <ul>
 * <li><b>平面类物化</b>（{@link #materializePrintParams}，出图工具件成功路径随调）：
 * 出图设计参数（色板/风格——参数即设计意图正身）随每张落盘稿物化为同词干侧车
 * 草稿 {@code design/{词干}.spec.json}（落 {@link DesignSpecs} 单点）；参数缺席＝
 * 无设计意图，不物化空壳；物化失败 quietly 不反噬出图（伴随产出不是出图的成败
 * 判据，对偶渲败回落先例）；</li>
 * <li><b>定稿刷新</b>（{@link #refreshAtFinalize}，{@code DesignProcessAppService#
 * finalizeDesignItem} 落定序接线——先于后续分岔，按稿对齐的 run 以刷新后的正本
 * 为准）：界面类 HTML 稿 → 稿内 :root 确定性直提（token 面）；平面类位图稿 →
 * 侧车参数转正（参数面），侧车缺席（代码出图路）回落同名 HTML 源直提。提炼
 * 无所得（无 :root／侧车畸形／读取不可达）<b>不刷新</b>——旧正本保持（品牌一致
 * 性不被空稿冲掉），失败 quietly 不反噬定稿（定稿是用户显式动作收口，规范是
 * 伴随事实）。</li>
 * </ul>
 * 正本消费面归后续票：遵守三件套的基座物化（#296）与设计资产包的规范文件
 * （#297）。无落库事务注解（单行覆写，仓储自带事务面——同设计轨道表落位口径）。
 */
@Service
@Slf4j
public class DesignSpecAppService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DesignSpecRepository designSpecs;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public DesignSpecAppService(DesignSpecRepository designSpecs,
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.designSpecs = designSpecs;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 平面类出图参数随稿物化（#295 验收③前半）：对本次落盘的每张位图稿写同词干
     * 侧车 {@code design/{词干}.spec.json}（载荷形单点 {@link DesignSpecs#sidecarPayload}
     * ——色板/风格各按在场呈现，两面皆空＝不物化）。逐张独立、失败留日志不抛
     * （出图结果已成立，规范草稿是伴随产出）。
     *
     * @param workspaceId  工作区标识（工具面锚定形）
     * @param landedPaths  本次落盘稿的工作区相对路径清单
     * @param palette      色板（可空——出图时执行体传的设计参数）
     * @param style        风格短语（可空）
     */
    public void materializePrintParams(String workspaceId, List<String> landedPaths,
            List<String> palette, String style) {
        Map<String, Object> payload = DesignSpecs.sidecarPayload(palette, style);
        if (payload.isEmpty()) {
            return;
        }
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        }
        catch (JsonProcessingException e) {
            // Map<String,Object> 纯字面载荷不可达；防御性如实暴露（不静默半套）
            throw new IllegalStateException("规范草稿载荷序列化失败", e);
        }
        for (String path : landedPaths) {
            try {
                ExecResultResponse result = workspaceLifecycleAppService.execWithStdin(workspaceId,
                        new WorkspaceExecCommand(DesignSpecs.sidecarWriteCommand(
                                DesignSpecs.sidecarPathOf(path))), bytes);
                if (result.exitCode() != 0) {
                    log.warn("[design-spec] 规范草稿物化失败（{}，退出码 {}）——不反噬出图",
                            path, result.exitCode());
                }
            }
            catch (RuntimeException e) {
                log.warn("[design-spec] 规范草稿物化失败（{}）——不反噬出图：{}",
                        path, e.toString());
            }
        }
    }

    /**
     * 定稿刷新项目正本（#295 验收②③④）：按定稿稿形态分岔提炼——界面类 HTML 稿
     * 直提 :root；平面类位图稿侧车参数转正（侧车缺席＝代码出图路，回落同名 HTML
     * 源直提）。有所得即覆写正本（新定稿覆盖旧规范、两面互斥、项目恒一行）；
     * 无所得不刷新；任何失败 quietly（定稿事实不受影响）。
     *
     * @param project    定稿项目（工作区锚）
     * @param draftPath  定稿选定的稿（工作区锚定形——与件行 finalizedPath 同形）
     * @param runId      定稿锚（与定稿收尾卡/成版 Run-Id 同锚）
     */
    public void refreshAtFinalize(Project project, String draftPath, String runId) {
        String relative = ProjectFiles.relativeFormOf(draftPath);
        try {
            boolean refreshed = ProjectFiles.isDraftHtml(relative)
                    ? refreshTokensFromHtml(project, relative, draftPath, runId)
                    : refreshFromImageDraft(project, relative, draftPath, runId);
            if (!refreshed) {
                log.info("[design-spec] 项目 {} 定稿稿 {} 提炼无所得（无 :root／无参数侧车／"
                        + "读取不可达），项目规范不刷新", project.getId(), draftPath);
            }
        }
        catch (RuntimeException e) {
            log.warn("[design-spec] 项目 {} 定稿稿 {} 规范提炼失败（定稿不受影响）：{}",
                    project.getId(), draftPath, e.toString());
        }
    }

    /** HTML 稿直提（界面类路径＝稿本身；位图路径＝同名源稿回落共用）。 */
    private boolean refreshTokensFromHtml(Project project, String htmlRelative,
            String draftPath, String runId) {
        return readWorkspaceFile(project, htmlRelative)
                .map(content -> upsertTokens(project, draftPath, runId,
                        DesignSpecs.rootTokensOf(content)))
                .orElse(false);
    }

    /** 位图稿分岔：侧车参数转正优先（图片模型路），缺席回落同名 HTML 源（代码出图路）。 */
    private boolean refreshFromImageDraft(Project project, String imageRelative,
            String draftPath, String runId) {
        Optional<String> sidecar = readWorkspaceFile(project,
                DesignSpecs.sidecarPathOf(imageRelative));
        if (sidecar.isPresent() && upsertParamsFromSidecar(project, draftPath, runId,
                sidecar.get())) {
            return true;
        }
        return refreshTokensFromHtml(project, DesignSpecs.htmlSourcePathOf(imageRelative),
                draftPath, runId);
    }

    /** 侧车 JSON → 参数面转正（畸形/两面皆空＝如实无所得，不猜不炸）。 */
    private boolean upsertParamsFromSidecar(Project project, String draftPath, String runId,
            String sidecarJson) {
        List<String> palette = new ArrayList<>();
        String style = null;
        try {
            JsonNode root = JSON.readTree(sidecarJson);
            JsonNode paletteNode = root.get("palette");
            if (paletteNode != null && paletteNode.isArray()) {
                paletteNode.forEach(node -> {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        palette.add(node.asText());
                    }
                });
            }
            if (root.hasNonNull("style")) {
                style = root.get("style").asText();
            }
        }
        catch (JsonProcessingException e) {
            return false;
        }
        Map<String, Object> payload = DesignSpecs.sidecarPayload(palette, style);
        if (payload.isEmpty()) {
            return false;
        }
        // 载荷只含在场面（sidecarPayload 单点）：palette 缺席＝仅风格侧车，列落
        // NULL（空数组与「token 形为 NULL」的列语义冲突，不落噪音空壳）
        @SuppressWarnings("unchecked")
        List<String> paletteFace = (List<String>) payload.get("palette");
        String styleFace = (String) payload.get("style");
        DesignSpec row = designSpecs.findByProjectId(project.getId()).orElse(null);
        if (row == null) {
            designSpecs.save(DesignSpec.paramsOf(project.getId(), draftPath, runId,
                    paletteFace, styleFace));
        }
        else {
            row.refreshParams(draftPath, runId, paletteFace, styleFace);
            designSpecs.save(row);
        }
        return true;
    }

    /** token 面转正（空集＝提炼无所得，不刷新——调用口径）。 */
    private boolean upsertTokens(Project project, String draftPath, String runId,
            Map<String, String> tokens) {
        if (tokens.isEmpty()) {
            return false;
        }
        DesignSpec row = designSpecs.findByProjectId(project.getId()).orElse(null);
        if (row == null) {
            designSpecs.save(DesignSpec.tokensOf(project.getId(), draftPath, runId, tokens));
        }
        else {
            row.refreshTokens(draftPath, runId, tokens);
            designSpecs.save(row);
        }
        return true;
    }

    /**
     * 容器读文件（提炼取件通道）：{@link ProjectFiles#contentCommand}（可浏览
     * 判定＋1 MiB 限读＋「大小行＋正文」回执）。非 0（不在/超限/环境故障）＝
     * 如实无所得（空）——提炼不猜。
     */
    private Optional<String> readWorkspaceFile(Project project, String relativePath) {
        try {
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(project.getWorkspaceId()),
                    new WorkspaceExecCommand(ProjectFiles.contentCommand(relativePath)));
            if (result.exitCode() != 0) {
                return Optional.empty();
            }
            int newline = result.stdout().indexOf('\n');
            if (newline < 0) {
                return Optional.empty();
            }
            return Optional.of(result.stdout().substring(newline + 1));
        }
        catch (RuntimeException e) {
            log.warn("[design-spec] 项目 {} 文件 {} 读取失败（提炼如实无所得）：{}",
                    project.getId(), relativePath, e.toString());
            return Optional.empty();
        }
    }
}
