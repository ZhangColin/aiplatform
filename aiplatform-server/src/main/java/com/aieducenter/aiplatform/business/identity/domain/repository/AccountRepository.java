package com.aieducenter.aiplatform.business.identity.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;

/**
 * 账号仓储（{@code idn_accounts}，#327 绑定表拆分后瘦身形）。按外部标识（OIDC
 * sub / 邮箱 / 手机号）的寻址不在本仓储——凭据住绑定表，换算面归
 * {@link AccountBindingRepository} 与 AccountAppService。
 */
public interface AccountRepository extends BaseRepository<Account, Long> {
}
