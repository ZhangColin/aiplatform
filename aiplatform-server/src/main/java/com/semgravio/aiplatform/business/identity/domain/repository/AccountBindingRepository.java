package com.semgravio.aiplatform.business.identity.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.identity.domain.aggregate.AccountBinding;
import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;

/**
 * 账号绑定仓储（{@code idn_account_bindings}）。按 (provider, identifier) 寻址＝
 * 登录 upsert 的查询面；按 identifier 跨 provider 命中＋按 provider 升序＝后台
 * 对外标识寻址的确定性优先序（oidc → email → phone）。
 */
public interface AccountBindingRepository extends BaseRepository<AccountBinding, Long> {

    Optional<AccountBinding> findByProviderAndIdentifier(AccountBindingProvider provider,
            String identifier);

    /** 同 identifier 跨 provider 命中（provider 升序＝优先序，多行时取首行）。 */
    Optional<AccountBinding> findFirstByIdentifierOrderByProviderAscIdAsc(String identifier);

    List<AccountBinding> findByAccountIdIn(Collection<Long> accountIds);
}
