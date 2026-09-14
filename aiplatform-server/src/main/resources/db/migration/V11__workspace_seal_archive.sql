-- #172 封存与深度唤醒（ADR-0016）：封存元数据入库——封存时刻、封存包路径与大小。
-- 期望态 desired_state=3（封存）由 #170 的三态枚举预留，本片不改；三列可空
-- （未封存的工作区无值；深度唤醒不回清——元数据描述盘上封存包事实，重复封存
-- 覆盖旧包时一并刷新，项目删除时随记录消亡并触发封存包清理）。

ALTER TABLE wsp_workspaces
    ADD COLUMN sealed_at TIMESTAMP;

ALTER TABLE wsp_workspaces
    ADD COLUMN archive_path VARCHAR(1024);

ALTER TABLE wsp_workspaces
    ADD COLUMN archive_size_bytes BIGINT;
