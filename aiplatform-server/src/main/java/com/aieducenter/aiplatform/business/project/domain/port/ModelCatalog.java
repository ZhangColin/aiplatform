package com.aieducenter.aiplatform.business.project.domain.port;

import java.util.List;

/**
 * 远端模型清单拉取结果（#368 手动同步的端口契约，形制照 {@link SearchResult}）：
 * 成功带远端模型条目列表（供数方无关的最小契约面——模型 ID＋基础辨识字段）；
 * 失败带如实理由（非 200、坏响应、连接异常等）——不抛错不炸，理由由调用方
 * （后台清单拉取用例）转成明确错误信封，不静默。
 *
 * <p>条目 modelId 必有（缺 id 条目＝坏响应——入库对照的唯一键）；name/status/
 * domain 可空缺失（OpenAI 纯形状只有 id，方舟多 name/status/domain——status
 * 无＝活跃，Shutdown/Retiring＝下架/退场中；对照入库建议由调用方结合 status
 * 判断，端口只如实转述）。</p>
 */
public sealed interface ModelCatalog {

    /** 拉取成功：{@link #models} 为远端模型条目列表（空列表＝远端真零模型）。 */
    record Fetched(List<RemoteModel> models) implements ModelCatalog {
    }

    /** 拉取失败：{@link #reason} 如实转述（非 200、坏响应、连接异常等）。 */
    record Failed(String reason) implements ModelCatalog {
    }

    /** 单条远端模型：模型 ID（入库即此值）＋基础辨识字段（可全空——供数方差异）。 */
    record RemoteModel(String modelId, String name, String status, String domain) {
    }
}
