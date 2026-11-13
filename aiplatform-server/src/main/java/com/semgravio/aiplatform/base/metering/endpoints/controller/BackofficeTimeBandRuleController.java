package com.semgravio.aiplatform.base.metering.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.metering.application.BackofficeTimeBandRuleAppService;
import com.semgravio.aiplatform.base.metering.application.dto.command.OpenTimeBandRuleCommand;
import com.semgravio.aiplatform.base.metering.application.dto.command.RewindowTimeBandRuleCommand;
import com.semgravio.aiplatform.base.metering.application.dto.response.TimeBandRuleResponse;
import com.semgravio.aiplatform.base.metering.application.dto.response.TimeBandRuleRewindowResponse;
import com.semgravio.aiplatform.base.metering.domain.error.MeteringMessage;
import com.semgravio.aiplatform.base.metering.domain.model.Operator;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 后台时段规则 REST 面（#497／ADR-0044 峰谷 1/4，机机签名——五头 HMAC 强制闸，
 * 见 {@link com.semgravio.aiplatform.config.WebMvcConfig}）：时段规则＝供应商
 * 峰谷定价的时段定义（通道 × 生效区间 × 星期口径 × UTC 窗口行），供应商侧可
 * 运营数据——运营者经签名通道种入并维护 DeepSeek 峰窗规则，无需改代码发版。
 * 错误码前缀 METER_（行不存在 METER_015、非当前行 METER_016、区间重叠 METER_008
 * 〔对称单价表〕、字段 METER_013、关行时点 METER_014）。
 */
@RestController
@RequestMapping("/api/backoffice/time-band-rules")
@RequireSignature
@Tag(name = "Backoffice Time Band Rules", description = "后台时段规则：行清单 / 开行 / 原子改窗 / 停用（机机签名）")
public class BackofficeTimeBandRuleController {

    private final BackofficeTimeBandRuleAppService appService;

    public BackofficeTimeBandRuleController(BackofficeTimeBandRuleAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "时段规则行清单（含历史版本，分页）",
            description = "现行与历史行全量（规则史全貌），排序服务端定死＝生效起点倒序"
                    + "（新版本在前，同起点 id 倒序稳定——一版本多窗口行同起点相邻）。"
                    + "provider 为匹配键＝精确等值过滤、可缺省（缺省＝全量行）；"
                    + "effectiveTo 为 null 即当前行。行带操作者两列（该行最近管理动作"
                    + "——开行/改窗落新行、停用落被关行；存量行/无头请求——含种子脚本"
                    + "种入行——落 null）。page 1 基（缺省 1）、size 缺省 20（上界 100）。"
                    + "过滤参数绑定失败（非法分页值）400（带字段明细，框架统一信封）。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST"})
    public ApiResponse<PageResponse<TimeBandRuleResponse>> entries(
            @RequestParam(required = false) String provider,
            Pagination pagination) {
        return ApiResponse.ok(appService.entries(provider, pagination));
    }

    @PostMapping
    @Operation(summary = "开行（空通道首开——种子脚本通道）",
            description = "对指定通道新开一个规则版本：一单 N 时段窗口行原子落"
                    + "（每项＝规则表一行〔通道 × 生效区间 × 星期口径 × UTC 窗口〕，"
                    + "同单各窗口共享生效起点）——DeepSeek 峰窗 01:00–04:00＋"
                    + "06:00–10:00 工作日（dayMask 31）两窗口一单种入。写口唯一化"
                    + "到管理 API 后本端点是唯一初始插入通道（#497：种子数据经幂等"
                    + "签名脚本走本端点种入，脚本侧幂等＝通道已有任意行即不再开行）。"
                    + "effectiveFrom 可回溯（种子敞口覆盖存量事件）、可指定未来时点"
                    + "（预发布），缺省即时。band 契约为 Integer code（1=峰 2=谷）；"
                    + "dayMask＝星期口径 bitmask（ISO 周序 Mon=1 Tue=2 … Sun=64，"
                    + "合法域 1..127）；startMinute/endMinute＝UTC 分钟（end 不含、"
                    + "须大于起点——跨午夜窗口拆两行）。X-User-Id/X-User-Name 透传头"
                    + "自动落痕新行（缺头落空——种子脚本即落空口径）。窗口项字段不"
                    + "完整/星期口径越界/跨午夜窗口 400 METER_013；同通道区间重叠"
                    + "（跨区间或同起点）409 METER_008。需要机机签名")
    @ErrorCodes({"METER_008", "METER_013"})
    public ApiResponse<List<TimeBandRuleResponse>> open(@RequestBody OpenTimeBandRuleCommand command) {
        return ApiResponse.ok(appService.open(command, currentOperator()));
    }

    @PostMapping("/{id}/rewindow")
    @Operation(summary = "原子改窗（单调用关当前版本全部行＋开新版本）",
            description = "同事务两段：寻址行须当前版本成员（版本任一行皆可达——关行在"
                    + "版本级非单行，同版本多窗口行共享区间），被关版本全部行落 "
                    + "effectiveTo＝新版本起点（保留其原开行操作者，不被改写）；新版本"
                    + "窗口清单整版本替换语义（清单即终态）。effectiveFrom 可指定"
                    + "（ISO-8601 Instant，含未来时点＝预发布，对齐供应商调窗口公告期）；"
                    + "缺省＝即时。服务端补同通道生效区间重叠校验（本表无唯一约束兜底"
                    + "——同版本多窗口行共享起点，同起点拒由服务端拦）。X-User-Id/"
                    + "X-User-Name 透传头自动落痕新行（缺头落空，#160 口径）。中途任一"
                    + "守卫失败两段都不动。行不存在 404 METER_015；窗口项字段不完整"
                    + " 400 METER_013；起点早于被关版本起点 400 METER_014；目标非"
                    + "当前行 409 METER_016；区间重叠（跨区间或同起点）409 METER_008。"
                    + "需要机机签名")
    @ErrorCodes({"METER_008", "METER_013", "METER_014", "METER_015", "METER_016"})
    public ApiResponse<TimeBandRuleRewindowResponse> rewindow(
            @PathVariable String id, @RequestBody RewindowTimeBandRuleCommand command) {
        return ApiResponse.ok(appService.rewindow(
                Tsid.resolve(id, MeteringMessage.TIME_BAND_RULE_NOT_FOUND), command,
                currentOperator()));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "停用（即时生效，关版本不接新行）",
            description = "关当前版本全部行（effectiveTo＝现在），不开新版本——此后该"
                    + "通道 ts 无窗口命中＝不归带（引擎归段票接线后生效；读时归段未接"
                    + "线前不影响现行成本面）。对未生效的预发布版本停用＝钳到自身起点"
                    + "成空区间（从未生效）。X-User-Id/X-User-Name 透传头自动落痕被关"
                    + "版本各行（停用不接新行，被关行是唯一落点；缺头落空，#160 口径）。"
                    + "行不存在 404 METER_015；目标非当前行 409 METER_016。"
                    + "需要机机签名")
    @ErrorCodes({"METER_015", "METER_016"})
    public ApiResponse<List<TimeBandRuleResponse>> deactivate(@PathVariable String id) {
        return ApiResponse.ok(appService.deactivate(
                Tsid.resolve(id, MeteringMessage.TIME_BAND_RULE_NOT_FOUND), currentOperator()));
    }

    /**
     * 当前操作者（#160 口径）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）；缺头/无上下文为 {@code null}——落空口径，开新版本/停用被关行的操作者
     * 两列落 NULL。Id 两形转换在此一次完成（上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
