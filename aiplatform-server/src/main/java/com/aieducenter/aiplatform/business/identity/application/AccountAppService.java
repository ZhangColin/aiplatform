package com.aieducenter.aiplatform.business.identity.application;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountResponse;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

/**
 * 账号查询用例：全量账号清单（v1 无成员页，量小不分页、建档顺序稳定）。
 */
@Service
public class AccountAppService {

    private final AccountRepository accountRepository;

    public AccountAppService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** 全量账号（建档顺序）。 */
    public List<AccountResponse> list() {
        return accountRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .map(AccountAppService::toResponse)
                .toList();
    }

    /**
     * 显示名（跨 BC 查名面：order 上下文后台订单视图嵌入用）。账号不存在或
     * 标识为 null 时返 null——订单下单账号可空，缺档如实呈现，不放大成错误。
     */
    public String displayNameOf(Long accountId) {
        if (accountId == null) {
            return null;
        }
        return accountRepository.findById(accountId)
                .map(Account::getDisplayName)
                .orElse(null);
    }

    private static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId().toString(), account.getDisplayName());
    }
}
