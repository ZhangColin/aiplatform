package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

import lombok.extern.slf4j.Slf4j;

/**
 * 基座 lint 两档收口扫描（#425，#407 裁决——同设计规范 lint 先例 #296：容器内
 * 执行、违规清单喂回执行体修一轮）：新基座工作区（健壮件在场探针判据，#421）的
 * 编码 run 收口扫描，判据两档——
 * <ul>
 * <li><b>强制档</b>（{@code mandatory}）：数据访问与外部调用的<b>裸客户端直用</b>
 * ——裸 pg/redis 客户端（{@code new Pool/Client/Redis}、{@code createClient}）与
 * 裸 {@code fetch}。修不完走收口拒绝回路（尝试环结构性拒绝、段级自愈升档兜底
 * ——判定归 {@code CoderRunAttempts}，本件只出事实）。</li>
 * <li><b>预置档</b>（{@code preset}）：会话存储件<b>自写</b>——手写 cookie 会话
 * （{@code cookies()} 直用）与裸密码哈希原语（scrypt/bcrypt/pbkdf2）。修不完
 * 如实标注收尾卡、不拦收口、<b>不拦不用</b>（未用会话件＝零命中，非违规）。</li>
 * </ul>
 * 经基座件的合法用法不误报：件目录（src/lib/robust、src/components/robust——
 * 件自身实现天然含这些原语）在 grep 侧排除（{@code --exclude-dir=robust}）＋
 * 解析侧剔除（双保险）。存量工作区（健壮件不在场）＝{@code null} 不扫描——
 * 旧基座直用是既有合法形态，零行为改变。扫描不可执行（grep 异常退出/通道故障）＝
 * {@code unavailable} 如实呈现、不假装达标、全程不抛（收口链路不被扫描故障
 * 反噬——同设计规范 lint 口径；环境故障不是违规）。
 *
 * <p>件用法指路的正本住工作区 AGENTS.md 本栈写法规则节（#421 栈规则集，随基座
 * tag 换版）；违规 message 只带最短指路（file:line 定位归违规条目）。</p>
 */
@Service
@Slf4j
public class RobustKitLintAppService {

    /** 收尾卡各档违规清单上限（其余以 total 计数呈现——同设计规范 lint 截断先例）。 */
    static final int MAX_LINT_LISTED = 20;

    /** 扫描载荷的状态值：通过／违规（分档清单随附）／未执行（如实、不假装达标）。 */
    static final String LINT_PASSED = "passed";
    static final String LINT_VIOLATIONS = "violations";
    static final String LINT_UNAVAILABLE = "unavailable";

    /** 载荷的两档分节键。 */
    static final String TIER_MANDATORY = "mandatory";
    static final String TIER_PRESET = "preset";

    /** 违规条目的规则名（收尾卡中文呈现面在前端映射；此处工程码单源）。 */
    static final String RULE_BARE_DB_CLIENT = "bare-db-client";
    static final String RULE_BARE_FETCH = "bare-fetch";
    static final String RULE_HANDWRITTEN_SESSION = "handwritten-session";

    /**
     * 强制档判据 ERE：裸 pg/redis 客户端构造（{@code new Pool(}／{@code new Client(}
     * ／{@code new Redis(}／{@code new pg.Pool(}（可选命名空间段）／
     * {@code createClient(}——构造名精确到词边界，SentryClient 之类长名不误命中）
     * ＋裸 {@code fetch(}（词边界——httpFetch( 不误命中；自写包装器内层必含裸
     * fetch，传递命中）。已知边界＝任意改名直用（safeFetch 式）不在此判，归
     * 生产返修缺陷面归纳反哺判据（演进口径）。
     */
    static final String MANDATORY_ERE = "\\bnew[[:space:]]+([[:alnum:]_]+\\.)?"
            + "(Pool|Client|Redis)[[:space:]]*\\("
            + "|\\bcreateClient[[:space:]]*\\("
            + "|\\bfetch[[:space:]]*\\(";

