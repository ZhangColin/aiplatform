package com.aieducenter.aiplatform.business.project.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.model.Operator;
import com.aieducenter.aiplatform.business.project.application.BackofficeWorkspaceAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceSummaryResponse;

/**
 * 后台沙箱面 REST（#173 观测 + #174 动作，/api/backoffice/workspaces 机机签名——
 * 五头 HMAC 强制闸，见 {@link com.aieducenter.aiplatform.config.WebMvcConfig}）：
 * 观测（清单/详情——期望态与 docker 实态两列如实分示）＋四干预动作（唤醒等就绪/
 * 强制休眠/强制重建/封存，append-only 留痕操作者）。沙箱事实与动作归
 * base.workspace（跨 BC 走应用层），所属项目引用与 run 在途事实在本域拼装递入。
 * 错误码前缀 WSP_（观测面的资源是工作区，前缀随资源不随宿主包）：不存在 WSP_001、
 * run 在途拒 WSP_015、封存包不可取 WSP_016、收敛任务在途 WSP_017、状态边界
 * WSP_009（信封 code 为数字业务码＝域码×1000＋序号：1001/1009/1015/1016/1017）。
 */
@RestController
@RequestMapping("/api/backoffice/workspaces")
@RequireSignature
@Tag(name = "Backoffice Workspaces", description = "后台沙箱：观测清单/详情＋四动作（唤醒/强制休眠/强制重建/封存，机机签名）")
public class BackofficeWorkspaceController {

    private final BackofficeWorkspaceAppService appService;

