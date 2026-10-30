package com.semgravio.aiplatform.business.identity.application;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.identity.domain.aggregate.Guest;
import com.semgravio.aiplatform.business.identity.domain.port.GuestOwnershipPort;
import com.semgravio.aiplatform.business.identity.domain.repository.GuestRepository;

/**
 * 访客绑定平移用例（#328，spec #320 段②）：登录完成时请求携带有效访客凭证即
 * 把访客名下未绑定项目过户给账户——「填 owner_account_id＋访客行作废＋清凭证
 * cookie」单一事务＋幂等；过期访客拒绝绑定；账户会话优先于访客凭证（无凭证
 * 即无平移）。
 *
 * <p>语义边界：</p>
 * <ul>
 *   <li><b>有效凭证</b>（行在、未作废、在保留期内）→ 平移其未绑定项目（经
 *       {@link GuestOwnershipPort}，与「访客行作废」同事务）＋作废访客行＋清
 *       cookie；</li>
 *   <li><b>无效/无凭证</b>（无 cookie、凭证无行、已作废）→ 幂等 no-op：不报错
 *       （登录照常），不重复平移；仍清 cookie（账户会话接管）；</li>
 *   <li><b>过期访客</b>（行在、未作废、逾保留期）→ 拒绝绑定（不认凭证、不平移；
 *       未绑定项目不承诺找回的既有口径）；登录照常、清 cookie。</li>
 * </ul>
 *
 * <p>平移与否以「有效凭证」为唯一门；调用方（M01 验证码登录 / OIDC 回调）在登录
 * 成功落会话后触发，不阻断登录——访客凭证无效不是登录失败。</p>
 */
@Service
public class GuestBindingAppService {

    private final GuestRepository guestRepository;
    private final GuestOwnershipPort ownershipPort;
    private final GuestProperties properties;
    private final GuestAppService guestAppService;
    private final Clock clock;

    @Autowired
    public GuestBindingAppService(GuestRepository guestRepository, GuestOwnershipPort ownershipPort,
            GuestProperties properties, GuestAppService guestAppService) {
        this(guestRepository, ownershipPort, properties, guestAppService, Clock.systemUTC());
    }

    GuestBindingAppService(GuestRepository guestRepository, GuestOwnershipPort ownershipPort,
            GuestProperties properties, GuestAppService guestAppService, Clock clock) {
        this.guestRepository = guestRepository;
        this.ownershipPort = ownershipPort;
        this.properties = properties;
        this.guestAppService = guestAppService;
        this.clock = clock;
    }

    /**
     * 登录即自动平移（M01 与 OIDC 两路共用）：请求携带的访客凭证有效则平移其未绑定
     * 项目＋作废访客行，单一事务（归属平移与本动作同事务）；幂等（已作废 no-op，
     * 返回当前归属＝0 迁移）；过期访客拒绝（不平移）。返回平移结果与清 cookie 指令
     * （凭证非空时恒清——账户会话接管；无凭证则 null 不发 Set-Cookie）。
     */
    @Transactional
    public BindingOutcome bindOnLogin(String credential, Long accountId) {
        if (credential == null || credential.isBlank() || accountId == null) {
            return new BindingOutcome(0, null);
        }
        ResponseCookie clearCookie = guestAppService.clearCredentialCookie();
        Guest guest = guestRepository.findByCredential(credential).orElse(null);
        if (guest == null || guest.getRevokedAt() != null) {
            // 凭证无行或已作废：幂等 no-op（不报错、不重复平移），仍清 cookie
            return new BindingOutcome(0, clearCookie);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!guest.activeAt(now, properties.getRetentionPeriod())) {
            // 过期访客拒绝绑定：不认凭证、不平移（未绑定项目不承诺找回）
            return new BindingOutcome(0, clearCookie);
        }
        Long guestId = guest.getId();
        // 原子占位（并发 CAS）：把 revoked_at 由 NULL 翻成 now 的这一次才拿到平移权；
        // 并发同携一凭证时后到者受影响 0 行 → no-op（「并发不双移」）。平移与作废同一
        // 事务——平移失败整体回滚，占位亦回滚（不留半绑）。
        int claimed = guestRepository.revokeIfActive(credential, now);
        if (claimed == 0) {
            return new BindingOutcome(0, clearCookie);
        }
        int migrated = ownershipPort.migrateProjectsToAccount(guestId, accountId);
        return new BindingOutcome(migrated, clearCookie);
    }

    /**
     * 绑定平移结果：migratedProjects＝本次平移项目数（幂等重复调用/过期拒绝为 0）；
     * guestClearCookie＝清访客凭证 cookie 指令（凭证存在时非 null，端点层据此落
     * Set-Cookie；无凭证为 null）。
     */
    public record BindingOutcome(int migratedProjects, ResponseCookie guestClearCookie) {
    }
}
