package com.semgravio.aiplatform.base.agentscope;

/**
 * 边缘调用点模型供数 SPI（#366，spec #364-T2）：底座侧消费的边缘轻调用——
 * <b>上下文压缩</b>（工厂装配压缩模型）——的模型选用反转口。正本在 business 侧
 * 智能体运营配置（边缘点配置，ADR-0031/0021 同表同机制：库值优先缺省回落），
 * 解析链（配置键→模型串＋通道注入）在 {@code ModelConfigAppService}——经端口接线
 * （单体进程内直调，先例 {@link AgentToolkitSupplier}；knowledge 在业务层、消费在
 * 底座，base 不依赖 business）。业务侧命令构建处（取名/分类）同链直调不走本口。
 * 技能预检模型供数随 #638 技能清场退场（预检调用点整体拆除）。
 *
 * <p>业务实现动态查库、不缓存（{@code AgentConfigAppService} 口径）——后台改配置
 * 下一轮装配/调用自然生效。</p>
 */
public interface EdgeModelSupplier {

    /**
     * 上下文压缩模型（装配经 {@code AgentscopeHarnessAgentFactory}：进压缩配置
     * 与智能体实例缓存键指纹——改配置即新实例）。
     */
    EdgeModel compactionModel();

    /**
     * 解析产物（配置解析的底座消费形，#557 起整队列）：候选队列
     * （{@link ModelCandidateQueue}——首候选＝模型串＋通道注入，多候选＝failover
     * 面，装配见 {@code AgentscopeHarnessAgentFactory#failoverLayer}）。兼容构造
     * （模型串＋通道件直构单候选队列）＝测试形/单候选路径。
     */
    record EdgeModel(ModelCandidateQueue queue) {

        /** 兼容构造：模型串＋通道件直构单候选队列（无配置语境——切换不留痕）。 */
        public EdgeModel(String modelString, ModelChannel channel) {
            this(ModelCandidateQueue.single(modelString, channel));
        }

        /** 首选候选模型串（前缀:实际模型名——前缀即协议路由）。 */
        public String modelString() {
            return queue.first().modelString();
        }

        /** 首选候选通道注入件（可空＝无通道语境，SDK 回落内置 provider 缺省）。 */
        public ModelChannel channel() {
            return queue.first().channel();
        }
    }
}
