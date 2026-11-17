package com.semgravio.aiplatform.business.project.domain.repository;

/**
 * 风格层 kill-switch 读腿（#543，ADR-0052 切片①）：单行开关表（V77）的生效值
 * 读口——物化通道每派发判定处调用（动态查、进程内不缓存：运营改表下一次
 * 派发判定即生效、不发版、进行中 run 不定格，#442/#517 开关保险同款语义）。
 *
 * <p>关＝回无规范现状（物化通道零动作零写入、收口扫描不扫）；开＝风格层生效
 * （新项目首个构建派发物化、风格来源行按 PRD 字段重物化）。表空/行缺＝
 * 缺省生效（V77 已种行）。</p>
 */
public interface StyleDirectionSwitchStore {

    /**
     * 风格层是否生效（true＝缺省态）：单行读，行缺/表空回落 true。
     */
    boolean enabled();
}
