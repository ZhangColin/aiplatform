package com.aieducenter.aiplatform.base.skills.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.skills.application.BackofficeSkillAppService;
import com.aieducenter.aiplatform.base.skills.application.BackofficeSkillPrecheckAppService;
import com.aieducenter.aiplatform.base.skills.application.SkillDraftAppService;
import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillInstallCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillSlotAssignCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.command.SkillUpdateCommand;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDraftDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDraftSummaryResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillPrecheckResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillSummaryResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillUpdateResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillUpdateTraceResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSlotAssignmentResponse;
import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台技能库管理 REST 面（#246-T1/#247＋T2/#248＋T3/#249＋T4/#250＋#255＋#259
 * 草稿只读面，机机
 * 签名——五头 HMAC 强制闸，见 {@link com.aieducenter.aiplatform.config.WebMvcConfig}）：
 * 清单 / 详情（审核面）＋写口——安装（git 仓库快照固化）/ 停用⇄启用 / 卸载
 * / 槽位指派读写（三职能槽位整包替换）/ 指派双头预检（#255 非阻断提示，只读）
 * / 显式更新＋版本留痕读面＋技能草稿清单/详情（#259 自产线——自荐草稿的人审
 * 分诊面，只读；晋升/拒绝写口 T2）。内置（classpath 合成）与库中安装技能同权呈现；
 * 技能柄为 opaque 串两形制（内置 {@code builtin:<技能名>}／安装 TSID 十进制串）。
 * 清单行带「有新版」标记（定期只读检查远端 HEAD，更新永远显式点——永不自动
 * 跟新，ADR-0021）。错误码前缀 SKL_（SKL_001～SKL_015）。操作者透传头
 * {@code X-User-Id}/{@code X-User-Name} 全程落痕（安装/启停/指派/更新必留痕，
 * 缺头 SKL_009；预检只读不留痕、卸载无行可留不留痕——admin 侧自有操作日志）。
 */
@RestController
@RequestMapping("/api/backoffice/skills")
@RequireSignature
@Tag(name = "Backoffice Skills", description = "后台技能库管理：清单 / 详情 / 安装 / 停用⇄启用 / 卸载 / 槽位指派读写 / 指派双头预检 / 显式更新＋版本留痕（机机签名）")
public class BackofficeSkillController {

    private final BackofficeSkillAppService appService;

    private final BackofficeSkillPrecheckAppService precheckAppService;

    private final SkillDraftAppService draftAppService;

    public BackofficeSkillController(BackofficeSkillAppService appService,
            BackofficeSkillPrecheckAppService precheckAppService,
            SkillDraftAppService draftAppService) {
        this.appService = appService;
        this.precheckAppService = precheckAppService;
        this.draftAppService = draftAppService;
    }

    @GetMapping
    @Operation(summary = "技能清单（内置＋安装同权，不分页）",
            description = "平台全部技能：内置（classpath 合成，来源=1）与库中安装"
                    + "（来源=2）同权呈现，空库时仍呈现内置技能。条目字段：名称、"
                    + "description、来源 code（1=内置 2=安装）与来源名、来源包"
                    + "（内置 null）、版本标识（装时 commit，内置 null）、状态"
                    + "（1=启用 2=停用，内置恒 1）、最近管理动作操作者（安装＝装者、"
                    + "启停＝最近动作者，内置 null）、远端有新版标记 updateAvailable"
                    + "（来源包级：定期只读检查远端 HEAD 与装时版本不同即 true——"
                    + "更新走显式 POST /update，平台永不自动跟新；null＝未检查过，"
                    + "内置恒 null）。排序服务端定死：内置在前"
                    + "（名称序）、安装在后（来源包、名称序）。技能库是有界目录"
                    + "（装什么是运营决策），不分页不过滤。id 为 opaque 串两形制"
                    + "（builtin:<技能名>／TSID 十进制串），作详情/写口寻址柄。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeSkillSummaryResponse>> skills() {
        return ApiResponse.ok(appService.skills());
    }