    /**
     * 预置档判据 ERE：手写 cookie 会话（{@code cookies(}——next/headers 的 cookie
     * 面直用）＋裸密码哈希原语（scrypt/scryptSync/bcrypt/pbkdf2——词边界，import
     * 与调用面都命中）。未用会话件（零命中）不是违规——预置档不拦不用。
     */
    static final String PRESET_ERE = "\\bcookies[[:space:]]*\\("
            + "|\\bscrypt\\b|\\bscryptSync\\b|\\bbcrypt\\b|\\bpbkdf2\\b";

    /**
     * 扫描命令装配（容器内真跑 grep；命令全常量与单引号形态——①族纪律：无内嵌
     * 双引号、无用户可控片段）：include 收 TS/JS 面源码；exclude 排除依赖/构建
     * 产物/平台目录/外部资料/设计稿与件目录（robust＝基座健壮件自身实现，合法
     * 原语所在地）。
     */
    static String scanCommandOf(String ere) {
        return "cd " + WorkspaceLayout.ROOT + " && grep -rnE"
                + " --include='*.ts' --include='*.tsx' --include='*.js' --include='*.jsx'"
                + " --exclude-dir=node_modules --exclude-dir=.next --exclude-dir=.git"
                + " --exclude-dir=external --exclude-dir=design --exclude-dir=.platform"
                + " --exclude-dir=robust"
                + " '" + ere + "' .";
    }

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public RobustKitLintAppService(
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 收口扫描（编码 run 收口判据回调内接线，生成/更新/fork 片/裁决轨同盖）：
     * 健壮件不在场（存量工作区）＝{@code null} 不扫描；在场则两档各一 grep、
     * 装配 {@code { status, mandatory?, preset? }} 载荷（各档 {total, violations[]}，
     * 条目 file/line/rule/message——同设计规范 lint 条目形）。全程不抛；探测与
     * 扫描的环境故障如实降级（null／unavailable），不反噬收口链路。
     */
    public Map<String, Object> lintClosingPayload(Long workspaceId) {
        String workspace = Long.toString(workspaceId);
        if (!robustKitPresent(workspace)) {
            return null;
        }
        try {
            ExecResultResponse mandatory = workspaceLifecycleAppService.exec(workspace,
                    new WorkspaceExecCommand(scanCommandOf(MANDATORY_ERE)));
            ExecResultResponse preset = workspaceLifecycleAppService.exec(workspace,
                    new WorkspaceExecCommand(scanCommandOf(PRESET_ERE)));
            if (mandatory.exitCode() >= 2 || preset.exitCode() >= 2) {
                log.warn("[robust-lint] 工作区 {} 基座 lint 扫描异常退出（码 {}/{}）——如实 unavailable",
                        workspaceId, mandatory.exitCode(), preset.exitCode());
                return Map.of("status", LINT_UNAVAILABLE);
            }
            List<Map<String, Object>> mandatoryViolations =
                    parseViolations(mandatory, true);
            List<Map<String, Object>> presetViolations = parseViolations(preset, false);
            if (mandatoryViolations.isEmpty() && presetViolations.isEmpty()) {
                return Map.of("status", LINT_PASSED);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", LINT_VIOLATIONS);
            if (!mandatoryViolations.isEmpty()) {
                payload.put(TIER_MANDATORY, tierPayload(mandatoryViolations));
            }
            if (!presetViolations.isEmpty()) {
                payload.put(TIER_PRESET, tierPayload(presetViolations));
            }
            return payload;
        }
        catch (RuntimeException e) {
            log.warn("[robust-lint] 工作区 {} 基座 lint 扫描执行失败：{}",
                    workspaceId, e.toString());
            return Map.of("status", LINT_UNAVAILABLE);
        }
    }

    /** 健壮件在场探针（#421 判据锚单源——探针命令归 GenerationAppService 单点）。 */
    private boolean robustKitPresent(String workspace) {
        try {
            return workspaceLifecycleAppService.exec(workspace,
                    new WorkspaceExecCommand(GenerationAppService.robustKitProbeCommand()))
                    .exitCode() == 0;
        }
        catch (RuntimeException e) {
            log.warn("[robust-lint] 工作区 {} 健壮件探针失败（按存量工作区不扫描）：{}",
                    workspace, e.toString());
            return false;
        }
    }

    /** 分档载荷：{total（如实）, violations（截 {@link #MAX_LINT_LISTED}）}。 */
    private static Map<String, Object> tierPayload(List<Map<String, Object>> violations) {
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("total", violations.size());
        tier.put("violations", violations.subList(0,
                Math.min(violations.size(), MAX_LINT_LISTED)));
        return tier;
    }

    /**
     * grep 输出行 → 违规条目：{@code ./path:line:content} 形（exit 0 才有输出；
     * 退出码 1＝零命中）。畸形行（无行号段）跳过（防御——grep 输出形是容器侧
     * 契约，不猜）；件目录路径剔除（双保险——grep 侧已 exclude，解析侧再守一道：
     * 经基座件的合法用法不误报）。规则归类按命中行内容（{@code fetch(} → 裸
     * fetch，余＝裸 db 客户端；预置档单规则）。
     */
    private static List<Map<String, Object>> parseViolations(ExecResultResponse result,
            boolean mandatory) {
        List<Map<String, Object>> violations = new ArrayList<>();
        if (result.exitCode() != 0 || result.stdout() == null || result.stdout().isBlank()) {
            return violations;
        }
        for (String line : result.stdout().split("\n")) {
            String path;
            int lineNo;
            String content;
            int firstColon = line.indexOf(':');
            int secondColon = firstColon < 0 ? -1 : line.indexOf(':', firstColon + 1);
            if (secondColon < 0) {
                continue;
            }
            path = line.substring(0, firstColon);
            if (path.startsWith("./")) {
                path = path.substring(2);
            }
            try {
                lineNo = Integer.parseInt(line.substring(firstColon + 1, secondColon).trim());
            }
            catch (NumberFormatException e) {
                continue;
            }
            content = line.substring(secondColon + 1);
            if (isKitPath(path)) {
                continue; // 经基座件的合法用法不误报（件目录双保险）
            }
            violations.add(violationOf(path, lineNo, content, mandatory));
        }
        return violations;
    }

    /** 件目录路径判定（grep --exclude-dir=robust 的解析侧对偶——同判据双源互证）。 */
    private static boolean isKitPath(String path) {
        return path.startsWith("src/lib/robust/") || path.startsWith("src/components/robust/");
    }

    // ---------- 收口回路消费面（载荷形状知识单源——判定归 CoderRunAttempts） ----------

    /** 载荷某档违规清单读口（passed/unavailable／档缺席＝空）。 */
    public static List<Map<String, Object>> tierViolationsOf(Map<String, Object> payload,
            String tier) {
        if (payload == null || !(payload.get(tier) instanceof Map<?, ?> section)) {
            return List.of();
        }
        if (!(section.get("violations") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map)
                .map(item -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> violation = (Map<String, Object>) item;
                    return violation;
                })
                .toList();
    }

    /** 强制档仍有违规（收口结构性拒绝的判据——预置档不在此判）。 */
    public static boolean mandatoryViolationsRemain(Map<String, Object> payload) {
        return !tierViolationsOf(payload, TIER_MANDATORY).isEmpty();
    }

    /** 载荷是否带任何档违规（修轮喂回判据——与设计规范 lint 违规同轮）。 */
    public static boolean hasViolations(Map<String, Object> payload) {
        return !(tierViolationsOf(payload, TIER_MANDATORY).isEmpty()
                && tierViolationsOf(payload, TIER_PRESET).isEmpty());
    }

    /**
     * 修轮现场（喂回执行体的携带物——对偶设计规范 lint 修轮先例）：分档违规清单
     * 逐条原样（file:line 规则：message），强制档点名「修不完会被拒收口」。
     */
    public static String fixRoundScene(Map<String, Object> payload) {
        StringBuilder scene = new StringBuilder(
                "基座 lint 扫描未过——数据访问与外部调用必须走基座预置件（件用法细则见"
                        + "工作区 AGENTS.md 本栈写法规则节），修掉以下违规再收口：");
        appendTier(scene, "强制档（数据访问/外部调用裸客户端直用——修不完收口会被拒）",
                tierViolationsOf(payload, TIER_MANDATORY));
        appendTier(scene, "预置档（会话存储自写——建议改走预置件）",
                tierViolationsOf(payload, TIER_PRESET));
        return scene.toString();
    }

    /**
     * 强制档拒绝回执（修轮用尽仍违规的结构性拒绝——对偶 #420 产出证明拒绝回执形）：
     * 执行体据此定位修什么；喂回经重试现场，耗尽走段级自愈重跑（升档兜底）。
     */
    public static String rejectionReceipt(Map<String, Object> payload) {
        StringBuilder receipt = new StringBuilder(
                "基座 lint 强制档未过（收口被拒）：数据访问与外部调用的裸客户端直用"
                        + "修不完——强制档违规是收口硬门（预置档不拦收口）。改走基座预置件"
                        + "（src/lib/robust——件用法细则见工作区 AGENTS.md 本栈写法规则节）"
                        + "后重新收口，仍违规的裸客户端：");
        for (Map<String, Object> violation : tierViolationsOf(payload, TIER_MANDATORY)) {
            receipt.append('\n').append(violation.get("file")).append(':')
                    .append(violation.get("line")).append(' ')
                    .append(violation.get("rule")).append("：").append(violation.get("message"));
        }
        return receipt.toString();
    }

    /** 分档清单拼装（档零违规不占行）。 */
    private static void appendTier(StringBuilder scene, String tierTitle,
            List<Map<String, Object>> violations) {
        if (violations.isEmpty()) {
            return;
        }
        scene.append('\n').append(tierTitle).append("：");
        for (int i = 0; i < violations.size(); i++) {
            Map<String, Object> violation = violations.get(i);
            scene.append('\n').append(i + 1).append(". ").append(violation.get("file"))
                    .append(':').append(violation.get("line")).append(' ')
                    .append(violation.get("rule")).append("：").append(violation.get("message"));
        }
    }

    /** 违规条目装配（message＝最短件用法指路——细则正本住 AGENTS.md 栈规则集）。 */
    private static Map<String, Object> violationOf(String path, int line, String content,
            boolean mandatory) {
        String rule;
        String message;
        if (!mandatory) {
            rule = RULE_HANDWRITTEN_SESSION;
            message = "会话存储自写——会话与密码一律走 src/lib/robust/session"
                    + "（createSession/getSession/destroySession/hashPassword，redis 挂了降级"
                    + "不挂死），不手写 cookie 会话、不裸用密码哈希原语";
        }
        else if (content.contains("fetch(")) {
            rule = RULE_BARE_FETCH;
            message = "裸 fetch——外部 HTTP 一律走 src/lib/robust/http 的 httpFetch/httpJson"
                    + "（默认超时＋错误归一），不用裸 fetch";
        }
        else {
            rule = RULE_BARE_DB_CLIENT;
            message = "数据访问裸客户端直用——pg/redis 一律走 src/lib/robust/data-access 的"
                    + " query/cacheGet/cacheSet（超时＋瞬时重试＋降级内置），不 new Pool/"
                    + "Client/Redis、不 createClient";
        }
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("file", path);
        violation.put("line", line);
        violation.put("rule", rule);
        violation.put("message", message);
        return violation;
    }
}
