package com.semgravio.aiplatform.business.order.domain.repository;

import java.util.Collection;
import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.order.domain.aggregate.PaymentVoucher;

/**
 * 转账凭证仓储（#337）：元数据行的清单与点取——字节伴行 files 表独立仓储
 * （{@code PaymentVoucherFileRepository}）同事务双写，本仓储不触字节。
 */
public interface PaymentVoucherRepository extends BaseRepository<PaymentVoucher, Long> {

    /** 一批节点的凭证清单（追加序＝上传序，TSID 时间有序；按调用方分组）。 */
    List<PaymentVoucher> findByNodeIdInOrderByIdAsc(Collection<Long> nodeIds);

    /** 一批节点的凭证计数（节点重录窗口的「有凭证」腿——支付活动已开始的判定输入）。 */
    long countByNodeIdIn(Collection<Long> nodeIds);
}
