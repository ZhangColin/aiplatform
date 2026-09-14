-- #170 唤醒自愈底座：期望态三态（ADR-0016 意图/实态分离）+ last-touch 字段。
-- 期望态默认运行（存量与新建一律运行；变更方是后续休眠器 #171 / 封存 #172）。
-- last-touch 回填 now()：存量项目视为刚触碰（保守口径，不惊动闲置判定），
-- 之后由项目域 API 每次触碰拨动。置备三态（provisioning_status）语义不动。

ALTER TABLE wsp_workspaces
    ADD COLUMN desired_state INT NOT NULL DEFAULT 1;  -- DesiredState：1=运行 2=休眠 3=封存

ALTER TABLE wsp_workspaces
    ADD COLUMN last_touch_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
