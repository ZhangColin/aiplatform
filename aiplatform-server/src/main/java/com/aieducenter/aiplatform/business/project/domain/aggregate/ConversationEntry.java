package com.aieducenter.aiplatform.business.project.domain.aggregate;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.aieducenter.aiplatform.business.project.domain.enums.ConversationEntryKind;

/**
 * 对话史条目（{@code prj_conversation_entries}，#89 对话史落库④）：对话面全量
 * 落库的 append-only 日志行——id 为库序列（BIGSERIAL），<b>读序 = 写入序</b>
 * （pk 升序即对话序，不信任进程内时钟的多线程序；区别于表族的 TSID 惯例，纯
 * 插入日志用库序最直）。写口唯一 = 应用层的 {@code ConversationHistoryAppService}。
 *
 * <p>question / closing 载荷为事件 / 收口扩载的 JSON 原样（JSONB）——问答卡刷新
 * 后可重建可作答（question-raised payload 原样）、收尾卡与 #88 SSE 扩载复用同一
 * 载荷（版本锚定 #91 亦复用）。answered 是全表唯一 UPDATE 面：问答作答即置位，
 * 读模型自洽（未答 ⟺ 挂起待答）。</p>
 */
@Entity
@Table(name = "prj_conversation_entries")
@Aggregate
@Getter
public class ConversationEntry extends Auditable implements AggregateRoot<ConversationEntry, Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 对话轮锚（问答 / 收尾卡的 run 归属判定与前端水合去重锚）。 */
    @Column(name = "run_id", updatable = false, length = 100)
    private String runId;

    /** 编码走 {@link ConversationEntryKind.JpaConverter}（autoApply，存 code INT）。 */
    @Column(name = "kind", nullable = false, updatable = false)
    private ConversationEntryKind kind;

    @Column(name = "text", updatable = false)
    private String text;

    /** kind=question：question-raised 事件 payload 原样（engineRef/data 全量）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> question;

    /** kind=closing：#88 收口扩载载荷原样（summary/判定行/变更清单/轮末统计）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "closing", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> closing;

    /** kind=question 的已答位（作答即置位——全表唯一 UPDATE 面）。 */
    @Column(name = "answered")
    private Boolean answered;

    protected ConversationEntry() {
    }

    private ConversationEntry(Long projectId, String runId, ConversationEntryKind kind,
            String text, Map<String, Object> question, Map<String, Object> closing) {
        this.projectId = projectId;
        this.runId = runId;
        this.kind = kind;
        this.text = text;
        this.question = question;
        this.closing = closing;
        this.answered = kind == ConversationEntryKind.QUESTION ? Boolean.FALSE : null;
    }

    /** 用户发言条目（提交守卫全过后同步落）。 */
    public static ConversationEntry userUtterance(Long projectId, String runId, String text) {
        return new ConversationEntry(projectId, runId, ConversationEntryKind.USER, text, null, null);
    }

    /** 智能体回复条目（轮收口 / 问答挂起时按段落库——段序即对话序）。 */
    public static ConversationEntry agentReply(Long projectId, String runId, String text) {
        return new ConversationEntry(projectId, runId, ConversationEntryKind.AGENT, text, null, null);
    }

    /** 问答卡条目（question-raised 事件 payload 原样——刷新后可重建可作答）。 */
    public static ConversationEntry question(Long projectId, String runId,
            Map<String, Object> payload) {
        return new ConversationEntry(projectId, runId, ConversationEntryKind.QUESTION, null,
                payload, null);
    }

    /** 问答作答条目。 */
    public static ConversationEntry answer(Long projectId, String runId, String text) {
        return new ConversationEntry(projectId, runId, ConversationEntryKind.ANSWER, text, null, null);
    }

    /** 收尾卡条目（#88 closing 载荷原样）。 */
    public static ConversationEntry closing(Long projectId, String runId,
            Map<String, Object> closing) {
        return new ConversationEntry(projectId, runId, ConversationEntryKind.CLOSING, null, null,
                closing);
    }

    /** 平台轻引导条目（兜底分支定型文案）。 */
    public static ConversationEntry guide(Long projectId, String runId, String text) {
        return new ConversationEntry(projectId, runId, ConversationEntryKind.GUIDE, text, null, null);
    }

    /** 问答卡作答置位（幂等——已答不再置）。 */
    public void markAnswered() {
        this.answered = Boolean.TRUE;
    }

    @Override
    public Long getId() {
        return id;
    }
}