    @GetMapping("/{id}")
    @Operation(summary = "技能详情（元数据＋frontmatter＋正文＋scripts，审核面）",
            description = "读全文按技能审核指引判安装审核（docs/agents/"
                    + "skill-audit-guide.md——注入面＋方法论重叠面＋description"
                    + " 三要素）：元数据（与清单行同形）＋ frontmatter 全量"
                    + "（解析态键值，含 name/description）＋正文全文（frontmatter"
                    + " 剥离后的 SKILL.md body）＋ scripts/ 全文——所见即运行时"
                    + "注入面。id 取清单行原值（opaque 串两形制）。技能不存在"
                    + "（含未寻址内置名/TSID、畸形柄）404 SKL_001。需要机机"
                    + "签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001"})
    public ApiResponse<BackofficeSkillDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(id));
    }

    @PostMapping("/install")
    @Operation(summary = "安装技能包（git 仓库快照固化）",
            description = "装时 clone 解析全部 SKILL.md 固化入库（ADR-0021 快照安装，"
                    + "不订阅远端）：来源包＝规范化仓库地址（trim、去尾斜杠与 .git——"
                    + "同源去重键）、版本标识＝装时 HEAD commit（快照锚）、状态＝启用、"
                    + "操作者＝装者（X-User-Id/X-User-Name 透传头，缺头 400 SKL_009）。"
                    + "excludeDirs 可勾选排除目录段：技能的仓库相对路径任一段命中即"
                    + "不入库（如 deprecated 类目整支排除；段名精确匹配）。解析管道"
                    + "与内置同源（审核面所见即运行时注入面）。失败 fail-fast 整体"
                    + "不入库：地址空 400 SKL_002、同源重复安装 409 SKL_003（更新"
                    + "走显式更新动作）、克隆失败/超时 502 SKL_004、未解析到技能"
                    + "400 SKL_005、SKILL.md 不合格 400 SKL_006、包内重名 400 "
                    + "SKL_007。回执＝本次装入的条目清单（清单行同形）。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_002", "SKL_003", "SKL_004", "SKL_005", "SKL_006", "SKL_007", "SKL_009"})
    public ApiResponse<List<BackofficeSkillSummaryResponse>> install(@RequestBody SkillInstallCommand command) {
        return ApiResponse.ok(appService.install(command, currentOperator()));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用技能（可逆开关）",
            description = "退出装配候选（装配合成只收启用行）、不丢库行；误伤可经"
                    + " enable 恢复。X-User-Id/X-User-Name 透传头自动落痕（最近"
                    + "管理动作操作者）——缺头 400 SKL_009（技能库写操作必留痕，"
                    + "知识治理同款无落空通道）。重复停用幂等（操作者留最近一次）。"
                    + "寻址 TSID 柄；内置技能非库行无状态迁移——builtin: 柄/畸形柄/"
                    + "未寻址 TSID 同语义 404 SKL_001。回执＝翻转后清单行。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001", "SKL_009"})
    public ApiResponse<BackofficeSkillSummaryResponse> disable(@PathVariable String id) {
        return ApiResponse.ok(appService.disable(
                Tsid.resolve(id, SkillMessage.SKILL_NOT_FOUND), currentOperator()));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用技能（恢复装配候选）",
            description = "停用的可逆侧：恢复参与装配合成。X-User-Id/X-User-Name"
                    + " 透传头自动落痕（口径同 disable，缺头 400 SKL_009）。重复"
                    + "启用幂等。寻址与 404 语义同 disable（TSID 柄；内置柄 404）。"
                    + "回执＝翻转后清单行。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001", "SKL_009"})
    public ApiResponse<BackofficeSkillSummaryResponse> enable(@PathVariable String id) {
        return ApiResponse.ok(appService.enable(
                Tsid.resolve(id, SkillMessage.SKILL_NOT_FOUND), currentOperator()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "卸载技能（库行删除，不可逆）",
            description = "快照行删除即彻底出库（快照物无历史不漂移约束）；清单/"
                    + "详情/装配均不可见。卸载守卫：有指派在身（任一职能槽位）"
                    + "的技能拒绝卸载（409 SKL_008，先解绑再卸）。无行可留不留痕"
                    + "（全局审计流水不建——admin 侧自有操作日志，知识删除同款）。"
                    + "回执＝删除前终态（确认移除了什么）。寻址 TSID 柄；内置技能"
                    + "不可卸——builtin: 柄/畸形柄/未寻址 TSID 同语义 404 SKL_001、"
                    + "重复卸载 404。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001", "SKL_008"})
    public ApiResponse<BackofficeSkillSummaryResponse> uninstall(@PathVariable String id) {
        return ApiResponse.ok(appService.uninstall(Tsid.resolve(id, SkillMessage.SKILL_NOT_FOUND)));
    }

    @GetMapping("/assignments/{slot}")
    @Operation(summary = "槽位指派读面（三职能槽位各自独立）",
            description = "该职能槽位当前指派的技能清单（与技能清单行共形、含停用行"
                    + "——启停是可逆开关指派关系随行保留，运营可见「指派了但已停用」"
                    + "实态；停用行不参与装配合成）。槽位三把：main=主智能体 / "
                    + "executor=run 执行体 / subagent=子智能体，未知槽位 404 SKL_010。"
                    + "装配生效语义（ADR-0021）：装配合成＝内置∪该槽位已指派且启用，"
                    + "动态查库——指派/启停变更后智能体下一轮自然生效、进行中 run "
                    + "不定格不打断。回执与 PUT 同形。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"SKL_010"})
    public ApiResponse<BackofficeSlotAssignmentResponse> slotAssignments(@PathVariable String slot) {
        return ApiResponse.ok(appService.slotAssignments(slot));
    }

    @PutMapping("/assignments/{slot}")
    @Operation(summary = "槽位指派整包替换（清单即终态）",
            description = "PUT 全量语义：skillIds 即该槽位终态（未列入即解绑、空清单"
                    + "＝清空），支持整包批量勾选（admin 侧按来源包勾满后送全量）。"
                    + "指派目标只收安装库行 TSID 柄：内置 builtin: 柄不可指派（400 "
                    + "SKL_011——内置随平台发版，装配按配置挂载）；未寻址/畸形 TSID "
                    + "404 SKL_001（先卸载后指派的不变窗口同语义）。X-User-Id/"
                    + "X-User-Name 透传头落痕（整包替换留最近动作者，缺头 400 "
                    + "SKL_009）。槽位寻址同 GET（未知 404 SKL_010）。生效语义＝"
                    + "动态查库：变更后下一轮自然生效，不新增智能体实例（工厂缓存键"
                    + "不含技能集）。回执＝替换后读面（与 GET 同形）。需要机机签名"
                    + "（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001", "SKL_009", "SKL_010", "SKL_011"})
    public ApiResponse<BackofficeSlotAssignmentResponse> assignSlot(@PathVariable String slot,
            @RequestBody SkillSlotAssignCommand command) {
        return ApiResponse.ok(appService.assignSlot(slot, command, currentOperator()));
    }

    @PostMapping("/assignments/{slot}/precheck")
    @Operation(summary = "指派双头预检（非阻断提示，只读）",
            description = "指派确认前对候选集做方法论重叠判定（#255，ADR-0021 分层"
                    + "纪律的执行面）：对照双头——该槽位生效工作协议（智能体运营"
                    + "配置生效值：库值优先、缺省回落）＋该槽位已指派启用技能＋候选"
                    + "集内其他技能，由模型判定方法论重叠并返回<b>非阻断提示</b>"
                    + "（提示是输入不是门，判定权留管理员）。请求体与 PUT 指派同形"
                    + "（skillIds＝候选清单，寻址同源：内置柄 400 SKL_011、未寻址/"
                    + "畸形 TSID 404 SKL_001、未知槽位 404 SKL_010）。回执两件："
                    + "executed（true＝判定已执行；false＝未执行——判定失败/超时/"
                    + "输出不可解析，如实标注不伪装成「确认无重叠」，预检组件故障"
                    + "不阻塞运营、指派照常可完成）＋ hints（重叠提示列表，每条："
                    + "skills 涉及的候选技能、counterpart 对照侧〔「工作协议」或"
                    + "对侧技能名〕、overlap 重叠内容、resolution 消解方向；空列表"
                    + "＋executed=true＝确认无重叠）。subagent 槽无运营配置正本："
                    + "对照＝已指派＋候选集（无协议面），机制同一不特判。判定引擎＝"
                    + "平台智能体内核一次性会话（flash 档专用配置键、专用计量标记"
                    + "skillcheck），全程只读——不落库、不留痕、不改指派、不拦后续"
                    + "指派调用。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001", "SKL_010", "SKL_011"})
    public ApiResponse<BackofficeSkillPrecheckResponse> precheckSlot(@PathVariable String slot,
            @RequestBody SkillSlotAssignCommand command) {
        return ApiResponse.ok(precheckAppService.precheck(slot, command));
    }

    @PostMapping("/update")
    @Operation(summary = "显式更新技能包（重拉快照＋版本留痕）",
            description = "按来源包整体重拉快照入库（ADR-0021：更新永远显式点，"
                    + "平台永不自动跟新远端——清单「有新版」标记只提示、不动作）。"
                    + "sourcePackage 取清单行原值回传（服务端再规范化，同源身份"
                    + "单源）；排除名单＝装时持久化口径（更新不还魂装时排除的目录）。"
                    + "翻新语义：同名行原地翻新（id/状态/指派跨更新保留——TSID 柄"
                    + "稳定）、新技能插入（启用）、远端已删行移除——移除有指派在身"
                    + "的技能整体拒绝（409 SKL_013，先解绑再更新）。远端未前进＝"
                    + "幂等回执（from==to、不落痕不写行）。每次前进必留版本痕"
                    + "（from→to＋操作者，经 update-traces 可查）。X-User-Id/"
                    + "X-User-Name 透传头落痕（缺头 400 SKL_009）。失败族：来源包空"
                    + " 400 SKL_014、未安装 404 SKL_012、克隆失败/超时 502 SKL_004、"
                    + "远端未解析到技能 400 SKL_005、包内重名 400 SKL_007。回执＝"
                    + "from/to 版本＋被移除技能名＋更新后终态行集。需要机机签名"
                    + "（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_004", "SKL_005", "SKL_007", "SKL_009", "SKL_012", "SKL_013", "SKL_014"})
    public ApiResponse<BackofficeSkillUpdateResponse> update(@RequestBody SkillUpdateCommand command) {
        return ApiResponse.ok(appService.update(command, currentOperator()));
    }

    @GetMapping("/drafts")
    @Operation(summary = "技能草稿清单（活跃面＝在途，人审队列）",
            description = "智能体自荐的技能草稿（#259 自产线，ADR-0022 库制草稿）："
                    + "跨项目全部<b>在途</b>草稿按自荐时间倒序（最近先）——终态"
                    + "（已晋升/已拒绝）不列活跃面、详情仍可按 id 查。行字段：名称、"
                    + "简介、血统三件（来源项目 id／来源 run 标识／来源槽位键"
                    + " main/executor/subagent）、写入前静态扫描判定（SAFE/CAUTION——"
                    + "DANGEROUS 已在写入口拒收不落库）、状态（恒 1=在途）与自荐"
                    + "时刻。草稿不参与任何装配（未审内容不影响任何 run）。不分页"
                    + "（人审是天然瓶颈，对齐技能清单有界目录先例）。id 为 TSID "
                    + "十进制串。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeSkillDraftSummaryResponse>> drafts() {
        return ApiResponse.ok(draftAppService.drafts());
    }

    @GetMapping("/drafts/{id}")
    @Operation(summary = "技能草稿详情（正文＋扫描回执＋血统，审核面）",
            description = "草稿全文按自产审核口径判读（docs/agents/skill-audit-"
                    + "guide.md——真伪／重叠／description 三要素／血统可溯：来源 run "
                    + "的外部资料接触史必查）：清单行同形字段＋正文全文（自荐只有"
                    + "正文——a-only 无 scripts）＋扫描 findings 逐条留档"
                    + "（patternId/severity/category/file/line/matchText/description，"
                    + "空列表＝无发现）＋终态留痕字段（审核操作者/时刻/拒绝理由——"
                    + "晋升/拒绝端点 T2 落地后回填，在途恒 null）。任意状态可查"
                    + "（终态留档）。id 取清单行原值（TSID 十进制串）；草稿不存在"
                    + "（含未寻址/畸形 TSID）404 SKL_015。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"SKL_015"})
    public ApiResponse<BackofficeSkillDraftDetailResponse> draftDetail(@PathVariable String id) {
        return ApiResponse.ok(draftAppService.draft(
                Tsid.resolve(id, SkillMessage.SKILL_DRAFT_NOT_FOUND)));
    }

    @GetMapping("/update-traces")
    @Operation(summary = "更新留痕读面（历史版本可查）",
            description = "该来源包全部显式更新留痕（from→to 版本＋操作者＋时刻），"
                    + "按时间倒序（最近先）。append-only：卸载不删留痕——历史事实"
                    + "不随库行消失，装时版本经链条首个 from 回溯。sourcePackage"
                    + " 取清单行原值（必填，空/缺 400 SKL_014）。需要机机签名"
                    + "（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_014"})
    public ApiResponse<List<BackofficeSkillUpdateTraceResponse>> updateTraces(
            @RequestParam(required = false) String sourcePackage) {
        return ApiResponse.ok(appService.updateTraces(sourcePackage));
    }

    /**
     * 当前操作者（#248）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）。安装/启停必留痕——缺头不为落空，域面守卫 SKL_009 拦截（知识治理
     * KNW_006 同款）。Id 两形转换在此一次完成（上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
