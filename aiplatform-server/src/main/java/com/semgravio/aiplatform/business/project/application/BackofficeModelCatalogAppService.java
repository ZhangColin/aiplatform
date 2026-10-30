package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeRemoteModelCatalogResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelTier;
import com.semgravio.aiplatform.business.project.domain.port.ModelCatalog;
import com.semgravio.aiplatform.business.project.domain.port.ModelCatalogFetcher;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

/**
 * 后台模型清单手动拉取用例（#368，spec #364-T4，ADR-0031「手动同步、不做自动
 * 定时」）：按通道行真拉远端清单（OpenAI 兼容 models 端点）→ 与库内档位对照
 * （远端有库里无＝建议入库；库内指向远端已无＝提示过时）。勾选入库不经此——
 * 复用 {@link BackofficeModelChannelAppService} 档位创建写口（语义键人工命名），
 * 拉取面零新写路径、零留痕（纯读动作）。
 *
 * <p><b>明确报错不静默</b>：通道 key 空（PRJ_076）与上游不可用（PRJ_077——
 * 非 200/坏响应/连接异常，理由如实转述）皆错误信封回给运营，无降级假数据。
 * 停用通道可拉（巡检语义——停用拦在装配面 resolve 层，不拦维护面读上游）。</p>
 *
 * <p>不挂事务：两次库读各自即席（动态查库同 {@code ModelTierAppService} 口径），
 * 不为一次上游 HTTP 调用占住连接。</p>
 */
@Service
public class BackofficeModelCatalogAppService {

    private final ModelChannelStore channelStore;

    private final ModelCatalogFetcher catalogFetcher;

    public BackofficeModelCatalogAppService(ModelChannelStore channelStore,
            ModelCatalogFetcher catalogFetcher) {
        this.channelStore = channelStore;
        this.catalogFetcher = catalogFetcher;
    }

    /**
     * 拉取远端模型清单并与库内档位对照（每次全量真拉、无缓存）。
     *
     * @throws ApplicationException PRJ_065 通道不存在；PRJ_076 通道 key 空；
     *                              PRJ_077 上游拉取失败（理由如实转述）
     */
    public BackofficeRemoteModelCatalogResponse pull(String providerKey) {
        ModelProvider provider = requireProvider(providerKey);
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            throw new ApplicationException(ProjectMessage.MODEL_CATALOG_PULL_KEY_MISSING,
                    provider.providerKey());
        }
        ModelCatalog catalog = catalogFetcher.fetch(provider.baseUrl(), provider.apiKey());
        if (catalog instanceof ModelCatalog.Fetched fetched) {
            return compare(provider, fetched.models());
        }
        throw new ApplicationException(ProjectMessage.MODEL_CATALOG_PULL_FAILED,
                ((ModelCatalog.Failed) catalog).reason());
    }

    /** 对照（票 #368 语义）：远端条目标已入库档位键（空＝建议入库）；库内实际
     * 模型名不在远端清单＝过时档位。排序 modelId/tierKey 字典序——回执稳定可 diff。 */
    private BackofficeRemoteModelCatalogResponse compare(ModelProvider provider,
            List<ModelCatalog.RemoteModel> remoteModels) {
        List<ModelTier> providerTiers = channelStore.findAllTiers().stream()
                .filter(tier -> provider.providerKey().equals(tier.providerKey()))
                .toList();
        Map<String, List<String>> trackedTierKeysByModelName = providerTiers.stream()
                .collect(groupingBy(ModelTier::modelName,
                        mapping(ModelTier::tierKey, toList())));
        Set<String> remoteModelNames = remoteModels.stream()
                .map(ModelCatalog.RemoteModel::modelId)
                .collect(toSet());

        List<BackofficeRemoteModelCatalogResponse.RemoteModelEntry> entries = remoteModels.stream()
                .sorted(Comparator.comparing(ModelCatalog.RemoteModel::modelId))
                .map(model -> new BackofficeRemoteModelCatalogResponse.RemoteModelEntry(
                        model.modelId(), model.name(), model.status(), model.domain(),
                        trackedTierKeysByModelName.getOrDefault(model.modelId(), List.of())))
                .toList();
        List<BackofficeRemoteModelCatalogResponse.StaleTier> staleTiers = providerTiers.stream()
                .filter(tier -> !remoteModelNames.contains(tier.modelName()))
                .sorted(Comparator.comparing(ModelTier::tierKey))
                .map(tier -> new BackofficeRemoteModelCatalogResponse.StaleTier(
                        tier.tierKey(), tier.modelName(), tier.displayName(), tier.enabled()))
                .toList();
        return new BackofficeRemoteModelCatalogResponse(provider.providerKey(),
                LocalDateTime.now(), entries, staleTiers);
    }

    /** 通道行寻址：无行 404 PRJ_065（同维护面语义）。 */
    private ModelProvider requireProvider(String providerKey) {
        ModelProvider provider = providerKey == null || providerKey.isBlank()
                ? null : channelStore.findProvider(providerKey.trim());
        if (provider == null) {
            throw new ApplicationException(ProjectMessage.MODEL_PROVIDER_NOT_FOUND);
        }
        return provider;
    }
}
