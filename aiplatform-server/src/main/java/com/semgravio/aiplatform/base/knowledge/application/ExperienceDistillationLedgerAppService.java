package com.semgravio.aiplatform.base.knowledge.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.base.knowledge.domain.model.ExperienceDistillationRecord;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.base.knowledge.domain.repository.ExperienceDistillationStore;
import com.semgravio.aiplatform.base.knowledge.domain.repository.SedimentDraftStore;

/**
 * 事后提炼落库编排（#641）：候选草稿落库与提炼处理记录同事务落定——两者要么一起
 * 成功（候选 + 一条「已提炼」记录），要么整体不发生（失败时只落一条失败记录、零
 * 草稿）。游标读口（最近一条非失败记录的版本号）与幂等判定读口（按项目 + 版本直读）
 * 同面暴露给业务编排。
 *
 * <p>记录写入用 upsert（唯一键 project_id + version_no）：同一版本重复触发幂等；
 * 失败行在同一版本重试成功时原地刷新。草稿只在新批次落定，失败重试路径不叠加
 * 历史草稿（失败从未写过草稿）。</p>
 */
@Service
public class ExperienceDistillationLedgerAppService {

    private final SedimentDraftStore draftStore;
    private final ExperienceDistillationStore recordStore;

    public ExperienceDistillationLedgerAppService(SedimentDraftStore draftStore,
            ExperienceDistillationStore recordStore) {
        this.draftStore = draftStore;
        this.recordStore = recordStore;
    }

    /** 游标：最近一条非失败记录的版本号（null＝首次处理）。 */
    @Transactional(readOnly = true)
    public Integer latestNonFailedVersionNo(long projectId) {
        return recordStore.latestNonFailedVersionNo(projectId);
    }

    /** 按项目 + 版本直读（重复触发幂等与失败重试判定）。 */
    @Transactional(readOnly = true)
    public ExperienceDistillationRecord find(long projectId, int versionNo) {
        return recordStore.find(projectId, versionNo);
    }

    /**
     * 落定一批提炼结果：候选草稿逐条落库（调用方已完成校验与静态扫描，危险内容
     * 不在列表内）＋处理记录 upsert。同一事务——候选与记录同生同灭。
     */
    @Transactional
    public void land(ExperienceDistillationRecord record,
            List<SedimentDraftRecord> drafts) {
        for (SedimentDraftRecord draft : drafts) {
            draftStore.insert(draft);
        }
        recordStore.upsert(record);
    }
}
