package com.semgravio.aiplatform.base.agentscope;

/**
 * 候选队列元素（ADR-0053 ③，#557）：一个候选＝「通道行 × 实际模型名」的解析产物
 * ——通道注入件（{@link ModelChannel}：baseUrl/apiKey/thinking，进创建上下文）
 * ＋计量/留痕腿（providerKey＝通道键、modelName＝实际模型名）。候选队列
 * （{@link ModelCandidateQueue}）由业务侧档位解析（{@code ModelTierAppService}
 * {@code resolveQueue}）产出随命令/装配透传；运行期链式切换收口在
 * {@code FailoverModel}（模型包装层——主/子智能体、压缩、边缘点全部 resolve 面免费
 * 获得同一切换语义）。
 *
 * <p>{@link #channel()} 可空＝无通道语境（测试形，语义同 {@link AgentCommand#modelChannel()}）
 * ——SDK 侧回落内置 provider 缺省地址/环境变量。</p>
 *
 * @param providerKey 通道键（＝模型串前缀＝计量 provider 归属；一行＝endpoint×账号 key）
 * @param modelName   实际模型名（计量 model 归属/单价行匹配腿）
 * @param channel     通道注入件（可空＝测试形）
 */
public record ModelCandidate(String providerKey, String modelName, ModelChannel channel) {

    /** 对话轨道模型串（前缀:实际模型名——前缀即协议路由，同 {@link ModelRef} 形）。 */
    public String modelString() {
        return providerKey + ":" + modelName;
    }
}
