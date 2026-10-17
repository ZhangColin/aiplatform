package com.aieducenter.aiplatform.business.order.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentVoucherFile;

/**
 * 凭证字节伴行仓储（#337）：与凭证元数据行同事务双写（上传口收口），点取按
 * 主键（＝凭证 id）——唯一触字节的口。
 */
public interface PaymentVoucherFileRepository extends BaseRepository<PaymentVoucherFile, Long> {
}
