package com.semgravio.aiplatform.base.metering.domain.port;

import java.time.Instant;
import java.util.Map;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 成本观测切片锚查询端口（CLIENT 直调，#536）：subject（计量归属原值）→ 切片锚
 * 时点。成本观测读面按锚对平台成本用量分两桶（{@code ts < 锚}＝获客成本、
 * {@code ts >= 锚}＝用户费用，{@link com.semgravio.aiplatform.base.metering.domain.enums.CostBucket}）——
 * 锚的语义由供方解释（billing 侧＝付费状态翻转时点，ADR-0047 计费边界），metering
 * 只按锚机械分桶、底座不解释存在性（subject 不透明口径不变）。
 *
 * <p>行缺席＝无锚：该 subject 用量全量落获客成本桶（未翻转项目/无计费态行）。
 * 实现供方在业务层（billing BC 读 {@code bil_project_billing.flipped_at}，弱引用
 * 不破 base 不依赖 business 的边界——照 {@link AgentKindNames} 端口反转先例）；
 * 计量服务迁出时本端口换 REST 适配器，签名不动（A1 §2.1）。</p>
 */
@Port(PortType.CLIENT)
public interface SubjectSliceAnchors {

    /**
     * 全量切片锚：已翻转 subject → 翻转时点；未翻转/无行缺席（消费端视全量获客
     * 成本桶）。返回全量而非按入参过滤——锚集＝已翻转行全集（小表），全局总览
     * 与项目清单两读面同读一次。
     */
    Map<String, Instant> anchorsBySubject();
}
