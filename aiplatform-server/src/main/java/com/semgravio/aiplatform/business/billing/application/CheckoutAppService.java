package com.semgravio.aiplatform.business.billing.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutTopupCommand;
import com.semgravio.aiplatform.business.billing.application.dto.response.PoolView;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;
import com.semgravio.aiplatform.web.AuthException;

/**
 * 模拟收银台用例（#534 计费④，ADR-0047「模拟收银台」词条兑现）：准入码门槛
 * （固定码起步，配置面 {@link CheckoutProperties}，运营改档即生效）＋<b>只充
 * 当前上下文的扣减源池</b>（向非源池充值＝死钱——前端不呈现该选项，入账口
 * BILL_020 服务端双保险）。账户上下文充我的账户池；项目上下文充项目池——
 * 试用项目（源未选）充值入账即选定项目源（动作即选择语义，{@link PoolTopupAppService}
 * 承载）；项目源已选「账户余额」则拒绝（该上下文源池＝账户池，归「我的账户」
 * 上下文充值）。须先登录（未登录 401——前端 M01 先行）。
 *
 * <p>流水落 {@link PoolTopupAppService} 同一口：append-only、行后余额、无操作者
 * （自助形态）＋备注「模拟收银台充值」——后台入账（#535 机机面）带操作者留痕
 * 同账本可区分。</p>
 */
@Service
public class CheckoutAppService {

    /** 自助充值流水备注（模拟通道形态标注——渠道真接后备注随通道形态换版）。 */
    public static final String CHECKOUT_NOTE = "模拟收银台充值";

    private final CheckoutProperties properties;
    private final PoolTopupAppService poolTopupAppService;
    private final ProjectBillingRepository projectBillingRepository;

    public CheckoutAppService(CheckoutProperties properties,
            PoolTopupAppService poolTopupAppService,
            ProjectBillingRepository projectBillingRepository) {
        this.properties = properties;
        this.poolTopupAppService = poolTopupAppService;
        this.projectBillingRepository = projectBillingRepository;
    }

    /**
     * 准入码校验（独立校验端点——正确码才进金额选择）：码错误/缺失被拦并如实
     * 引导（BILL_019 message 指路联系平台）。比对做双方空白归一。
     */
    public void validateAccessCode(String code) {
        String expected = properties.getAccessCode();
        if (code == null || code.isBlank() || expected == null
                || !code.trim().equals(expected.trim())) {
            throw new ApplicationException(BillingMessage.ACCESS_CODE_INVALID);
        }
    }

    /**
     * 账户上下文充值（「我的账户」入口）：充当前登录账户的账户池。
     *
     * @param accountId 当前登录账户（null＝未登录——401，前端 M01 先行）
     * @param command   准入码＋金额（分）
     */
    @Transactional
    public CheckoutReceipt topupAccount(Long accountId, CheckoutTopupCommand command) {
        requireLogin(accountId);
        // 空体（@RequestBody(required=false)）按码缺失拦——BILL_019 如实引导，不 NPE
        validateAccessCode(command == null ? null : command.accessCode());
        PoolTopupAppService.TopupReceipt receipt = poolTopupAppService.topup(
                accountId, null, amountOf(command), null, CHECKOUT_NOTE);
        return CheckoutReceipt.of(receipt);
    }

    /**
     * 项目上下文充值（项目账单/拦截卡充值入口）：充项目池——源未选或项目源的
     * 项目才可达（入账即选定项目源）；账户源项目拒绝（BILL_020：源池是账户池，
     * 走「我的账户」上下文充值；前端不呈现本选项）。
     *
     * @param accountId 当前登录账户（null＝未登录——401）
     * @param projectId 项目（归属守卫先于控制器——非本人 404 PRJ_001）
     * @param command   准入码＋金额（分）
     */
    @Transactional
    public CheckoutReceipt topupProject(Long accountId, Long projectId,
            CheckoutTopupCommand command) {
        requireLogin(accountId);
        validateAccessCode(command == null ? null : command.accessCode());
        ProjectBilling billing = projectBillingRepository.findByProjectId(projectId)
                .orElse(null);
        if (billing != null && billing.getDeductionSource() == DeductionSource.ACCOUNT) {
            throw new ApplicationException(BillingMessage.TOPUP_TARGET_NOT_SOURCE_POOL);
        }
        PoolTopupAppService.TopupReceipt receipt = poolTopupAppService.topup(
                null, projectId, amountOf(command), null, CHECKOUT_NOTE);
        return CheckoutReceipt.of(receipt);
    }

    /** 金额取值（空体/空值归 0——正数守卫在入账口 BILL_002 统一拦）。 */
    private long amountOf(CheckoutTopupCommand command) {
        return command == null || command.amountFen() == null ? 0L : command.amountFen();
    }

    /** 收银台须先登录（CONTEXT「模拟收银台」：须先登录；未登录先走 M01——401 全局出口）。 */
    private void requireLogin(Long accountId) {
        if (accountId == null) {
            throw AuthException.unauthorized();
        }
    }

    /** 充值回执读面（池现值——到账即时反映的消费面）。 */
    public record CheckoutReceipt(PoolView pool, Long entryId) {

        private static CheckoutReceipt of(PoolTopupAppService.TopupReceipt receipt) {
            return new CheckoutReceipt(
                    new PoolView(String.valueOf(receipt.poolId()), receipt.balanceAfter()),
                    receipt.entryId());
        }
    }
}
