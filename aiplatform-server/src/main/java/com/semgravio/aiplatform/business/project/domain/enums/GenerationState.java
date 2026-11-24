package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 项目生成态投影（#222，ADR-0020 裁决四）：由 generated_at＋在途标记派生的
 * 呈现态（不是落库事实——事实源是 {@code prj_projects.generated_at}），REST 详情
 * 透出、前端档位由此派生（不依赖 SSE 会话态——刷新/回访后档位仍正确）。
 *
 * <p>#604 四态归一：「生成中断」档随旧失败语义（续跑机器）一并退役——轨道表
 * 机器 #602 已退役、中断档失去判据，本枚举只余三态（{@code NEVER_GENERATED}/
 * {@code GENERATING}/{@code GENERATED}），原中断档并入从未生成档（#604 起失败
 * 不落投影：失败只经 run-failed 事件在会话内明确呈现，恢复入口＝点火入口）。
 * 派生序：已生成（generated_at 落位）恒赢——迭代/修正 run 在途不改变
 * 生成态；未生成时编码 run 在途 = 生成中（含已提交未起跑）；其余 = 从未生成。</p>
 */
public enum GenerationState implements BaseEnum<GenerationState> {

    /** 从未生成（PRD 未产出/产出未起跑，或首建失败·停止后待重跑——点火入口在场）。 */
    NEVER_GENERATED(1, "从未生成"),

    /** 生成中（编码 run 在途，含排队；进程内标记——重启即失，落回从未生成档）。 */
    GENERATING(2, "生成中"),

    /** 已生成（generated_at 落位＝首建成功收口＝Demo 首版成版锚；恒赢迭代在途）。 */
    GENERATED(4, "已生成");

    private final Integer code;
    private final String name;

    GenerationState(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}
