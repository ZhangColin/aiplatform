-- ========================================================================
-- business.project：enableThinking 开关档位层配置化（#370，ADR-0031 增补）。
--
-- prj_model_tiers 加 thinking_enabled：深度思考开关终值位从编译常量
-- （ModelTierAppService.THINKING_ENABLED，终值 false——#369 活体验收结论）
-- 改档位行运营配置——thinking 是模型行为属性，跟档位/模型走（换模型行为
-- 自动跟随、doubao 与 DeepSeek 系可不同设）。缺省 FALSE＝与常量终值一致：
-- 不配置＝显式关（用户故事「深度思考不默认拖慢首 token」承诺不破）。
--
-- 生效语义复用既有机制：装配动态查库（resolve 单点读行值）、通道指纹含
-- thinking 腿（改开关＝新指纹＝新模型实例——下一轮命令构建即生效，进行中
-- run 不定格）。种子三档位行经列缺省自动获 false，行为与切换前一致。
-- ========================================================================

ALTER TABLE prj_model_tiers
    ADD COLUMN thinking_enabled BOOLEAN NOT NULL DEFAULT FALSE;  -- 缺省＝显式关（#369 结论）
