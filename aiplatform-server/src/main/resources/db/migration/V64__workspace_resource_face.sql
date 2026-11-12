-- ========================================================================
-- base.workspace：资源面两小表（#489，ADR-0045）——主机观测采样行＋手动清扫留痕
--
-- 后台资源面（resources/overview 大盘＋resources/sweep 手动清扫）的落库形状：
--   * wsp_host_observation：主机磁盘＋docker 四类用量＋无主计数的扫描轮采样快照，
--     全机一行（单机部署口径，id 恒 1），资源大盘纯读本表零 docker 调用、秒级
--     返回（扩 #487 采样面——采样轮末尾追加主机帧，不另立通道）；docker 不可达
--     轮 docker 列如实 NULL（「零」与「缺测」在观测面上必须可分）；
--   * wsp_sweep_actions：后台手动清扫 append-only 留痕（照 V12 wsp_workspace_actions
--     先例：操作者两列缺头落 NULL、只插入不写）——逐项处置报告 JSONB 全量留档，
--     库不可达整体跳过轮也落行（动了没动都要可审计）。无 FK：清扫对象是注册表
--     外的无主资源、观测是主机级事实，均不锚工作区。
--
-- 无主计数口径（准绳红线）：docker 侧工作区命名资源（在跑 ws-<id> 容器/
-- vol-ws-<id> 卷）对注册表全量编号求差；docker system df 的「可回收」口径不入
-- 任何判定/呈现（休眠卷被一并计入，ADR-0045）。
-- ========================================================================

CREATE TABLE wsp_host_observation (
    id                    BIGINT PRIMARY KEY,     -- 恒 1（全机一行，单机部署口径）
    host_path             VARCHAR(1024),          -- 主机盘测量锚路径（平台进程所在文件系统）
    host_total_bytes      BIGINT,                 -- 主机盘总字节（statfs；测不出 NULL）
    host_usable_bytes     BIGINT,                 -- 主机盘可用字节（statfs；测不出 NULL）
    image_count           BIGINT,                 -- 镜像计数（system df 采样；缺测 NULL）
    image_size_bytes      BIGINT,                 -- 镜像体量字节（十进制单位换算；缺测 NULL）
    container_count       BIGINT,                 -- 容器计数（缺测 NULL）
    container_size_bytes  BIGINT,                 -- 容器体量字节（缺测 NULL）
    volume_count          BIGINT,                 -- 本地卷计数（缺测 NULL）
    volume_size_bytes     BIGINT,                 -- 本地卷体量字节（缺测 NULL）
    build_cache_count     BIGINT,                 -- build cache 计数（缺测 NULL）
    build_cache_size_bytes BIGINT,                -- build cache 体量字节（缺测 NULL）
    orphan_container_count BIGINT,                -- 无主容器计数（ws-<id> 对注册表求差）
    orphan_volume_count   BIGINT,                 -- 无主卷计数（vol-ws-<id> 对注册表求差）
    sampled_at            TIMESTAMP NOT NULL,     -- 采样时刻（新鲜度标注，同帧各列）
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            BIGINT,
    updated_by            BIGINT
);

CREATE TABLE wsp_sweep_actions (
    id                 BIGINT PRIMARY KEY,     -- TSID
    operator_id        VARCHAR(64),            -- admin 侧 TSID 十进制（缺头 NULL）
    operator_name      VARCHAR(200),           -- 直读展示（缺头 NULL）
    registry_reachable BOOLEAN NOT NULL,       -- 注册表可达否（false＝库不可达一刀未动轮）
    swept_containers   INT NOT NULL,           -- 已收编容器数
    swept_volumes      INT NOT NULL,           -- 已收编卷数
    skipped_count      INT NOT NULL,           -- 跳过数（在册/宽限窗/时刻解析不出）
    failed_count       INT NOT NULL,           -- 删除未成数（下轮扫描再收）
    items              JSONB,                  -- 逐项处置报告 JSON（收编/跳过/未成逐项留档）
    swept_at           TIMESTAMP NOT NULL,     -- 清扫时刻（动作语义时间）
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         BIGINT,
    updated_by         BIGINT
);
