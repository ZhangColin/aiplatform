-- ========================================================================
-- business.billing：试用拦截留存（#533 计费② / ADR-0047——试用拦截面＝平台
-- 结构闸，门②拦截卡）。被拦输入（意见/兜底两道拦）原文随卡留存——确认扣减即
-- 翻转（FlipGate=2）并立即受理原输入（不重输）；走充值引导或中途关闭后输入
-- 不丢失、卡可回（一项目至多一条待决，新拦取代旧待决）。
--
-- bil_intercepts 拦截留存：lane＝被拦车道（1=意见 2=兜底——咨询放行、下单
-- 意图维持旧轨，永不入拦）；attachments＝消息附件载荷原样（JSONB，可空——纯
-- 文字输入）；status＝1 待决 2 已确认（翻转落定同事务）3 被取代；run_id＝拦截
-- 卡事件锚（随派发响应同值返回，挂 /api/events?runId= 同既有口径）。
-- ========================================================================

CREATE TABLE bil_intercepts (
    id          BIGINT PRIMARY KEY,     -- TSID（应用侧 @PrePersist 生成）
    project_id  BIGINT NOT NULL,        -- 项目（prj_projects 软引用）
    lane        INT NOT NULL,           -- InterceptLane：1=意见 2=兜底
    prompt      VARCHAR(5000) NOT NULL, -- 被拦输入原文（上限与发言同源 5000）
    attachments JSONB,                  -- 随拦附件载荷原样（圈注/图片物料，可空）
    status      INT NOT NULL,           -- InterceptStatus：1=待决 2=已确认 3=被取代
    run_id      VARCHAR(100) NOT NULL,  -- 拦截卡事件锚（派发响应同值）
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  BIGINT,
    updated_by  BIGINT
);

-- 一项目至多一条待决拦截（新拦取代旧待决的部分唯一索引——卡可回的读面单点）
CREATE UNIQUE INDEX uk_bil_intercepts_pending ON bil_intercepts (project_id) WHERE status = 1;
CREATE INDEX idx_bil_intercepts_project ON bil_intercepts (project_id);
