-- ========================================================================
-- base.skills：更新检查包级行＋显式更新留痕（#246-T4/#250，ADR-0021 快照更新）
--
-- skl_packages：来源包级检查状态（一行一来源包）。装时排除名单在此持久化——
-- 显式更新重拉快照须与装时同口径（否则装时排除的 deprecated 类目会随更新还魂）。
-- update_available＝「有新版」标记：定期只读检查（git ls-remote HEAD）远端 HEAD
-- ≠装时版本即亮；永不自动跟新（远端删改不漂进平台——否决订阅式的理由），更新
-- 永远显式点（POST /update）。检查失败静默降级：不写本表（checked_at 不动），
-- 留待下轮。存量安装（T4 前无包行）由扫描轮按需补建（排除名单空）。
--
-- skl_update_traces：显式更新版本留痕（append-only，历史版本可查）。无 FK——
-- 留痕跨卸载存活（历史事实不随库行消失），来源包以字符串身份随行。操作者两列
-- 房规同 V16（admin 侧标识，签名面明示信任）。
-- ========================================================================

CREATE TABLE skl_packages (
    source_package   VARCHAR(300) PRIMARY KEY,  -- 来源包（规范化仓库地址，与 skl_skills.source_package 同串）
    exclude_dirs     JSONB NOT NULL DEFAULT '[]',  -- 装时排除目录段（重拉快照同口径）
    remote_head      VARCHAR(100),              -- 最近一次成功检查的远端 HEAD（null=未检查过）
    update_available BOOLEAN NOT NULL DEFAULT FALSE,  -- 有新版标记（远端 HEAD ≠ 装时版本）
    checked_at       TIMESTAMP,                 -- 最近检查时刻（失败不写——留待下轮）
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE skl_update_traces (
    id             BIGINT PRIMARY KEY,      -- TSID
    source_package VARCHAR(300) NOT NULL,   -- 来源包（规范化地址；无 FK——留痕跨卸载存活）
    from_version   VARCHAR(100) NOT NULL,   -- 更新前版本（装时/上次更新 commit）
    to_version     VARCHAR(100) NOT NULL,   -- 更新后版本（新 HEAD commit）
    operator_id    VARCHAR(64),             -- 更新操作者（显式动作必留痕）
    operator_name  VARCHAR(200),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
