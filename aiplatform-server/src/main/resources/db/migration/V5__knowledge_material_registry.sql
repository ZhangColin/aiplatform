-- ========================================================================
-- base.knowledge：知识素材登记表（#153）——素材状态机制的物理落点
--
-- 管理单元＝素材（身份 (kind, source_ref)，幂等键即柄），状态与操作者是素材级
-- 事实：登记表一行是单一事实源，块表 knw_chunks 结构不动。沉淀幂等（删后插
-- 替换）只动块表；登记表 upsert 保 id/status/operator/created_at（首沉淀时间）
-- ——停用状态跨重沉淀存活，被治理素材重沉淀不复活。
--
-- 选择登记表而非块表同值列：同值列方案停用要更新 N 行、幂等重插要读旧状态
-- 回填、素材级清单要 GROUP BY 分页；登记表三处皆免。素材合成独立 id（TSID，
-- 首沉淀生成、重沉淀保留）供后续管理端点作 URL 柄。
--
-- operator 两列＝后台管理动作操作者（admin 侧管理员 TSID+昵称，非平台用户，
-- #151 口径）：Id 供关联、Name 供直读；VARCHAR 存外域标识，不与平台
-- accountId 混型。检索过滤＝findSimilar JOIN 本表 WHERE status = 1（启用 code）。
-- ========================================================================

CREATE TABLE knw_materials (
    id            BIGINT PRIMARY KEY,     -- TSID（首沉淀时生成，重沉淀保留）
    kind          VARCHAR(30) NOT NULL,   -- 素材类别（与 knw_chunks.kind 同口径）
    source_ref    VARCHAR(200) NOT NULL,  -- 素材来源标识（幂等键之二）
    project_id    VARCHAR(100) NOT NULL,  -- 归属项目（purgeByProject 级联清理入口）
    project_name  VARCHAR(200) NOT NULL,  -- 来源项目名（登记面展示）
    title         VARCHAR(300) NOT NULL,  -- 素材标题（登记面展示）
    status        INT NOT NULL DEFAULT 1, -- MaterialStatus：1=启用 2=停用（#34 房规：枚举落库 Integer code）
    operator_id   VARCHAR(64),            -- 最近管理动作操作者 id（未治理过为 NULL）
    operator_name VARCHAR(200),           -- 最近管理动作操作者名（直读）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    BIGINT,
    updated_by    BIGINT,
    CONSTRAINT uq_knw_materials_identity UNIQUE (kind, source_ref),
    CONSTRAINT ck_knw_materials_status CHECK (status IN (1, 2))
);

-- 项目删除级联清理入口（与 knw_chunks 对称）
CREATE INDEX idx_knw_materials_project_id ON knw_materials (project_id);

-- 存量块回填登记行：不做则检索 JOIN 过滤会把存量知识整体打隐身。
-- 一素材一行（同素材块的字段同值，MAX/MIN 即直取）；status 走缺省 1（启用）；
-- created_at 取块 MIN(created_at)＝首沉淀时间。回填 id 用行号小整数——TSID
-- 时间高位占满，应用侧生成值与之无碰撞面。
INSERT INTO knw_materials (id, kind, source_ref, project_id, project_name, title,
                           created_at, updated_at)
SELECT row_number() OVER (ORDER BY kind, source_ref),
       kind, source_ref, MAX(project_id), MAX(project_name), MAX(title),
       MIN(created_at), MAX(updated_at)
FROM knw_chunks
GROUP BY kind, source_ref;
