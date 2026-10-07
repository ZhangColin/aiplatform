package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 对话史条目的会话来源（#298 后台对接件——「对话史含设计会话」的读面标识）：
 * 设计过程为每设计物一个独立设计会话（ADR-0024），与主智能体单会话并存于同一
 * 对话史；读面带来源标识供后台按来源分组或标注（admin 侧消费）。
 *
 * <p><b>降级口径（存储不同构，对齐超预期已备案）</b>：对话表无来源列，读侧按
 * 结构标记推导——设计会话收尾卡（首产/改稿/定稿）携 {@code drafts} 稿清单
 * （SSE 收口扩载契约键，仅设计轨道拼装）⇔ 设计会话来源；其余条目（含作用域
 * 改稿发言——无结构标记）出 {@code null}＝主会话缺省，不假装归属（主会话是
 * 缺省态非标识值，不出枚举值位）。设计会话的完整过程稿本在智能体会话存储、
 * 不在对话表（收尾卡已是凝聚物），并入读面属超预期、不做。</p>
 */
public enum ConversationSessionSource implements BaseEnum<ConversationSessionSource> {

    /** 设计会话（每设计物一个；判据＝收尾卡携 drafts 稿清单）。 */
    DESIGN(2, "设计会话");

    private final Integer code;
    private final String name;

    ConversationSessionSource(Integer code, String name) {
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
