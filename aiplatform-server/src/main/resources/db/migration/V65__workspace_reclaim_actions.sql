-- ========================================================================
-- base.workspace：一键回收动作留痕（#490，ADR-0045「动刀类走 admin 手动＋留痕」）
-- —— wsp_reclaim_actions append-only
--
-- 后台 reclaim（POST /api/backoffice/projects/{id}/reclaim 与
-- /api/backoffice/workspaces/{id}/reclaim）每次触发一行：谁（操作者两列，照 V64
-- wsp_sweep_actions 先例：admin 侧标识，缺透传头落 NULL）、何时、回收了什么
-- （scope＋两锚列）、结果如何（三清/四面事实——容器/卷/注册行三面清否＋项目行
-- 面清否〔工作区级 NULL＝不动项目面〕＋probed 探查成否〔false＝销毁未全成、
-- 各面状态未知不冒充清〕＋runsStopped 在飞 run 级联停止与否）。只插入不写；
-- 未成动作不留行（销毁失败上抛 500，照 WorkspaceAction/WSP_002 口径）。
-- 无 FK：回收对象行随动作本身消亡（项目行/注册行都被本动作删除），锚列只作
-- 对账寻址（TSID 原文），不锚存活的行。
--
-- 在册失联裁决出口（#488→#490）：wsp_lost_marks 行随工作区注册行 FK 级联消亡，
-- 本表留痕即「人工已裁决」的审计事实。
-- ========================================================================

CREATE TABLE wsp_reclaim_actions (
    id                 BIGINT PRIMARY KEY,     -- TSID
    scope              INT NOT NULL,           -- ReclaimScope：1=项目级 2=工作区级
    project_id         BIGINT,                 -- 项目级＝回收的项目；工作区级＝所属项目（无所属 NULL）
    workspace_id       BIGINT NOT NULL,        -- 回收的工作区（三清对象）
    operator_id        VARCHAR(64),            -- admin 侧 TSID 十进制（缺头 NULL）
    operator_name      VARCHAR(200),           -- 直读展示（缺头 NULL）
    runs_stopped       BOOLEAN NOT NULL,       -- 回收时有在飞 run 被级联停止（承 #477）
    container_clean    BOOLEAN NOT NULL,       -- 容器面清否（UNKNOWN 不算清）
    volume_clean       BOOLEAN NOT NULL,       -- 卷面清否
    registry_row_clean BOOLEAN NOT NULL,       -- 注册行面清否
    project_row_clean  BOOLEAN,                -- 项目行面清否（工作区级 NULL＝不动项目面）
    probed             BOOLEAN NOT NULL,       -- 三清探查成否（false＝销毁未全成/探查未成）
    reclaimed_at       TIMESTAMP NOT NULL,     -- 回收时刻（动作语义时间）
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         BIGINT,
    updated_by         BIGINT
);
