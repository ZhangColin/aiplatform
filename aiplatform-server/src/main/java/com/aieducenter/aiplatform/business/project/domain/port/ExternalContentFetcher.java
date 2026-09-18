package com.aieducenter.aiplatform.business.project.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 外部资料取数口（#213 抓取闭环的南向缝）：按用户给定地址读取外部资料——主智能体
 * 在对话区贴 URL 后据此读入内容（网页 / 文档站 / GitHub raw 文件），并可在来源内
 * 自主探读（逐文件读）。与知识库对偶：外部资料按项目即时获取、用后不沉淀
 * （根级 CONTEXT.md「外部资料」）。
 *
 * <p>四条安全底线在本端口契约内兑现，换抓取实现不破底线语义：仅 GET；拒绝环回
 * /内网/云元数据目标；响应大小上限截断；抓回内容带「不可信外部内容」标注。动态
 * 页只取得初始 HTML，附如实降级标志（Kimi 同款限制与话术口径）。</p>
 */
@Port(PortType.CLIENT)
public interface ExternalContentFetcher {

    /**
     * 抓取一个外部地址的内容。
     *
     * @param url 用户给定的外部地址（http/https 完整 URL）
     * @return 抓取结果：成功带已转文本、已截断/降级标志、已带不可信标注的内容；
     *         被拒/失败带如实理由（环回/内网/云元数据、非 200、连接异常、非法
     *         地址等）
     */
    FetchResult fetch(String url);
}
