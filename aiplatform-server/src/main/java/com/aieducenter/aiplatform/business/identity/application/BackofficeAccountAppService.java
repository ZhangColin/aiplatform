package com.aieducenter.aiplatform.business.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.identity.application.dto.response.BackofficeAccountProfileResponse;
import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;

/**
 * 后台账号读面（#154 账号域，/api/backoffice 机机签名）：按对外正身查极简
 * 档案——监管场景确认用户身份正身。#327 绑定表拆分：externalId 语义扩为
 * 「主绑定标识」（存量 OIDC sub 寻址零变化；验证码登录人群同面可寻址），
 * 解析经 {@link AccountAppService}（绑定表取数链单点）。我方 accountId 只是
 * 内部代理键。无清单浏览、无 identity 富化（联系方式/封禁等归 identity/admin
 * 侧，不代理不透传）。
 */
@Service
public class BackofficeAccountAppService {

    private final AccountAppService accountAppService;

    public BackofficeAccountAppService(AccountAppService accountAppService) {
        this.accountAppService = accountAppService;
    }

    /**
     * 按对外正身查极简档案：我方留存四字段原样（id/externalId/displayName/createdAt，
     * externalId＝主绑定标识）。
     *
     * @throws ApplicationException IDN_004 对外正身未命中（不返回半空档案）
     */
    @Transactional(readOnly = true)
    public BackofficeAccountProfileResponse profile(String externalId) {
        return accountAppService.accountByIdentifier(externalId)
                .map(account -> BackofficeAccountProfileResponse.of(account,
                        accountAppService.primaryIdentifierOf(account.getId())))
                .orElseThrow(() -> new ApplicationException(IdentityMessage.ACCOUNT_NOT_FOUND));
    }
}
