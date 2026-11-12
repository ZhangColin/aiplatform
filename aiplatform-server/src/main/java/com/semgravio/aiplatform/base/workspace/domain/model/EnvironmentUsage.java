package com.semgravio.aiplatform.base.workspace.domain.model;

import java.util.Set;

/**
 * 环境用量一瞥（#489 资源面大盘采样，{@code EnvironmentBackend#environmentUsage}
 * 的返回形）：docker system df（非 verbose）四类用量＋工作区命名资源清单编号。
 * 是资源大盘（resources/overview）的 docker 侧数据源——采样轮旁路探查落库，
 * 读面零 docker 调用、纯读库秒级返回。
 *
 * <p>体量解析口径：docker CLI 的人类可读串按<b>十进制单位</b>换算（go-units
 * HumanSize，如 4442639553 → "4.44GB"＝4.44×10⁹——与 docker 自身显示同基），
 * 计数值为回执原数。「可回收」字段不入本形（判定红线：该口径把休眠卷一并计入，
 * 永不作准绳——ADR-0045）。</p>
 *
 * @param images               镜像用量（计数/体量）
 * @param containers           容器用量（计数/体量）
 * @param localVolumes         本地卷用量（计数/体量）
 * @param buildCache           build cache 用量（计数/体量）
 * @param workspaceContainerIds 在跑主命名容器编号集（无主计数＝对注册表求差）
 * @param workspaceVolumeIds    工作区卷编号集（同上）
 */
public record EnvironmentUsage(
        Usage images,
        Usage containers,
        Usage localVolumes,
        Usage buildCache,
        Set<Long> workspaceContainerIds,
        Set<Long> workspaceVolumeIds) {

    /**
     * 单类用量：计数与体量字节。
     */
    public record Usage(long count, long sizeBytes) {

        /** docker system df 无该类回执时的空用量（如实零，非缺测）。 */
        public static Usage zero() {
            return new Usage(0, 0);
        }
    }
}
