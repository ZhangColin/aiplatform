package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment;
import com.aieducenter.aiplatform.business.project.application.dto.response.ConversationEntryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.aieducenter.aiplatform.business.project.domain.enums.ConversationEntryKind;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ConversationEntryRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话史落库（#89 对话史落库④，spec ④）——<b>写口唯一</b>：对话表的全部写入经本
 * 服务（主智能体单会话收口处为主写口——#86 并轨后单会话，无双会话先落再迁移的
 * 结构；问答 / 收尾卡 / 轻引导的写入同归本口）。对话面全量落库：用户发言、智能体
 * 回复、问答卡、问答作答、收尾卡、平台轻引导；<b>过程明细不落</b>（解说段 / 动作卡
 * 流水——收尾卡已是凝聚物）。
 *
 * <p><b>失败口径分两档</b>：提交侧（用户发言 / 问答作答——REST 线程同步写）失败
 * 上抛——REST 5xx 撤回乐观气泡，「落库 ⟺ 说过」不漂移（刷新不丢已发送的话）；
 * 收口侧（智能体回复 / 问答卡 / 收尾卡——异步轨道写）失败只记日志不牵连已成轮
 * （轮已成功，历史缺段不炸对话轨道）。读口 = 项目全量按 id 升序（写入序即对话序）。</p>
 *
 * <p>run 不落平台表的口径不变：对话史承载「再进入所见」，编排轨道（迭代排队 /
 * 在途标记）保持进程内为已知取舍。</p>
 */
@Service
@Slf4j
public class ConversationHistoryAppService {

    /** 圈注附件 → JSONB 数组的序列化器（对话史落库用，静态无状态）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConversationEntryRepository entries;
    private final ProjectRepository projectRepository;

    public ConversationHistoryAppService(ConversationEntryRepository entries,
            ProjectRepository projectRepository) {
        this.entries = entries;
        this.projectRepository = projectRepository;
    }

    /**
     * 用户发言落库（意见 / 咨询 / 建项目开场需求——提交守卫全过后、异步轮提交前
     * 同步写；失败上抛撤回 REST 面）。attachments = 随发言发送的圈注附件（可空/
     * 空表——纯文字发言），落库为 JSONB 数组供刷新回显重建圈注 chip。
     */
    public void recordUserUtterance(Long projectId, String runId, String text,
            List<AnnotationAttachment> attachments) {
        entries.save(ConversationEntry.userUtterance(projectId, runId, text,
                toJsonMaps(attachments)));
    }

    /** 用户发言落库（无附件——纯文字发言）。 */
    public void recordUserUtterance(Long projectId, String runId, String text) {
        recordUserUtterance(projectId, runId, text, null);
    }

    /** 圈注附件 → 原始 JSON 数组（空/全非圈注返回 null——不入库空数组）。 */
    private static List<Map<String, Object>> toJsonMaps(List<AnnotationAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<AnnotationAttachment> annotations = attachments.stream()
                .filter(AnnotationAttachment::hasAnnotation)
                .toList();
        if (annotations.isEmpty()) {
            return null;
        }
        return JSON.convertValue(annotations, new TypeReference<>() {
        });
    }

    /**
     * 问答作答落库（作答通道受理即写——同步、失败上抛同用户发言口径）；同一事务
     * 内置位 run 内最近一张待答问答卡的 answered（读模型自洽：未答 ⟺ 待答卡在场）。
     */
    @Transactional
    public void recordAnswer(Long projectId, String runId, String answerText) {
        entries.findFirstByRunIdAndKindAndAnsweredFalseOrderByIdDesc(runId,
                        ConversationEntryKind.QUESTION)
                .ifPresent(question -> {
                    question.markAnswered();
                    entries.save(question);
                });
        entries.save(ConversationEntry.answer(projectId, runId, answerText));
    }

    /**
     * 智能体回复段落库（轮收口或问答挂起时的已出文本段——段序即对话序，问答
     * 挂起段先落、答复与续跑段随后，交错成完整叙事）。异步轨道写：失败只记日志。
     */
    public void recordAgentReply(Long projectId, String runId, String text) {
        if (text == null || text.isBlank()) {
            return; // 无话语段（纯工具轮 / 挂起前无解说）不落空行
        }
        quietly(() -> entries.save(ConversationEntry.agentReply(projectId, runId, text)));
    }

