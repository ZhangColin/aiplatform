package com.semgravio.aiplatform.base.agentscope;

/**
 * 边缘调用点模型供数 SPI（#366，spec #364-T2）：底座侧消费的边缘轻调用——
 * <b>上下文压缩</b>（工厂装配压缩模型）与<b>技能预检</b>（skills 域判定调用）——
 * 的模型选用反转口。正本在 business 侧智能体运营配置（边缘点档位，ADR-0031/
 * 0021 同表同机制：库值优先缺省回落），解析链（档位键→模型串＋通道注入）在
 * {@code ModelTierAppService}——经端口接线（单体进程内直调，先例
 * {@link AgentToolkitSupplier}；knowledge 在业务层、消费在底座，base 不依赖
 * business）。业务侧命令构建处（取名/分类）同链直调不走本口。
 *
 * <p>业务实现动态查库、不缓存（{@code AgentConfigAppService} 口径）——后台改档
 * 下一轮装配/调用自然生效。</p>
 */
public interface EdgeModelSupplier {

    /**
     * 上下文压缩模型（装配经 {@code AgentscopeHarnessAgentFactory}：进压缩配置
     * 与智能体实例缓存键指纹——改档即新实例）。
     */
    EdgeModel compactionModel();

    /**
     * 技能预检模型（消费在 {@code BackofficeSkillPrecheckAppService}：随一次性
     * 判定命令携模型串与通道）。
     */
    EdgeModel precheckModel();

    /**
     * 解析产物（档位解析的底座消费形）：模型串（前缀:实际模型名——前缀即协议
     * 路由）＋通道注入件（baseUrl/apiKey/thinking，进创建上下文；可空＝无通道
     * 语境，SDK 回落内置 provider 缺省——测试形）。
     */
    record EdgeModel(String modelString, ModelChannel channel) {
    }
}
