-- ========================================================================
-- business.billing：余额提醒留存（#537 计费③ / ADR-0047 段间硬门段中自由）。
-- 付费项目段边界源池余额≤0 时被拦输入（意见/咨询/作用域改稿——兜底零调用
-- 天然免费永不入拦）原文随留存落库：充值回正自动放行（恢复受理不重输，
-- 「充值到账后下一段自动放行〔不手动重试〕」）；新拦取代旧待决（一项目至多
-- 一条待决）。与 bil_intercepts（拦「未付费」）对偶同构，本表拦「已欠费」
-- （确认动作＝充值到账）。
--
-- bil_balance_holds 余额提醒留存：lane＝被拦车道（1=意见 2=咨询 3=作用域
-- 改稿）；attachments＝消息附件载荷原样（JSONB，可空——纯文字输入）；
-- design_item/divergence＝作用域改稿的件序与发散度（仅 3 车道承载，其余
-- 车道 NULL）；status＝1 待决 2 已放行（充值回正认领） 3 被取代；
-- run_id＝余额提醒卡事件锚（随派发响应同值返回）。
-- ========================================================================

CREATE TABLE bil_balance_holds (
    id          BIGINT PRIMARY KEY,     -- TSID（应用侧 @PrePersist 生成）
    project_id  BIGINT NOT NULL,        -- 项目（prj_projects 软引用）
    lane        INT NOT NULL,           -- BalanceHoldLane：1=意见 2=咨询 3=作用域改稿
    prompt      VARCHAR(5000) NOT NULL, -- 被拦输入原文（上限与发言同源 5000）
    attachments JSONB,                  -- 随拦附件载荷原样（圈注/图片物料，可空）
    design_item INT,                    -- 作用域改稿目标件序（仅 3 车道；其余 NULL）
    divergence  VARCHAR(20),            -- 作用域改稿发散度档位名（仅 3 车道可空）
    status      INT NOT NULL,           -- BalanceHoldStatus：1=待决 2=已放行 3=被取代
    run_id      VARCHAR(100) NOT NULL,  -- 余额提醒卡事件锚（派发响应同值）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT,
    updated_by  BIGINT
);

-- 一项目至多一条待决余额提醒（新拦取代旧待决的部分唯一索引——卡可回的读面单点）
CREATE UNIQUE INDEX uk_bil_balance_holds_pending ON bil_balance_holds (project_id) WHERE status = 1;
CREATE INDEX idx_bil_balance_holds_project ON bil_balance_holds (project_id);
