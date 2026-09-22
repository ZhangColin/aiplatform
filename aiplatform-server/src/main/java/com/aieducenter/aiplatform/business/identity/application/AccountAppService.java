package com.aieducenter.aiplatform.business.identity.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
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
     * 账号 Id（跨 BC 换算面：order 上下文按 externalId 过滤订单清单用，#156）。
     * externalId＝对外正身、accountId 只是内部代理键，换算在 identity 侧完成、
     * 外域不触 idn_accounts。未命中返 empty——空结果还是错误码归调用面定。
     */
    @Transactional(readOnly = true)
    public Optional<Long> accountIdOf(String externalId) {
        return accountRepository.findByExternalId(externalId)
                .map(Account::getId);
    }

    /**
     * 账号摘要（跨 BC 取档面：order/project 后台读面嵌入用）。externalId 与显示名
     * 一批取齐（#243 起后台读面带 ownerExternalId——账号档案读口的寻址键）。
     * 账号不存在或标识为 null 时返 null——下单/归属账号可空，缺档如实呈现，
     * 不放大成错误。
     */
    public AccountBriefResponse briefOf(Long accountId) {
        if (accountId == null) {
            return null;
        }
        return accountRepository.findById(accountId)
                .map(AccountBriefResponse::of)
                .orElse(null);
    }

    /**
     * 账号摘要批量（{@link #briefOf} 的清单面，后台清单行嵌入用）：缺档/可空账号
     * 不在返回 Map——调用面取不到即 null（容缺呈现，口径同单笔）。一批取齐
     * externalId＋显示名，清单不放大查询次数。
     */
    @Transactional(readOnly = true)
    public Map<Long, AccountBriefResponse> briefsOf(Collection<Long> accountIds) {
        List<Long> ids = accountIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accountRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, AccountBriefResponse::of));
    }

    private static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId().toString(), account.getDisplayName());
    }
}
