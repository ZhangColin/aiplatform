package com.aieducenter.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 沙箱容器实态（#173 后台观测面，ADR-0016 意图/实态分离的实态侧）：docker 探查的
 * 一瞥结论，不落库（DB 只记期望态）——实态列与期望态列分示，漂移一眼可见。
 * 与 {@link DesiredState} 无映射关系：期望运行而 {@link #ABSENT} 即「实态已亡」
 * 漂移行，期望休眠/封存而 {@code ABSENT} 是正常收敛态。
 *
 * <p>{@link #UNKNOWN} 是诚实位：探查自身失败（docker daemon 不可达等）与「容器
 * 不在」区分——观测面如实分示，不把基础设施故障伪装成全员漂移。</p>
 */
public enum ContainerState implements BaseEnum<ContainerState> {

    RUNNING(1, "运行中"),

    STOPPED(2, "已停止"),

    ABSENT(3, "无容器"),

    UNKNOWN(4, "未知");

    private final Integer code;
    private final String name;

    ContainerState(Integer code, String name) {
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
