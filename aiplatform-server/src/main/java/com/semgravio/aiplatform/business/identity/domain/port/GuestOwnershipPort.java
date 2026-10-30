package com.semgravio.aiplatform.business.identity.domain.port;

/**
 * 访客项目归属平移端口（#328 绑定平移，spec #320 段②）：「访客名下未绑定项目
 * 过户给账户」这一能力的南向需求接口——归属正身归 {@code business.project}
 * （prj_projects 的 owner 双列），本端口是 identity 侧对它的依赖倒置：identity
 * 只依赖自己的端口，适配器（实现方）在 project 侧装配，避免 identity ↔ project
 * 的限界上下文循环依赖（project 已依赖 identity 的访客域）。
 *
 * <p>事务：实现方自持 {@code @Transactional}（REQUIRED），与调用方
 * {@code GuestBindingAppService#bindOnLogin} 同事务——「填 owner_account_id＋
 * 访客行作废」必须原子（票面「单一事务」）。</p>
 *
 * <p>幂等：只动仍挂在 guestId 名下的项目（owner_guest_id = guestId）；已平移过
 * （owner_guest_id 已清）的项目不再命中，重复调用返回 0。</p>
 */
public interface GuestOwnershipPort {

    /**
     * 把 guestId 名下的未绑定项目平移给 accountId（owner_account_id 落值、
     * owner_guest_id 清空——owner 双列逻辑互斥，库层 CHECK 兜底）。
     *
     * @return 本次平移的项目数（幂等：已无访客归属即 0）
     */
    int migrateProjectsToAccount(Long guestId, Long accountId);
}
