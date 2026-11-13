package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.business.project.application.BackofficeReclaimAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeReclaimResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 后台一键回收 REST（#490，/api/backoffice 机机签名——五头 HMAC 强制闸，见
 * {@link com.semgravio.aiplatform.config.WebMvcConfig}；#473/ADR-0045「一键回收」：
 * 删除＋清残＋三清报告一次完成）：项目级（删后四面〔注册表行/项目行/容器/卷〕
 * 对账报告——复用删除级联内核＋#485 销毁终态一致内核，在飞 run 先级联停止承
 * #477）＋工作区级（单台三清，项目其余部分不动——#488 在册失联行的人工裁决
 * 出口）。运维通道不经用户会话凭证（账户属主项目无凭证删不动的缺口就此覆盖，
 * 不再裸操改挂属主）——签名面担保调用应用，归属不查。append-only 留痕
 * （{@code wsp_reclaim_actions}）：操作者＋四面/三面事实全量落档，成功落痕、
 * 未成不留痕。错误码前缀随寻址对象（项目 PRJ_001、工作区 WSP_001）。
 */
@RestController
@RequireSignature
@Tag(name = "Backoffice Reclaim", description = "后台一键回收：项目级/工作区级 reclaim（删除＋清残＋三清报告，机机签名＋留痕）")
public class BackofficeReclaimController {

    private final BackofficeReclaimAppService appService;

    public BackofficeReclaimController(BackofficeReclaimAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/api/backoffice/projects/{id}/reclaim")
    @Operation(summary = "项目级一键回收（删除＋清残＋四面报告）",
            description = "删除＋清残＋三清校验报告一次完成（#473 故事 6）：在飞 run "
                    + "<b>先级联停止</b>（承 #477 停止面——置停＋cancel(true) 中断在途，"
                    + "不留僵尸任务对着已删容器自救）→ 工作区销毁（<b>复用 #485 销毁终态"
                    + "一致内核</b>：收敛互斥双向串行＋尾部三清校验，非另写一套）→ "
                    + "prj_* 真删级联（受理即删语义与用户面 DELETE 同内核）。回执＝"
                    + "四面（注册表行/项目行/容器/卷）逐面 CLEAN/RESIDUE/UNKNOWN＋"
                    + "residueFree 零残留判据（UNKNOWN 不算清——销毁未全成/探查未成"
                    + "不冒充清，残留如实红、归收编通道）。物理销毁尽力而为不阻断"
                    + "记录删除（受理即删）。运维通道不经用户会话凭证——无凭证属主的"
                    + "账户项目照收（归属不查，签名面担保调用应用）。X-User-Id/"
                    + "X-User-Name 透传头落痕（append-only wsp_reclaim_actions，缺头"
                    + "落空 NULL——照 #489 清扫留痕口径；未成动作不留痕）。项目不存在"
                    + "404 PRJ_001。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_001"})
    public ApiResponse<BackofficeReclaimResponse> reclaimProject(@PathVariable String id) {
        return ApiResponse.ok(appService.reclaimProject(
                Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND), currentOperator(),
                LocalDateTime.now()));
    }

    @PostMapping("/api/backoffice/workspaces/{id}/reclaim")
    @Operation(summary = "工作区级一键回收（单台三清，项目其余部分不动）",
            description = "处置单点残留而不动整个项目（#473 故事 7）：单台工作区三清"
                    + "（容器/卷/注册表行，#485 销毁终态一致内核），<b>项目行与其余"
                    + "资产不动</b>（回执 projectRow＝UNTOUCHED 如实分示）；#488 在册"
                    + "失联行（lostAt 标记）的人工裁决出口——确认后经本端点回收，"
                    + "标记行随注册行级联消亡。所属项目有在飞 run 先级联停止（承 "
                    + "#477）；无所属（失联裁决/fork）无停止面可挂，runsStopped 如实"
                    + "false。回执三面逐面 CLEAN/RESIDUE/UNKNOWN＋residueFree（UNKNOWN"
                    + "不算清）。运维通道不经用户会话凭证。操作者透传头落痕同项目级"
                    + "（未成动作不留痕）。工作区不存在（含畸形 id）404 WSP_001。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"WSP_001"})
    public ApiResponse<BackofficeReclaimResponse> reclaimWorkspace(@PathVariable String id) {
        return ApiResponse.ok(appService.reclaimWorkspace(id, currentOperator(),
                LocalDateTime.now()));
    }

    /**
     * 当前操作者（#490，照 #489 资源面先例）：{@code X-User-Id}/{@code X-User-Name}
     * 透传头经 RequestContext 读出落痕回收行（admin 侧管理员标识，签名面明示信任、
     * 不校验真实性）；缺头/无上下文为 {@code null}——落空口径。Id 两形转换在此一次
     * 完成（上下文 Long → 外域标识字符串，存储不混型）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
