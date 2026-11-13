-- ========================================================================
-- base.metering：时段规则表（#497，ADR-0044 峰谷 1/4）——met_time_band_rules
--
-- 时段规则＝供应商峰谷定价的时段定义（通道 × 生效区间 × 星期口径 × UTC 时段
-- 窗口），供应商侧可运营数据：与单价表（met_price_entries）分表各带生效区间，
-- 两个变化节奏独立（调窗口≠调价）。行粒度＝一个 UTC 窗口——一次开行原子落 N
-- 行窗口行＝一个规则版本；生命周期与单价表同构（关行开新行，改/停在版本级：
-- 寻址当前版本任一行即关该通道全部敞口行）。归段消费（事件 ts → 峰/谷）为后续
-- 引擎票（PRICE_MATCH 扩时段维）改动面，本表只落结构与写口。
--
-- 星期口径：ISO 周 bitmask（Mon=1 Tue=2 Wed=4 Thu=8 Fri=16 Sat=32 Sun=64），
-- 1..127 非零——SQL 归段单点判定（ts 星期 ∈ mask ∧ ts UTC 分钟 ∈ [start,end)）。
--
-- 跨午夜窗口表示法定案（#497，票内评论留痕）：v1 单行须 start < end（同日
-- 窗口），跨午夜窗口拆两行（22:00–02:00 ＝ 22:00–24:00 ＋ 00:00–02:00）——
-- 星期口径×跨午夜语义有歧义（周一 22:00–02:00 溢入周二），拆行让归段无包装
-- 歧义；DeepSeek 现役窗口（01:00–04:00、06:00–10:00 UTC）均不跨午夜。
--
-- 无 (provider, effective_from) 唯一约束：同版本多窗口行共享起点，约束会撞
-- 合法种入——同起点拒由写口服务端拦（对称 METER_008，#160 事故口同款口径）。
--
-- 日期覆写行（ADR-0044 节假日升级位）形状随本迁移预留：met_time_band_date_
-- overrides（通道 × 日期 × 全天带），空表无写口——将来上年度中国节假日日历
-- 免迁移；v1 周一至周五近似不动本表。
-- ========================================================================

CREATE TABLE met_time_band_rules (
    id             BIGINT       PRIMARY KEY,  -- TSID（应用侧 @PrePersist 生成）
    provider       VARCHAR(50)  NOT NULL,   -- 模型提供方（与 UsageEvent.provider 同键）
    band           INT          NOT NULL,   -- Band：1=峰 2=谷（BaseEnum code 落库）
    day_mask       INT          NOT NULL,   -- 星期口径 bitmask（Mon=1…Sun=64，1..127）
    start_minute   SMALLINT     NOT NULL,   -- UTC 窗口起点（含），0..1439
    end_minute     SMALLINT     NOT NULL,   -- UTC 窗口终点（不含），1..1440；start < end
    effective_from TIMESTAMPTZ  NOT NULL,   -- 生效起点（含）
    effective_to   TIMESTAMPTZ,             -- 生效终点（不含）；NULL = 当前行（整行唯一可写列，关行）
    operator_id    VARCHAR(64),             -- 操作者标识（admin 侧 TSID 十进制；存量/无头 NULL）
    operator_name  VARCHAR(200),            -- 操作者昵称（直读展示；同上落空口径）
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT
);

-- 清单/重叠校验走 provider 过滤（effective_from 排序无索引刚需：通道行量级小）

-- 日期覆写行预留（ADR-0044 节假日升级位）：通道 × 日期 × 全天带，一行覆写一天；
-- 唯一约束＝同一天同一通道至多一条覆写。空表、无写口——升级位形状落档。
CREATE TABLE met_time_band_date_overrides (
    id             BIGINT       PRIMARY KEY,  -- TSID
    provider       VARCHAR(50)  NOT NULL,   -- 模型提供方（同上键）
    override_date  DATE         NOT NULL,   -- 覆写日期（UTC 日界）
    band           INT          NOT NULL,   -- 全天带：1=峰 2=谷（BaseEnum code 落库）
    operator_id    VARCHAR(64),             -- 留痕两列（写口随升级位一并补）
    operator_name  VARCHAR(200),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by     BIGINT,
    updated_by     BIGINT,
    CONSTRAINT uk_met_time_band_date_overrides_key UNIQUE (provider, override_date)
);
