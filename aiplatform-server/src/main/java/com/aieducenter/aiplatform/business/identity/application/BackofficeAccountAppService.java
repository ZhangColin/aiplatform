package com.aieducenter.aiplatform.business.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.identity.application.dto.response.BackofficeAccountProfileResponse;
import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

/**
 * 后台账号读面（#154 账号域，/api/backoffice 机机签名）：按 externalId 查极简
 * 档案——监管场景确认用户身份正身。externalId＝OIDC sub＝identity 账户 Id，
 * 对外正身；我方 accountId 只是内部代理键。无清单浏览、无 identity 富化
 * （联系方式/封禁等归 identity/admin 侧，不代理不透传）。
 */
@Service
public class BackofficeAccountAppService {

    private final AccountRepository accountRepository;

    public BackofficeAccountAppService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * 按 externalId 查极简档案：我方留存四字段原样（id/externalId/displayName/createdAt）。
     *
     * @throws ApplicationException IDN_004 externalId 未命中（不返回半空档案）
     */
    @Transactional(readOnly = true)
    public BackofficeAccountProfileResponse profile(String externalId) {
        return accountRepository.findByExternalId(externalId)
                .map(BackofficeAccountProfileResponse::of)
                .orElseThrow(() -> new ApplicationException(IdentityMessage.ACCOUNT_NOT_FOUND));
    }
}
