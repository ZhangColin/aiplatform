package com.semgravio.aiplatform.business.order.domain.aggregate;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;

/**
 * 凭证字节伴行行（{@code ord_payment_voucher_files}，#337）：bytea 内联、与
 * 凭证元数据行同事务落库（{@code PaymentPlanAppService} 上传口收口——节点
 * 状态/凭证行/字节三写一事务）。独立小聚合根（同
 * {@code UsageEventEntry} 形制——append-only 行自持仓储）无关联映射：共享主键
 * 一对一的 JPA 映射复杂度不入库，伴行关系由主键同名承载（{@code voucher_id}）。
 * 伴行表隔离聚合读面：清单查询不触字节，仅按 id 点取（后台核验看证）。
 */
@Entity
@Table(name = "ord_payment_voucher_files")
@Aggregate
@Getter
public class PaymentVoucherFile implements AggregateRoot<PaymentVoucherFile, Long> {

    /** 所属凭证 id（同 id 一对一——主键即外键语义，不另设关联列）。 */
    @Id
    @Column(name = "voucher_id", nullable = false, updatable = false)
    private Long voucherId;

    /** 文件字节（≤10MB 业务上限在上传口校验）。 */
    @Column(name = "content", nullable = false, updatable = false)
    private byte[] content;

    protected PaymentVoucherFile() {
    }

    /** 凭证字节落行（与凭证元数据同事务，上传口调用）。 */
    public static PaymentVoucherFile of(Long voucherId, byte[] content) {
        PaymentVoucherFile file = new PaymentVoucherFile();
        file.voucherId = voucherId;
        file.content = content;
        return file;
    }

    @Override
    public Long getId() {
        return voucherId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return sameIdentityAs((PaymentVoucherFile) o);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(voucherId);
    }
}
