package com.semgravio.aiplatform.business.order.domain.aggregate;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

/**
 * 转账凭证聚合根（{@code ord_payment_vouchers}，#337）：用户上传的银行转账
 * 电子回单/水单——<b>上传仅代表已提交</b>（核验结论在
 * {@code PaymentVerification} 留痕行），<b>追加保留不覆盖</b>（多次上传各一行，
 * append-only 只插不改写）。元数据行轻载可列；字节伴行
 * {@code PaymentVoucherFile}（bytea 内联、同事务双写——共享主键同名承载伴行
 * 关系，清单查询不触字节）。
 *
 * <p>格式与大小校验（pdf/png/jpg/jpeg、≤10MB）在应用层上传口（口径同物料
 * 上传 #286：如实报错不静默压缩）。</p>
 */
@Entity
@Table(name = "ord_payment_vouchers")
@Aggregate
@Getter
public class PaymentVoucher extends Auditable implements AggregateRoot<PaymentVoucher, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属付款节点（ord_payment_nodes 软引用——节点状态变更同事务、行身份不设 FK）。 */
    @Column(name = "node_id", nullable = false, updatable = false)
    private Long nodeId;

    /** 净化后原始文件名（回执单/水单人读）。 */
    @Column(name = "file_name", nullable = false, updatable = false, length = 500)
    private String fileName;

    /** MIME（application/pdf | image/png | image/jpeg——展示与后台点看用）。 */
    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    /** 字节数（≤10MB 业务上限）。 */
    @Column(name = "byte_size", nullable = false, updatable = false)
    private Long byteSize;

    protected PaymentVoucher() {
    }

    /**
     * 上传一凭证（应用层上传口调用——格式/大小已校验、文件名已净化）：id 此处
     * 定格，字节伴行行以同 id 落（同事务双写归 {@code PaymentPlanAppService}）。
     */
    public static PaymentVoucher upload(Long nodeId, String fileName, String contentType,
            long byteSize) {
        PaymentVoucher voucher = new PaymentVoucher();
        voucher.id = TsidGenerator.newInstance().generate();
        voucher.nodeId = nodeId;
        voucher.fileName = fileName;
        voucher.contentType = contentType;
        voucher.byteSize = byteSize;
        return voucher;
    }

    /**
     * 聚合 ID。
     */
    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return sameIdentityAs((PaymentVoucher) o);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
