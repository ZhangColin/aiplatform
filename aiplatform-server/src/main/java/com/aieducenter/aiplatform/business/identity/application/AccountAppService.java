package com.aieducenter.aiplatform.business.identity.application;

import java.util.Collection;
import java.util.Comparator;
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
import com.aieducenter.aiplatform.business.identity.domain.aggregate.AccountBinding;
import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountBindingRepository;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

/**
 * 账号查询与登录建档用例。#327 绑定表拆分后取数链全走绑定表：外部标识（旧称
 * externalId，OIDC sub 存量、邮箱/手机号新增）→ 账号的换算与反向的「对外标识」
 * 取数，都以绑定行为正身——「迁 SSO 只动绑定表」在读写两面同时成立。
 *
 * <p>对外标识的确定性口径：identifier 无单独唯一约束（oidc sub 与邮箱/手机号
 * 命名空间互不重叠是常态、不以约束赌它），跨 provider 命中给固定优先序
 * oidc → email → phone；账号侧「主绑定标识」（后台摘要嵌入、档案读口回显）同序，
 * 同 provider 多绑定时取绑定 id 小者（先绑者）——v1 流程一账号一绑定，兜底口径
 * 只为确定性，不为多绑定经营。</p>
 */
@Service
public class AccountAppService {

    /** 对外标识解析的 provider 优先序（枚举 code 升序＝oidc → email → phone） */
    private static final Comparator<AccountBinding> PRIMARY_ORDER =
            Comparator.comparing(AccountBinding::getProvider,
                    Comparator.comparing(AccountBindingProvider::getCode));

    private final AccountRepository accountRepository;
    private final AccountBindingRepository bindingRepository;

    public AccountAppService(AccountRepository accountRepository,
            AccountBindingRepository bindingRepository) {
        this.accountRepository = accountRepository;
        this.bindingRepository = bindingRepository;
    }

    /** 全量账号（建档顺序）。 */
    public List<AccountResponse> list() {
        return accountRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .map(AccountAppService::toResponse)
                .toList();
    }

    /**
     * 账号 Id（跨 BC 换算面：order/project 上下文按外部标识过滤清单用，#156/#159）。
     * 契约不变（#327 搬迁零破坏）：解析改从绑定表——存量 OIDC sub 命中 oidc 行，
     * 验证码登录人群的邮箱/手机号同面可寻址。未命中返 empty——空结果还是错误码
     * 归调用面定。
     */
    @Transactional(readOnly = true)
    public Optional<Long> accountIdOf(String externalId) {
        return accountByIdentifier(externalId).map(Account::getId);
    }

    /**
     * 外部标识 → 账号（后台档案读口的寻址面）：绑定表跨 provider 命中，优先序
     * 见类注释。
     */
    @Transactional(readOnly = true)
    public Optional<Account> accountByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        return bindingRepository.findFirstByIdentifierOrderByProviderAscIdAsc(identifier)
                .flatMap(binding -> accountRepository.findById(binding.getAccountId()));
    }

    /**
     * OIDC 回调 upsert（凭据住绑定表后的平移形）：按 (oidc, sub) 寻址——已建档
     * 同步显示名（IdP 是显示名权威），未建档建账号＋oidc 绑定行。并发同 sub 首登
     * 撞唯一索引的窄路径由 uk_idn_account_bindings 兜底（罕见，v1 不重试）。
     */
    @Transactional
    public Account upsertOidc(String subject, String displayName) {
        return bindingRepository.findByProviderAndIdentifier(AccountBindingProvider.OIDC, subject)
                .map(binding -> accountRepository.findById(binding.getAccountId())
                        .map(existing -> {
                            if (existing.syncDisplayName(displayName)) {
                                return accountRepository.save(existing);
                            }
                            return existing;
                        })
                        .orElseThrow(() -> new IllegalStateException( // 绑定行在而账号档缺＝数据残缺，不静默重建
                                "oidc 绑行指向不存在的账号：" + binding.getAccountId())))
                .orElseGet(() -> registerAccount(AccountBindingProvider.OIDC, subject, displayName));
    }

    /**
     * 验证码登录 upsert（#327，无密码、登录即注册）：按 (provider, identifier)
     * 寻址——未命中静默建账号＋绑定行（显示名缺省＝标识原文，不造玄）；已命中
     * 原样返回——标识不是显示名权威（对照 oidc 流的同步语义），不为登录覆写档案。
     */
    @Transactional
    public Account loginByBinding(AccountBindingProvider provider, String identifier) {
        return bindingRepository.findByProviderAndIdentifier(provider, identifier)
                .map(binding -> accountRepository.findById(binding.getAccountId())
                        .orElseThrow(() -> new IllegalStateException( // 绑定行在而账号档缺＝数据残缺，不静默重建
                                "绑定行指向不存在的账号：" + binding.getAccountId())))
                .orElseGet(() -> registerAccount(provider, identifier, identifier));
    }

    /**
     * 账号摘要（跨 BC 取档面：order/project 后台读面嵌入用）。对外标识（账号档案
     * 读口的寻址键）自绑定表主绑定取（#327 起后台读面带 ownerExternalId 的取数链
     * 由此改道）。账号不存在或标识为 null 时返 null——下单/归属账号可空，缺档如实
     * 呈现，不放大成错误。
     */
    @Transactional(readOnly = true)
    public AccountBriefResponse briefOf(Long accountId) {
        if (accountId == null) {
            return null;
        }
        return accountRepository.findById(accountId)
                .map(account -> new AccountBriefResponse(
                        primaryIdentifierOf(accountId), account.getDisplayName()))
                .orElse(null);
    }

    /**
     * 账号摘要批量（{@link #briefOf} 的清单面，后台清单行嵌入用）：缺档/可空账号
     * 不在返回 Map——调用面取不到即 null（容缺呈现，口径同单笔）。对外标识一批
     * 取齐（绑定表两查询内），清单不放大查询次数。
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
        Map<Long, String> identifiers = primaryIdentifiersOf(ids);
        return accountRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, account -> new AccountBriefResponse(
                        identifiers.get(account.getId()), account.getDisplayName())));
    }

    /** 账号 → 主绑定标识（优先序见类注释；无绑定账号返 null——档案残缺如实呈现）。 */
    @Transactional(readOnly = true)
    public String primaryIdentifierOf(Long accountId) {
        return primaryIdentifiersOf(List.of(accountId)).getOrDefault(accountId, null);
    }

    private Map<Long, String> primaryIdentifiersOf(List<Long> accountIds) {
        return bindingRepository.findByAccountIdIn(accountIds).stream()
                .collect(Collectors.toMap(AccountBinding::getAccountId,
                        binding -> binding,
                        // 同账号多绑定：优先序取主（provider 同序再比绑定 id，先绑者优先）
                        AccountAppService::primaryOf))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getIdentifier()));
    }

    private static AccountBinding primaryOf(AccountBinding first, AccountBinding second) {
        return PRIMARY_ORDER.thenComparing(AccountBinding::getId).compare(first, second) <= 0
                ? first : second;
    }

    private Account registerAccount(AccountBindingProvider provider, String identifier,
            String displayName) {
        Account account = accountRepository.save(Account.register(displayName));
        bindingRepository.save(AccountBinding.bind(account.getId(), provider, identifier));
        return account;
    }

    private static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId().toString(), account.getDisplayName());
    }
}
