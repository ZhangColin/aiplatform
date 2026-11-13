-- ========================================================================
-- base.workspace：占用观测采样快照（#487）——wsp_observation_samples 每工作区一行
--
-- 工作区占用观测从「清单请求时每行现场 docker 探查＋旁路容器 du 全卷」（经常
-- 超时）改为「扫描轮旁路采样落库」：卷大小与容器实态快照由休眠扫描轮末尾的
-- 采样轮（后台单线程旁路、在途让路不排队）经环境后端探查后写本表，带采样时刻
-- （sampled_at 新鲜度标注）；工作区清单/详情端点零 docker 调用、纯读库（注册表
-- ＋本表）秒级返回。
--
-- 形状选型（#487 票内定，独立小表而非 wsp_workspaces 加列）：
--   * 注册表聚合只记意图（ADR-0016 语汇地基）——采样是观测读模型，混进聚合会让
--     每轮采样翻 updated_at 审计列（审计失真）并弄脏意图面；
--   * 行随工作区记录级联消亡（FK ON DELETE CASCADE，同 wsp_resources/
--     wsp_workspace_actions 先例）——销毁删注册行即清采样行，无需额外卫生通道。
--
-- container_state 采样的实态快照（ContainerState 四态枚举，探查失败如实 UNKNOWN）；
-- volume_size_bytes 可空（封存态卷已删不采；探查失败容缺 NULL）；
-- sampled_at 两帧同一轮采样的时刻（新鲜度标注的唯一正本）。
-- ========================================================================

CREATE TABLE wsp_observation_samples (
    workspace_id      BIGINT PRIMARY KEY,     -- TSID（wsp_workspaces.id 同源，一工作区一行）
    container_state   INT NOT NULL,           -- ContainerState：1=运行中 2=已停止 3=无容器 4=未知
    volume_size_bytes BIGINT,                 -- 旁路容器 du 全卷字节（封存/失败 NULL）
    sampled_at        TIMESTAMP NOT NULL,     -- 采样时刻（新鲜度标注）
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        BIGINT,
    updated_by        BIGINT,
    CONSTRAINT fk_wsp_observation_samples_workspace FOREIGN KEY (workspace_id)
        REFERENCES wsp_workspaces (id) ON DELETE CASCADE
);