    public BackofficeWorkspaceController(BackofficeWorkspaceAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "沙箱清单（期望态/实态过滤，分页）",
            description = "存储观测工作清单：新沙箱在前（TSID 倒序）。两过滤维度均可缺省"
                    + "（缺省＝全量）、可组合：① desired 期望态单选，Integer code"
                    + "（1=运行 2=休眠 3=封存，DB 意图侧）；② actual 容器实态单选，"
                    + "Integer code（1=运行中 2=已停止 3=无容器 4=未知——docker 现场探查，"
                    + "实态不落库，过滤在探查后内存完成，total 如实＝筛后计数；"
                    + "「期望运行而实态无容器」即漂移行，actual=3 即捞漂移清单）。"
                    + "每行＝项目引用（无所属项目为 null）＋期望态/实态两列＋last-touch"
                    + "＋卷大小（旁路容器 du 全卷、含可重建缓存，字节——封存容缺 null、"
                    + "探查失败亦 null）＋封存信息（时刻/包大小）。page 1 基（缺省 1）、"
                    + "size 缺省 20（上界 100）；排序服务端定死不开放。实态与卷大小逐行"
                    + "现场探查（docker 子进程），页越大越慢——观测页不必贪大。"
                    + "过滤参数绑定失败走框架统一信封：非法期望态/实态 code 400"
                    + "（带合法取值表）、非数值分页 400（带字段明细）。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST"})
    public ApiResponse<PageResponse<BackofficeWorkspaceSummaryResponse>> workspaces(
            @RequestParam(required = false) DesiredState desired,
            @RequestParam(required = false) ContainerState actual,
            Pagination pagination) {
        return ApiResponse.ok(appService.workspaces(desired, actual, pagination));
    }

    @GetMapping("/{id}")
    @Operation(summary = "沙箱详情（全量字段＋所属项目引用）",
            description = "清单行超集：另带置备失败原因（FAILED 态排障）、封存包寻址键、"
                    + "审计时间列与中间件资源清单（连接串原文，容器内回环形态）。"
                    + "期望态/实态/卷大小同清单口径（现场探查、封存容缺）。"
                    + "所属项目引用软引用容缺（null 呈现）——工作区先于项目存在。"
                    + "需要机机签名（五头 HMAC）；工作区不存在（含畸形 id）404 WSP_001")
    @ErrorCodes({"WSP_001"})
    public ApiResponse<BackofficeWorkspaceDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(id));
    }

    @PostMapping("/{id}/wake")
    @Operation(summary = "唤醒（等就绪）",
            description = "收敛到 READY＋（已生成项目）应用在服，同步等结果：健康即回；"
                    + "容器缺失/被杀（#168 型漂移）走幂等重建（卷保留、数据不动）；"
                    + "封存态走深度唤醒（解包回卷＋依赖重装，分钟级）。run 在途不受限"
                    + "（唤醒不动数据面）。响应＝动作后的观测详情（新事实）。"
                    + "X-User-Id/X-User-Name 透传头自动落痕动作行（缺头落空）。"
                    + "工作区不存在 404 WSP_001；非 DEV 400 WSP_007；封存包不可读"
                    + "（深度唤醒保持封存态）404 WSP_016；置备等待超时 500 WSP_011。"
                    + "需要机机签名")
    @ErrorCodes({"WSP_001", "WSP_007", "WSP_011", "WSP_016"})
    public ApiResponse<BackofficeWorkspaceDetailResponse> wake(@PathVariable String id) {
        return ApiResponse.ok(appService.wake(id, currentOperator()));
    }

    @PostMapping("/{id}/hibernate")
    @Operation(summary = "强制休眠（立即删容器保卷）",
            description = "管理员的即时止损口：删容器保卷、期望态置休眠——闲置计时的"
                    + "手动直达（不等闲置阈值）。已休眠＝幂等成功（补删残留容器）；"
                    + "封存态拒 400 WSP_009（卷已删，先唤醒）；置备在途拒 WSP_009；"
                    + "run 在途拒 409 WSP_015（管理员操作资源面，不打断用户正在"
                    + "进行的生成——要处置先取消 run 或等收口）；收敛任务在途"
                    + "（触碰自愈/扫描封存）409 WSP_017。响应＝动作后的观测详情。"
                    + "操作者透传头落痕同唤醒。需要机机签名")
    @ErrorCodes({"WSP_001", "WSP_007", "WSP_009", "WSP_015", "WSP_017"})
    public ApiResponse<BackofficeWorkspaceDetailResponse> hibernate(@PathVariable String id) {
        return ApiResponse.ok(appService.hibernate(id, currentOperator()));
    }

    @PostMapping("/{id}/rebuild")
    @Operation(summary = "强制重建（rm＋幂等重建）",
            description = "#168 型「预览死了」事故的标准化处置（替代手工 docker 拉）："
                    + "在跑但坏了的容器也杀（卷保留、数据不动），随后走唤醒内核同一"
                    + "重建路径收敛回 READY（已生成项目拉起 8081 应用）。封存态拒"
                    + "400 WSP_009（数据只在包里，空卷重建＝掩埋数据丢失——先唤醒）；"
                    + "置备在途拒 WSP_009；run 在途拒 409 WSP_015；收敛任务在途"
                    + "409 WSP_017；重建重试上限落 FAILED 时 500 WSP_010（可再触发）。"
                    + "响应＝动作后的观测详情。操作者透传头落痕同唤醒。需要机机签名")
    @ErrorCodes({"WSP_001", "WSP_007", "WSP_009", "WSP_010", "WSP_015", "WSP_017"})
    public ApiResponse<BackofficeWorkspaceDetailResponse> rebuild(@PathVariable String id) {
        return ApiResponse.ok(appService.rebuild(id, currentOperator()));
    }

    @PostMapping("/{id}/seal")
    @Operation(summary = "封存（产物同自动封存）",
            description = "动作序同闲置满期的自动封存：删容器 → 整卷打包（仅排可重建"
                    + "缓存，数据库与机密随包）落平台存储 → 期望态置封存＋包元数据"
                    + "→ 删卷（失败由扫描下轮收敛）。RUNNING 起点可用（即时深回收，"
                    + "不用先等休眠满期）；重复封存拒 400 WSP_009（走「唤醒→休眠→"
                    + "封存」周期）；置备在途拒 WSP_009；run 在途拒 409 WSP_015"
                    + "（卷正被 run 读写时打包＝半程数据）；收敛任务在途 409 WSP_017。"
                    + "响应＝动作后的观测详情（含封存包元数据）。操作者透传头落痕"
                    + "同唤醒。需要机机签名")
    @ErrorCodes({"WSP_001", "WSP_007", "WSP_009", "WSP_015", "WSP_017"})
    public ApiResponse<BackofficeWorkspaceDetailResponse> seal(@PathVariable String id) {
        return ApiResponse.ok(appService.seal(id, currentOperator()));
    }

    /**
     * 当前操作者（#174）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕动作行（admin 侧管理员标识，签名面明示信任、不校验
     * 真实性）；缺头/无上下文为 {@code null}——落空口径（照订单/单价表先例）。
     * Id 两形转换在此一次完成（上下文 Long → 外域标识字符串，存储不混型）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
