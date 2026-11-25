-- ========================================================================
-- base.knowledge：命中留痕 knw_retrievals（#639 阶段六-1）——
--
-- 每次主智能体开场检索写一条：项目、时间、命中条目（类别／标题／来源项目
-- 展示名／片段，jsonb 数组原样存端口模型）、是否降级。用途三处：
-- ① 会话装配时从「该项目最近一次记录」重建注入块（服务重启后继续同一场
--    访谈仍拿到同一份块，不重检索）；
-- ② 后台只读读面（按项目／时间过滤），区分「检索成功但 0 条」与「降级」；
-- ③ 后续飞轮统计的有命中／无命中分组事实源。
--
-- 检索成功但 0 条也记（items = []、degraded = false）；检索失败或向量服务
-- 不可用记 degraded = true（items 空）。项目删除时级联清理该项目的记录。
-- ========================================================================

CREATE TABLE knw_retrievals (
    id         BIGINT PRIMARY KEY,       -- TSID（写入时应用侧生成）
    project_id VARCHAR(100) NOT NULL,    -- 检索所属项目（读面过滤 + 级联清理入口）
    degraded   BOOLEAN NOT NULL,         -- 是否降级（向量服务不可用 / 检索异常）
    items      JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 命中条目数组（kind/sourceProjectName/title/chunk）
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 最近一次记录重建 + 项目级联清理入口（时间倒序取一 / DELETE ... WHERE project_id）
CREATE INDEX idx_knw_retrievals_project_time ON knw_retrievals (project_id, created_at DESC, id DESC);

-- 后台读面时间过滤（可缺项目维度）
CREATE INDEX idx_knw_retrievals_created_at ON knw_retrievals (created_at DESC);
