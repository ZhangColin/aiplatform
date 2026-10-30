package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 远端模型清单拉取回执（#368 手动同步，spec #364-T4）：远端全量条目（含库内
 * 对照）＋过时档位清单——「勾选入库」不经此响应回写，复用档位创建写口
 * （POST /api/backoffice/model-tiers，语义键人工命名）。
 *
 * <p><b>对照语义</b>（票 #368）：</p>
 * <ul>
 * <li>{@code remoteModels[].trackedTierKeys} 空＝远端有、库里无（建议入库——
 *     勾选后走档位创建，modelName 填该条 modelId）；非空＝已入库档位键清单；</li>
 * <li>{@code staleTiers}＝库内指向远端已无（该通道档位行的实际模型名不在远端
 *     清单——提示过时，升版/改指向前处置）。</li>
 * </ul>
 *
 * <p>条目 status 如实转述上游（方舟：无 status＝活跃、Shutdown/Retiring＝下架/
 * 退场中——建议入库时结合判断，接口不代拍）。</p>
 *
 * @param providerKey  通道键（拉取寻址）
 * @param pulledAt     拉取时刻（手动同步无缓存——每次全量真拉）
 * @param remoteModels 远端全量模型条目（modelId 序稳定返回）
 * @param staleTiers   库内指向远端已无的档位行（tierKey 序稳定返回）
 */
public record BackofficeRemoteModelCatalogResponse(
        String providerKey,
        LocalDateTime pulledAt,
        List<RemoteModelEntry> remoteModels,
        List<StaleTier> staleTiers) {

    /** 单条远端模型：模型 ID（入库即此值）＋辨识字段＋已入库档位键（空＝建议入库）。 */
    public record RemoteModelEntry(String modelId, String name, String status, String domain,
            List<String> trackedTierKeys) {
    }

    /** 库内过时档位：实际模型名不在远端清单（提示过时——升版或改指向）。 */
    public record StaleTier(String tierKey, String modelName, String displayName, boolean enabled) {
    }
}
