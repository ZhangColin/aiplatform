package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.aieducenter.aiplatform.business.project.domain.enums.ConversationEntryKind;

/**
 * 对话史条目仓储（#89）。读面 = 项目全量按 id 升序（写入序即对话序）。
 */
public interface ConversationEntryRepository extends BaseRepository<ConversationEntry, Long> {

    /** 项目对话史（id 升序 = 写入序 = 对话序；水合读口）。 */
    List<ConversationEntry> findByProjectIdOrderByIdAsc(Long projectId);

    /** run 内最近一张待答问答卡（作答置位的目标——一 run 逐问串行，取最新待答）。 */
    Optional<ConversationEntry> findFirstByRunIdAndKindAndAnsweredFalseOrderByIdDesc(
            String runId, ConversationEntryKind kind);

    /** 项目删除的级联清理入口（编排调用，同表族软引用约定）。 */
    void deleteByProjectId(Long projectId);
}
