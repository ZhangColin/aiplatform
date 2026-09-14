-- ========================================================================
-- base.workspace：后台沙箱动作留痕（#174）——wsp_workspace_actions append-only
--
-- 管理员对单台沙箱的四动作（唤醒/强制休眠/强制重建/封存）逐行留痕：操作者两列
-- 口径同 V6/V9（admin 侧管理员 TSID+昵称，非平台用户，VARCHAR 存外域标识不与
-- accountId 混型，服务端不校验真实性；签名面缺 X-User-Id/X-User-Name 透传头时
-- 落 NULL）。只插入不写：重复动作是重复行（休眠/唤醒周期合法）；被拒动作不落行
-- （拒绝以错误响应即时触达）。行随工作区记录级联消亡（同封存包口径：删除项目
-- 才是终点）。acted_at 独立于审计 created_at（动作的语义时刻）。
-- ========================================================================

CREATE TABLE wsp_workspace_actions (
    id            BIGINT PRIMARY KEY,     -- TSID
    workspace_id  BIGINT NOT NULL,
    action        INT NOT NULL,           -- WorkspaceActionKind：1=唤醒 2=强制休眠 3=强制重建 4=封存
    operator_id   VARCHAR(64),            -- admin 侧 TSID 十进制（缺头 NULL）
    operator_name VARCHAR(200),           -- 直读展示（缺头 NULL）
    acted_at      TIMESTAMP NOT NULL,     -- 动作时刻（语义时间）
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    BIGINT,
    updated_by    BIGINT,
    CONSTRAINT fk_wsp_workspace_actions_workspace FOREIGN KEY (workspace_id)
        REFERENCES wsp_workspaces (id) ON DELETE CASCADE
);
