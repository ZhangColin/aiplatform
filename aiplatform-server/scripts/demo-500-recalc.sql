-- #500 验收锚本地合成复算：11-11 计量基础（miss 21.08M / hit 85.49M / out 2.20M）
-- 合成事件集（2026-11-11 周三，provider=deepseek，subject=demo500-recalc 专用自洁）。
-- 分布构造对齐父票 #470 验收参照已知事实：中午高峰 pro 单会话巨上下文
-- （北京 11:00-12:00＝UTC 03:00-04:00 峰窗 8.34M miss + 0.22M out → ¥81.0）、
-- flash 大头缓存命中落谷段；全局 token 量钉死父票计量基础。
BEGIN;
INSERT INTO met_usage_events (event_id, ts, subject, run_id, session_id, provider, model, dims, input, output, cache_read, cache_write, reasoning) VALUES
  -- deepseek-v4-pro（25.90M：miss 9.20 / hit 16.30 / out 0.40）
  ('demo500-evt-p1', '2026-11-11 03:30:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL, 8340000,  220000,      0, 0, 0),
  ('demo500-evt-p2', '2026-11-11 02:40:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL,       0,       0, 1000000, 0, 0),
  ('demo500-evt-p3', '2026-11-11 06:30:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL,  200000,       0, 2000000, 0, 0),
  ('demo500-evt-p4', '2026-11-11 09:30:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL,       0,       0, 3000000, 0, 0),
  ('demo500-evt-p5', '2026-11-11 04:30:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL,       0,       0, 5000000, 0, 0),
  ('demo500-evt-p6', '2026-11-11 12:00:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL,       0,       0, 5300000, 0, 0),
  ('demo500-evt-p7', '2026-11-11 13:30:00+00', 'demo500-recalc', 'run-demo500-p1', NULL, 'deepseek', 'deepseek-v4-pro', NULL,  660000,  180000,      0, 0, 0),
  -- deepseek-flash（82.87M：miss 11.88 / hit 69.19 / out 1.80）
  ('demo500-evt-f1', '2026-11-11 03:30:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL,  700000,       0,10500000, 0, 0),
  ('demo500-evt-f2', '2026-11-11 02:40:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL,  300000,       0, 6000000, 0, 0),
  ('demo500-evt-f3', '2026-11-11 06:30:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL,       0,       0, 8000000, 0, 0),
  ('demo500-evt-f4', '2026-11-11 09:30:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL, 1000000,  200000, 6000000, 0, 0),
  ('demo500-evt-f5', '2026-11-11 04:30:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL, 2000000,       0,10000000, 0, 0),
  ('demo500-evt-f6', '2026-11-11 12:00:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL, 3000000,       0, 9690000, 0, 0),
  ('demo500-evt-f7', '2026-11-11 13:30:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL, 2880000, 1600000, 3000000, 0, 0),
  ('demo500-evt-f8', '2026-11-11 15:00:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL,       0,       0,10000000, 0, 0),
  ('demo500-evt-f9', '2026-11-11 16:00:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL, 2000000,       0,       0, 0, 0),
  ('demo500-evt-f10','2026-11-11 17:00:00+00', 'demo500-recalc', 'run-demo500-f1', NULL, 'deepseek', 'deepseek-flash',  NULL,       0,       0, 6000000, 0, 0);
COMMIT;
