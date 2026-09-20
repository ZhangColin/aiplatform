package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 项目生成态四态投影（#222，ADR-0020 裁决四）：由轨道表＋generated_at＋在途标记
 * 派生的呈现态（不是落库事实——事实源是 {@code prj_projects.generated_at} 与
 * {@code prj_generation_segments}），REST 详情透出、前端档位由此派生（不再依赖
 * SSE 会话态——刷新/回访后档位仍正确）。
 *
 * <p>派生序：已生成（generated_at 落位）恒赢——迭代/修正 run 在途不改变生成态；
 * 未生成时编码 run 在途 = 生成中（含已提交未起跑的排队段）；既未生成也不在途而
 * 轨道表有片行 = 生成中断（run 失败终态、进程重启丢在途标记、PRD 演进致旧计划
 * 过期同档——等待用户永不判死，「继续生成」出口的档位）；其余 = 从未生成
 * （含存量中断项目——轨道表之前的在途项目无片行，恢复走计划重派，「继续生成」
 * 同一出口）。</p>
 */
public enum GenerationState implements BaseEnum<GenerationState> {

    /** 从未生成（无片行且不在途：PRD 未产出/产出未起跑/存量无轨道——「继续生成」触发计划重派同路）。 */
    NEVER_GENERATED(1, "从未生成"),

    /** 生成中（编码 run 在途，含排队段；进程内标记——重启即失，落到中断档）。 */
    GENERATING(2, "生成中"),

    /** 生成中断（有轨道片行但不在途且未生成：失败终态/重启/中断——「继续生成」从断点续跑）。 */
    INTERRUPTED(3, "生成中断"),

    /** 已生成（generated_at 落位＝最后一片收口＝「确认下单」门槛；恒赢迭代在途）。 */
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