    /**
     * 问答卡落库（question-raised 事件 payload 原样——engineRef / data.toolCalls /
     * questions 投影全量，刷新 / 回访后问答卡可重建可作答）。
     */
    private void recordQuestion(Long projectId, AgentEvent event) {
        Object runId = event.payload().get(AgentEventTypes.RUN_FIELD);
        quietly(() -> entries.save(ConversationEntry.question(projectId,
                runId != null ? runId.toString() : null, event.payload())));
    }

    /**
     * 收尾卡落库（编码 run 真收口的 closing 权威事实——#88 SSE 扩载同载荷，版本
     * 锚定 #91 复用本行）。异步轨道写：失败只记日志（不触发尝试环重试）。
     */
    public void recordClosing(Long projectId, String runId, Map<String, Object> closing) {
        quietly(() -> entries.save(ConversationEntry.closing(projectId, runId, closing)));
    }

    /**
     * 平台轻引导落库（兜底分支零产物路径：用户发言 + 定型文案两行——对话完整）。
     * 事件已发射后的补写：失败只记日志。attachments = 随发言发送的圈注附件（可空）。
     */
    public void recordGuide(Long projectId, String runId, String prompt, String text,
            List<AnnotationAttachment> attachments) {
        quietly(() -> {
            entries.save(ConversationEntry.userUtterance(projectId, runId, prompt,
                    toJsonMaps(attachments)));
            entries.save(ConversationEntry.guide(projectId, runId, text));
        });
    }

    /**
     * 对话史读口（水合载荷）：项目全量按 id 升序（写入序即对话序）。归档项目照读
     * （对话区只读终态）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public List<ConversationEntryResponse> read(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND);
        }
        return entries.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(ConversationEntryResponse::of)
                .toList();
    }

    /**
     * 事件桥的对话史旁挂（#89 写口接线）：转发智能体事件至 SSE 桥的同时<b>捕获</b>
     * question-raised（不即写——问答卡必须落在挂起段文本之后，对话序方正确），
     * 轮落定点经 {@link TurnRecorder#settle} 按序落库（挂起段文本 → 问答卡）。
     * 捕获的问答卡是刷新 / 回访后问答卡可重建可作答的唯一来源（重放缓冲降级断线
     * 补发后不再承担刷新重建）。
     */
    public TurnRecorder recorder(Long projectId, Consumer<AgentEvent> sink) {
        return new TurnRecorder(projectId, sink);
    }

    /**
     * 轮级对话史记录器：旁挂一轮对话的事件桥——转发 SSE 事件 + 捕获问答卡载荷，
     * 轮落定点（converse / resume 返回后）按对话序落段。轮炸（异常上抛）不落——
     * 失败轮无对话面残留（重提即兜底）。
     */
    public final class TurnRecorder implements Consumer<AgentEvent> {

        private final Long projectId;
        private final Consumer<AgentEvent> sink;
        private AgentEvent question;

        private TurnRecorder(Long projectId, Consumer<AgentEvent> sink) {
            this.projectId = projectId;
            this.sink = sink;
        }

        /** sink 面：转发 + 捕获问答卡（幂等——一轮多问以后到为准，随各自 settle 落）。 */
        @Override
        public void accept(AgentEvent event) {
            sink.accept(event);
            if (AgentEventTypes.QUESTION_RAISED.equals(event.type())) {
                question = event;
            }
        }

        /**
         * 轮落定点落库：正常收口 / 问答挂起——挂起段文本先落、捕获的问答卡随后
         * （live 呈现序：解说 → 问答卡）；权限类挂起不落（主智能体只读面无权限
         * 工具，防御位）。失败 quietly（缺段不牵连已成轮）。
         */
        public void settle(String runId, AgentReply reply) {
            if (reply.suspension() != null && reply.suspension().permission()) {
                return;
            }
            if ((reply.text() == null || reply.text().isBlank()) && question == null) {
                return;
            }
            quietly(() -> {
                if (reply.text() != null && !reply.text().isBlank()) {
                    entries.save(ConversationEntry.agentReply(projectId, runId, reply.text()));
                }
                if (question != null) {
                    recordQuestion(projectId, question);
                    question = null;
                }
            });
        }
    }

    /** 异步轨道写的护栏：对话史缺段不牵连已成轮（只记日志）。 */
    private void quietly(Runnable write) {
        try {
            write.run();
        }
        catch (RuntimeException e) {
            log.warn("[conversation] 对话史写入失败（缺段不牵连轮）：{}", e.toString());
        }
    }

    /** 项目删除级联清理入口（软引用无 FK，删除编排显式调用——同 knw_chunks 先例）。 */
    public void purgeByProject(Long projectId) {
        entries.deleteByProjectId(projectId);
    }
}
