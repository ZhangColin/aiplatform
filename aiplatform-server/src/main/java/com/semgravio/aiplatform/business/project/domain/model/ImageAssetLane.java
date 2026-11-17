package com.semgravio.aiplatform.business.project.domain.model;

import com.cartisan.core.domain.BaseEnum;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * 出图资产落点轨道（#545，ADR-0052 图片能力下放构建轨）：同一出图内核
 * （{@code ImageGenerationAppService}／出图工具件）两轨语义不同——
 * <ul>
 *   <li>{@link #DESIGN 设计轨}：设计执行体产出的设计稿，落
 *       {@code design/}（设计过程产物目录，候选各代累积面，#283/ADR-0027），
 *       计量 dims 归 {@code designer}；</li>
 *   <li>{@link #BUILD 构建轨}：run 执行体为演示数据配的真实位图（首屏 hero／
 *       关键实体图／空态美化），落 {@code public/}（应用静态资源——Next.js
 *       根 URL 直引的交付资产），计量 dims 归 {@code executor}。</li>
 * </ul>
 * 枚举只承载落点目录；计量归属在应用服务侧装配（轨道不引计量维表）。
 * 不落库不经 REST——BaseEnum 为架构规约（domain 枚举统一形），寻址走枚举名。
 */
public enum ImageAssetLane implements BaseEnum<ImageAssetLane> {

    /** 设计轨：设计执行体（设计稿产物落 design/）。 */
    DESIGN(1, WorkspaceLayout.DESIGN_DIR),

    /** 构建轨：run 执行体（演示配图落 public/，应用交付资产）。 */
    BUILD(2, WorkspaceLayout.PUBLIC_DIR);

    private final Integer code;
    private final String dir;

    ImageAssetLane(Integer code, String dir) {
        this.code = code;
        this.dir = dir;
    }

    /** 框架约定码（BaseEnum 规约面；本枚举不落库不经 REST，code 不出面）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 框架约定名（即枚举名）。 */
    @Override
    public String getName() {
        return name();
    }

    /** 工作区锚定目录名（design 或 public）。 */
    public String dir() {
        return dir;
    }
}
