package com.aieducenter.aiplatform.base.eventhub.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;

/**
 * SSE 事件端点（单端点单流，ADR-0001 + #82 通道合并）：{@code GET /api/events}
 * 一条流承载两族事件——平台通知族 + 智能体事件族（合并通道不合并语义）。
 * eventhub 是唯一 SSE 管道，通道语义归 {@link EventsAppService}。
 *
 * <p>SSE 是呈现通道不是 REST——不套 ApiResponse，返回 {@link SseEmitter}；
 * 事件契约以名册正本（docs/spec/SSE事件清单.md）+ 本端点描述为准。</p>
 *
 * @since 0.1.0
 */
@RestController
@Validated
@RequestMapping("/api/events")
@Tag(name = "事件中心 SSE", description = "SSE 呈现通道（非 REST，不套 ApiResponse）；事件名册正本见 docs/spec/SSE事件清单.md")
public class EventsController {

    private final EventsAppService appService;

    public EventsController(EventsAppService appService) {
        this.appService = appService;
    }

    /**
     * 订阅事件流（两族混载）。Last-Event-ID 请求头作新连/重连分野（#89 断线补发）：
     * 有值（浏览器断线重连自动携带）= 断线补发——先补发命中订阅过滤的智能体缓冲
     * 事件中锚事件之后的窗口（默认 1000 条深，app.agent-events.replay-depth）再进
     * 实时流；无值（缺席或空串）= 新连接/刷新 = 不补发——对话史经 REST 水合
     * （GET /api/projects/{id}/conversation），重放缓冲只承担断线窗口。通知族永不
     * 补发（不进缓冲）。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅事件流（SSE，单端点单流）", description = """
            一条流承载两族事件（合并通道不合并语义）：

            - 平台通知族（状态变化广播）：只作实时呈现、状态以 REST 重查为准，永不补发；
            - 智能体事件族（运行过程流）：带近期事件缓冲的热流——断线重连（浏览器自动
              携带非空 Last-Event-ID）先补发缓冲中锚事件之后命中订阅过滤的事件（断线
              窗口；锚已被逐出或随重启丢失则补发整段缓冲——缓冲内事件必然晚于锚），
              再无缝进实时流；新连接（Last-Event-ID 缺席或空串——含刷新/回访）不补发，
              对话史经 REST 水合（GET /api/projects/{id}/conversation，#89），平台状态
              以查询收敛。缓冲为单实例内存态（重启即失，多实例化时需重估）。

            信封：SSE name 恒为 `event`；id = `{streamId}:{seq}`（通知 streamId=projectId、
            智能体事件 streamId=runId）；data = `{"type","payload","ts"}`（payload 恒为
            对象、内禁 type 键名）。心跳：每 15s 发注释行 `:ping`。

            订阅：`?projectId=` / `?runId=` 过滤（与 payload 关联字段同名，可叠用 AND）；
            缺省 = 只收平台通知族（智能体事件族只投递给带过滤的订阅——过程细节是项目
            内事实）。智能体事件带 projectId（编排桥接注入）。

            名册（type → 说明，payload 除关联字段外）：

            | type | 族 | payload 字段 |
            |---|---|---|
            | workspace-created / preview-ready / workspace-destroyed / document-updated / project-renamed / order-status-changed | 通知 | projectId（+ 各自载荷） |
            | run-start | 智能体·生命周期 | runId, prompt, model, engine, agent（可空——main/executor 配置键） |
            | error | 智能体·生命周期 | runId, message |
            | run-finish | 智能体·生命周期 | runId, sessionId, engine, finish, closing（可缺省——#88 收口扩载：编码 run 真收口携带收尾卡权威事实（summary/prdChanged/systemChanged/files/durationMs），主智能体对话轮不携带） |
            | question-raised | 智能体·生命周期 | runId, sessionId, summary, engineRef, data（问答卡投影与待确认工具清单） |
            | permission-required | 智能体·生命周期 | runId, sessionId, summary, engineRef, data（#83 权限确认挂起：确认卡——summary=命令文本、data.toolCalls=待确认工具最小面） |
            | permission-resolved | 智能体·生命周期 | runId, engineRef, approved（#83 权限确认落定：确认卡转已批/已拒） |
            | run-failed / guide-reply | 智能体·生命周期 | runId（+ guide-reply 的 prompt/label/text） |
            | acceptance-start | 智能体·生命周期 | runId（#87 受理动作卡：受理轮开场受理事实；落定由该轮 run-finish / error 推导） |
            | part-text | 智能体·部件 | text（完整段非增量——消息部件契约） |
            | part-action | 智能体·部件 | toolCallId, toolName, state（started/running/completed/failed）, label |
            | part-step | 智能体·部件 | step（1 起序号） |
            | part-check | 智能体·部件 | state（checking/passed/failed——#85 自检播报：平台侧产出，不经引擎部件映射） |
            | text / reasoning / patch / tool / step-start / step-finish | 引擎透传 | … + `data`（引擎 part 原样） |

            名册正本与字段细则：docs/spec/SSE事件清单.md（新增顶层 type 先进清单再上线）。""")
    public SseEmitter subscribe(
            @Parameter(description = "按项目过滤（与信封关联字段同名；缺省只收通知族）")
            @RequestParam(required = false) String projectId,
            @Parameter(description = "按运行过滤（智能体事件族的「看某个运行才挂」姿势；缺省不过滤）")
            @RequestParam(required = false) String runId,
            @Parameter(in = ParameterIn.HEADER, description = "SSE 断线重连自动携带；"
                    + "有值 = 重连 = 补发锚事件之后的缓冲窗口（断线补发），无值 = 新连接 = "
                    + "不补发（对话史经 REST 水合，平台状态以查询收敛）")
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // 新连/重连分野：空串视同无值（新连接）——浏览器只在真见过事件后才带非空值
        return appService.subscribe(projectId, runId,
                lastEventId != null && !lastEventId.isBlank() ? lastEventId : null);
    }

    /**
     * SSE 断连的 async error dispatch 静默（本地优先于全局 advice）：向已死连接写
     * 事件失败经容器 async 机制 dispatch 回本端点，异常带原始业务栈（极易误读为
     * 业务 500）——连接已断且响应已提交，无事可做，不落 ERROR 噪音。负载下
     * complete() 与 dispatch 竞态会以 IllegalStateException（emitter 已完成）出现，
     * 一并静默。
     */
    @ExceptionHandler({IOException.class, IllegalStateException.class})
    public void handleDisconnectedClient() {
        // 空：SSE 是呈现通道，订阅方断开属正常生命周期（心跳/广播失败已逐出并 WARN）
    }
}
