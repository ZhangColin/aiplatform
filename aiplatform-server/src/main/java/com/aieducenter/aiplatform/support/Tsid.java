package com.aieducenter.aiplatform.support;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CodeMessage;

/**
 * TSID 解析单源（后台公共出口，#199）：路径段与过滤值两形态共用同一解析口径
 * （非数值/非正数即「不存在的标识」）。跨层共享的中性工具——控制器（寻址）与
 * 应用服务（过滤）同源，不落 web 适配面，避免应用层反向依赖北向适配层。
 *
 * <p>两出口语义不同：</p>
 * <ul>
 *   <li>{@link #resolve} 严格式（寻址语义）——畸形标识抛 {@code notFound}（404）；</li>
 *   <li>{@link #parseOrNull} 宽松式（过滤语义）——畸形标识返 null，调用面短路空清单（200）。</li>
 * </ul>
 *
 * <p>七个调用点归一至此：订单寻址×2 形态（订单/项目）、项目寻址、单价行、知识
 * 素材、沙箱观测 requireWorkspace 与宽松式 parseOrderId/parseProjectId。路径段
 * 与过滤值入参上下文不同（前者不经 trim、后者允许粘贴空白），两法各自保留该
 * 口径。本类之外的 workspace action/lifecycle 内部寻址不属后台控制器面，未归一
 * （与 {@code currentOperator()}「同款不同身」同款备案取舍）。</p>
 */
public final class Tsid {

    private Tsid() {
    }

    /**
     * 寻址解析（严格式）：路径段 TSID → Long，非数值/非正数（含空白）即不存在的
     * 标识，抛 {@code notFound}（语义上同 404）。路径段不 trim——Spring 路径变量
     * 原样传入，非数值即 404。
     */
    public static Long resolve(String raw, CodeMessage notFound) {
        try {
            long parsed = Long.parseLong(raw);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非数值/空白 → 落到下方统一 404
        }
        throw new ApplicationException(notFound);
    }

    /**
     * 过滤解析（宽松式）：过滤值 TSID → Long，空白/非数值/非正数返 null（调用面
     * 短路空清单，检索维度的「无命中」不是错误）。过滤值允许粘贴空白，先 trim。
     */
    public static Long parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
