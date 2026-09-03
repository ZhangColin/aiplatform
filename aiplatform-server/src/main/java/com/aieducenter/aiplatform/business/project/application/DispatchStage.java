package com.aieducenter.aiplatform.business.project.application;

/**
 * 派发阶段值集（#50 阶段状态条）：意见 / 咨询全过程阶段帧 {@code dispatch-stage}
 * 的 {@code stage} 值正本（SSE事件清单·通道二）。前端状态条按帧驱动呈现——
 * <b>不署智能体名</b>（分派对用户隐式，阶段进度可见，CONTEXT.md「派发」）。
 *
 * <p>两条链的阶段序：</p>
 * <ul>
 *   <li><b>意见链</b>（已生成项目）：{@code analyzing} →（{@code clarifying} 挂起，
 *       答复即回 {@code analyzing} 续走 / {@code updating-prd} 可选——BA 零动作时
 *       不经此阶，状态条仍完整走完不静默）→ {@code dispatching} 或 {@code queued}
 *       （在途 run 排队如实呈现）→ {@code fixing} → {@code done}（changed 区分
 *       「已修改」与「未动系统」）。生成前意见链止于 BA，不发阶段帧（访谈期以
 *       对话面本身呈现，无隐藏处理段）。</li>
 *   <li><b>咨询链</b>：{@code analyzing} → {@code answered}（零产物短路）。</li>
 * </ul>
 *
 * <p>帧不承担正确性（SSE 事件口径）：失败链唯一的终态阶段帧是
 * {@code dispatch-failed}（BA 收口派修正 run 异常——状态条不悬死在「派发中」），
 * 其余失败无终态阶段帧（error 帧如实表达，状态条停在事发阶段）；重放缓冲可
 * 恢复近期链的阶段（刷新不静默）。</p>
 */
enum DispatchStage {

    /** 分析中：意见 / 咨询已受理，正在分析（BA 轮起跑 / 问答答复续跑同发）。 */
    ANALYZING("analyzing"),

    /** 追问中：BA 挂起提问，链停在等用户答复——答复后续跑回 {@link #ANALYZING}。 */
    CLARIFYING("clarifying"),

    /** 更新 PRD 中：BA 判定需求变更，正在修订 PRD（savePrd 执行）。 */
    UPDATING_PRD("updating-prd"),

    /** 派发中：BA 回合收口，平台正在派修正 run（交接物组装 / 轨道提交）。 */
    DISPATCHING("dispatching"),

    /** 修正中：修正 run 进行中（直播 / 预览随之呈现细节）。 */
    FIXING("fixing"),

    /** 排队中：修正 run 在途，意见已并入下一轮合并修正（如实呈现排队）。 */
    QUEUED("queued"),

    /** 完成：修正收口——changed=true 已修改 / false 未动系统（区分呈现）。 */
    DONE("done"),

    /** 派发失败：BA 收口派修正 run 异常的失败终态——如实告知重提（意见锚已
     * 消费、不自动重试，重提即兜底），状态条不悬死在派发中。 */
    DISPATCH_FAILED("dispatch-failed"),

    /** 已答复：咨询已由平台答复（零产物短路收口）。 */
    ANSWERED("answered");

    /** 线上值（SSE 帧 stage 字段的正本词面，kebab-case）。 */
    private final String wireValue;

    DispatchStage(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
