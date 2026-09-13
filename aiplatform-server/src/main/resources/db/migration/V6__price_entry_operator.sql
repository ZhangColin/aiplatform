-- ========================================================================
-- business.order：价目行操作者留痕（#155）——ord_price_entries 补 operator 两列
--
-- operator 两列＝后台管理动作操作者（admin 侧管理员 TSID+昵称，非平台用户，
-- #151 口径，与 V5 knw_materials.operator 同款）：Id 供关联、Name 供直读
-- （账号删除后仍可读）；VARCHAR 存外域标识，不与平台 accountId 混型；服务端
-- 不校验真实性（签名面担保调用应用，操作者头明示信任）。
--
-- 落空口径：存量行不回填（呈现为空）；签名报价/改价请求缺 X-User-Id/
-- X-User-Name 透传头时同样落 NULL——「操作者为空」是读面一等状态（v0 无头
-- 调用方照常可用）。只加列不改写，append-only 口径不动。
-- ========================================================================

ALTER TABLE ord_price_entries
    ADD COLUMN operator_id   VARCHAR(64),
    ADD COLUMN operator_name VARCHAR(200);
