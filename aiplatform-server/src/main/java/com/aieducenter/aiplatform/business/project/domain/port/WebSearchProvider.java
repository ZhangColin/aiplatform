package com.aieducenter.aiplatform.business.project.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 搜索供数口（#215 调研闭环的南向缝）：按查询返回全网搜索结果——主智能体在
 * 「参考 X 类平台」这类输入下自主判断信息缺口后据此寻找来源（调研连「去哪找」
 * 都自主），再配合 {@link ExternalContentFetcher} 抓取阅读。与抓取的分界在来源谁定：
 * 抓取是来源既定（用户贴 URL），调研是来源也自主（先搜再抓）。
 *
 * <p>面向供数器接口编程——可替换是结构内聚的：换供数方（换配置 + 加适配器）主链路
 * 零改动，本端口契约不渗任何供数方 specifics（供应商类型/key/baseUrl 是实例化配置，
 * 不进端口面）。好测试只测外部行为：查询进 → 结果/失败理由出。</p>
 */
@Port(PortType.CLIENT)
public interface WebSearchProvider {

    /**
     * 搜索全网，返回结果列表或失败理由。
     *
     * @param query 查询词（自然语言，非空）
     * @return 结果：成功带标题/URL/摘要列表；失败带如实理由（非 200、坏响应、
     *         连接异常、缺 key 等）——不抛错不炸，理由由调用方（搜索工具）如实
     *         回给智能体
     */
    SearchResult search(String query);
}
