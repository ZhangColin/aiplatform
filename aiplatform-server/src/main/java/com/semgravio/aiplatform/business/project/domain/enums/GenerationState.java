package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 项目生成态投影（#222，ADR-0020 裁决四）：由 generated_at＋在途标记派生的
 * 呈现态（不是落库事实——事实源是 {@code prj_projects.generated_at}），REST 详情
 * 透出、前端档位由此派生（不依赖 SSE 会话态——刷新/回访后档位仍正确）。
 *
 * <p>#602 起轨道表机器退役：「生成中断」档失去轨道表判据，派生面暂并为三档
 * （中断档并入从未生成——{@code NEVER_GENERATED}/{@code GENERATING}/
 * {@code GENERATED} 三态消费；{@code INTERRUPTED} 枚举暂留不归一，四态的
 * 整体退役/重设归 #604）。派生序：已生成（generated_at 落位）恒赢——迭代/
 * 修正 run 在途不改变生成态；未生成时编码 run 在途 = 生成中（含已提交未起跑）；
 * 其余 = 从未生成。</p>
 */
public enum GenerationState implements BaseEnum<GenerationState> {

    /** 从未生成（PRD 未产出/产出未起跑/中断并入档——「继续生成」同出口）。 */
    NEVER_GENERATED(1, "从未生成"),

    /** 生成中（编码 run 在途，含排队；进程内标记——重启即失，落回从未生成档）。 */
    GENERATING(2, "生成中"),

    /**
     * 生成中断（#602 起无消费面——轨道表退役后失去判据；#604 归一退役。
     * 暂留防外部 code 3 反序列化炸口）。
     */
    INTERRUPTED(3, "生成中断"),

    /** 已生成（generated_at 落位＝最后一片收口＝Demo 首版成版锚；恒赢迭代在途）。 */
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
