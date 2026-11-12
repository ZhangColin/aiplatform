-- ========================================================================
-- base.workspace：在册失联标记（#488，ADR-0045）——wsp_lost_marks 每工作区一行
--
-- 「在册但项目行已删」的工作区行（销毁中途吞错的静止现场）分级处置的标记面：
-- 物理资源仍在（容器/卷任一在场）→ 只标记在册失联、后台清单可见（lostAt 列），
-- 待人工裁决（裁决出口＝#490 一键回收——机器不删可能是用户数据的卷，ADR-0016）；
-- 物理已空 → 删注册行做卫生（空壳噪音不淹失联清单），本表行随 FK 级联消亡。
--
-- 形状选型（#488 票内定，独立小表而非 wsp_workspaces 加列，照 V62 先例）：
--   * 注册表聚合只记意图（ADR-0016 语汇地基）——失联是对账判定的观测面，加列会
--     弄脏意图面（desired_state 三态语汇不动，不设第四态，ADR-0045 已否决墓碑制）；
--   * 行随工作区记录级联消亡（FK ON DELETE CASCADE，同 wsp_observation_samples/
--     wsp_resources 先例）——销毁/卫生删注册行即清标记，无需额外卫生通道。
--
-- marked_at 失联判定时刻（首标即定、复扫不覆盖——「失联多久」的稳定锚）。
-- ========================================================================

CREATE TABLE wsp_lost_marks (
    workspace_id BIGINT PRIMARY KEY,     -- TSID（wsp_workspaces.id 同源，一工作区一行）
    marked_at    TIMESTAMP NOT NULL,     -- 失联判定时刻（首标即定）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   BIGINT,
    updated_by   BIGINT,
    CONSTRAINT fk_wsp_lost_marks_workspace FOREIGN KEY (workspace_id)
        REFERENCES wsp_workspaces (id) ON DELETE CASCADE
);
