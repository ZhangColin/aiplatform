package com.aieducenter.aiplatform.business.project.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
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
 * 正本消费面（#296 遵守三件套①③）：<b>派发物化</b>（{@link #materializeAtDispatch}
 * ，生成与更新 run 起手接线——带规范项目平台确定性写 :root 刷值＋调色板收窄块＋
 * DESIGN.md 规则文件＋lint 配置，非模型动作；无规范项目零动作零写入）与
 * <b>收口扫描</b>（{@link #lintClosingPayload}，编码 run 收口判据回调内接线——
 * 容器内 oxlint〔@shadcn/lint 规则〕跑 token 合规扫描，事实随收尾卡如实呈现；
 * 无规范项目不扫）。设计资产包规范文件（#297）读同一正本。
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

    // ===== 遵守面（#296 消费侧：派发物化＋收口扫描） =====

    /** lint 配置文件名（工作区根——oxlint 自 cwd 解析；带规范项目平台写入）。 */
    static final String OXLINTRC = ".oxlintrc.json";

    /**
     * lint 配置正文（@shadcn/lint 0.2 规则三件＝ADR-0028 点名的裸色/任意值/inline
     * style；oxlint 1.8+ 双通道走 Oxlint——零 parser 配置、单二进制）：ignorePatterns
     * 排除依赖/构建产物/外部资料/设计稿目录（设计稿是单文件 HTML 非 lint 面）。
     */
    static final String OXLINTRC_CONTENT = """
            {
              "jsPlugins": ["@shadcn/lint"],
              "rules": {
                "shadcn/no-raw-colors": "error",
                "shadcn/no-arbitrary-values": "error",
                "shadcn/no-inline-styles": "error"
              },
              "ignorePatterns": ["node_modules/**", ".next/**", "external/**", "design/**"]
            }
            """;

    /**
     * 收口扫描命令（容器内真跑 oxlint；{@code --format json} 输出诊断清单——退出码
     * 0＝无违规、1＝有诊断，其他＝配置/环境故障）。工作区根经 cd 定位（oxlint 自
     * cwd 解析 .oxlintrc.json 与项目结构）。
     */
    static final String OXLINT_COMMAND =
            "cd " + WorkspaceLayout.ROOT + " && ./node_modules/.bin/oxlint --format json";

    /** 收尾卡违规清单上限（其余以 total 计数呈现——卡不无限长；重试现场同律）。 */
    static final int MAX_LINT_LISTED = 20;

    /** 收口扫描载荷的状态值：通过／违规（清单随附）／未执行（如实、不假装达标）。 */
    static final String LINT_PASSED = "passed";
    static final String LINT_VIOLATIONS = "violations";
    static final String LINT_UNAVAILABLE = "unavailable";

    /**
     * 派发物化（#296 遵守三件套①结构物化——生成与更新 run 起手接线，先于
     * AGENTS.md 资产就位）：带规范项目平台确定性写三件——
     * <ul>
     * <li>{@code src/app/globals.css}：:root 值刷换（token 面＝正本 token；参数面＝
     * 色板物化为 {@code --brand-N}——品牌色由此进系统主题）＋平台 @theme 块
     * （调色板收窄＋品牌色暴露）。语义 token 引用不变时刷值即全局换肤；执行体
     * 自加 token/注释原样保留；globals.css 不在（执行体重构了样式文件）＝CSS 面
     * 跳过留日志（规则文件与收窄纪律照常——保守方向不破链）；</li>
     * <li>{@code DESIGN.md} 规则文件（{@link DesignSpecs#designRulesMarkdown}——
     * 双件之规则面，#297 资产包规范文件同一物）；</li>
     * <li>{@code .oxlintrc.json} lint 配置（收口扫描的容器内配置面）。</li>
     * </ul>
     * 幂等（重复派发＝重复刷值稳定）；写入失败＝环境故障如实上抛（run 不起跑——
     * 与 AGENTS.md 资产同口径，遵守链不静默缺席）。
     *
     * @return 是否带规范（真＝带规范项目已物化；调用方据此拼 AGENTS.md 设计规范节）
     */
    public boolean materializeAtDispatch(Project project) {
        DesignSpec spec = designSpecs.findByProjectId(project.getId()).orElse(null);
        if (spec == null) {
            return false;
        }
        String workspaceId = Long.toString(project.getWorkspaceId());
        // :root 刷值集：token 面＝正本直提 token；参数面＝色板→品牌附加色（确定性映射）
        Map<String, String> rootRefresh = spec.getTokens() != null
                ? spec.getTokens()
                : DesignSpecs.brandTokensOf(spec.getPalette());
        Optional<String> css = readWorkspaceFile(project, DesignSpecs.BASELINE_GLOBALS_CSS);
        if (css.isPresent()) {
            String refreshed = DesignSpecs.upsertSpecTheme(css.get(),
                    DesignSpecs.specThemeBlockOf(rootRefresh));
            if (!rootRefresh.isEmpty()) {
                refreshed = DesignSpecs.refreshRootValues(refreshed, rootRefresh);
            }
            writeWorkspaceFile(workspaceId, DesignSpecs.BASELINE_GLOBALS_CSS, refreshed);
        }
        else {
            log.warn("[design-spec] 项目 {} 基座样式文件 {} 不在（执行体重构？）——:root 刷值与"
                    + "调色板收窄跳过，规则文件与 lint 扫描照常", project.getId(),
                    DesignSpecs.BASELINE_GLOBALS_CSS);
        }
        writeWorkspaceFile(workspaceId, WorkspaceLayout.DESIGN_MD, DesignSpecs.designRulesMarkdown(
                spec.getSourceDraftPath(), spec.getTokens(), spec.getPalette(), spec.getStyle()));
        writeWorkspaceFile(workspaceId, OXLINTRC, OXLINTRC_CONTENT);
        return true;
    }

    /**
     * 收口扫描（#296 遵守三件套③后处理校验——编码 run 收口判据回调内接线）：
     * 容器内跑 oxlint（规则＝裸色/任意值/inline style）→ 事实载荷随收尾卡。
     * <b>软约束不当硬门</b>：违规不抛（重试一轮的决策归尝试环，仍违规如实列
     * 清单）；扫描不可执行（二进制缺失/配置错/环境故障）＝{@code unavailable}
     * 如实呈现，不假装达标。无规范项目＝{@code null}（不扫——基座行为零改变）。
     * 全程不抛（收口链路不被扫描故障反噬）。
     */
    public Map<String, Object> lintClosingPayload(Project project) {
        if (designSpecs.findByProjectId(project.getId()).isEmpty()) {
            return null;
        }
        try {
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(project.getWorkspaceId()),
                    new WorkspaceExecCommand(OXLINT_COMMAND));
            if (result.exitCode() == 0) {
                return Map.of("status", LINT_PASSED);
            }
            if (result.exitCode() == 1) {
                List<Map<String, Object>> violations = parseOxlintViolations(result.stdout());
                if (violations != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", LINT_VIOLATIONS);
                    payload.put("total", violations.size());
                    payload.put("violations", violations.subList(0,
                            Math.min(violations.size(), MAX_LINT_LISTED)));
                    return payload;
                }
                log.warn("[design-spec] 项目 {} lint 退出码 1 但诊断输出不可解析（不猜）：{}",
                        project.getId(), firstLine(result.stdout()));
            }
            else {
                log.warn("[design-spec] 项目 {} lint 扫描异常退出（码 {}）：{}",
                        project.getId(), result.exitCode(), firstLine(result.stderr()));
            }
        }
        catch (RuntimeException e) {
            log.warn("[design-spec] 项目 {} lint 扫描执行失败：{}", project.getId(), e.toString());
        }
        return Map.of("status", LINT_UNAVAILABLE);
    }

    /**
     * oxlint {@code --format json} 诊断输出 → 违规条目（file/line/rule/message——
     * rule 取 {@code shadcn(no-raw-colors)} 括号内名）。只数 error 级（配置全
     * error，防御）；形状异常＝{@code null}（不可解析不猜——调用方转 unavailable）。
     * 前导非 JSON 行（@shadcn/lint 组件识别提示走 stderr，防御容错）经首个 {@code {}
     * 定位剥除。
     */
    private static List<Map<String, Object>> parseOxlintViolations(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        int brace = stdout.indexOf('{');
        if (brace < 0) {
            return null;
        }
        try {
            JsonNode diagnostics = JSON.readTree(stdout.substring(brace)).path("diagnostics");
            if (!diagnostics.isArray()) {
                return null;
            }
            List<Map<String, Object>> violations = new ArrayList<>();
            for (JsonNode diagnostic : diagnostics) {
                if (!"error".equals(diagnostic.path("severity").asText())) {
                    continue;
                }
                String code = diagnostic.path("code").asText();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", diagnostic.path("filename").asText());
                item.put("line", diagnostic.path("labels").path(0).path("span").path("line").asInt());
                item.put("rule", code.contains("(")
                        ? code.substring(code.indexOf('(') + 1, code.length() - 1) : code);
                item.put("message", diagnostic.path("message").asText());
                violations.add(item);
            }
            return violations;
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 日志用首行（诊断输出可能很长——日志面只留线索）。 */
    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /**
     * 平台落盘写（遵守面资产共用——规则文件/lint 配置/:root 刷值回写）：
     * {@link ProjectFiles#stdinWriteCommand} 形制（stdin 灌入、stat 字节回执）；
     * 退出码非 0＝环境故障如实上抛（物化不静默缺席）。
     */
    private void writeWorkspaceFile(String workspaceId, String relativePath, String content) {
        ExecResultResponse result = workspaceLifecycleAppService.execWithStdin(workspaceId,
                new WorkspaceExecCommand(ProjectFiles.stdinWriteCommand(relativePath)),
                content.getBytes(StandardCharsets.UTF_8));
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "设计规范资产写入失败（" + relativePath + "）: " + result.stderr());
        }
    }
}
