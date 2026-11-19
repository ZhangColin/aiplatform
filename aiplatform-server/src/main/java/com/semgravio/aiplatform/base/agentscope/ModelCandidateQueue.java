package com.semgravio.aiplatform.base.agentscope;

import java.util.List;

/**
 * 有序候选队列（ADR-0053 ③，#557）：模型配置（旧称档位）的运行期消费形——档位键
 * ＋启用候选有序列表（priority 升序，主备＝队列前两名）。业务侧解析单点
 * （{@code ModelTierAppService#resolveQueue}）产出，随命令（{@link AgentCommand}
 * {@code modelQueue}）/装配（{@link EdgeModelSupplier.EdgeModel}）透传进底座；
 * 链式 failover 收口在 {@code FailoverModel}（持本队列、懒装配候选[i+1]）。
 *
 * <p><b>软着陆口径</b>：队列恒非空（单候选队列＝无切换面，装配与既有单候选路径
 * 行为一致）；首候选＝modelString/modelChannel 双字段的事实源（命令双字段由
 * {@link #first()} 派生，不改既有面）。{@code tierKey} 可空＝非档位语境的测试形
 * （留痕腿需要档位键，空则切换不留痕——见 {@code FailoverModel}）。</p>
 *
 * <p>{@link #fingerprint()} 是<b>整队列生效配置指纹</b>：进智能体实例缓存键
 * （{@code AgentscopeHarnessAgentFactory#obtain}）——任一候选换 key/换地址/换
 * 模型/换 vendor＝指纹变＝新实例（「改配置即生效、进行中 run 不定格」对整个
 * 队列成立，apiKey 只以 {@link ModelChannel#fingerprint()} 的摘要形入指纹）。</p>
 *
 * @param tierKey    档位语义键（可空＝测试形；切换留痕的归档腿）
 * @param candidates 启用候选有序列表（非空；priority 升序）
 */
public record ModelCandidateQueue(String tierKey, List<ModelCandidate> candidates) {

    public ModelCandidateQueue {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "候选队列不可为空（档位解析产物至少含首选候选）");
        }
        candidates = List.copyOf(candidates);
    }

    /** 首选候选（modelString/modelChannel 双字段的事实源）。 */
    public ModelCandidate first() {
        return candidates.get(0);
    }

    /** 按队列序取候选（越界＝编程错误——内部迭代面）。 */
    public ModelCandidate candidate(int index) {
        return candidates.get(index);
    }

    /** 首选候选模型串（命令双字段派生单点——调用面以此填 modelString）。 */
    public String modelString() {
        return first().modelString();
    }

    /** 首选候选通道注入件（同 {@link #modelString()}——调用面以此填 modelChannel）。 */
    public ModelChannel channel() {
        return first().channel();
    }

    /** 队列深度。 */
    public int size() {
        return candidates.size();
    }

    /** 整队列生效配置指纹（缓存键腿）：档位键＋逐候选模型串＋通道指纹。 */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder("tier=").append(tierKey);
        for (int i = 0; i < candidates.size(); i++) {
            ModelCandidate candidate = candidates.get(i);
            sb.append("|c").append(i).append("=").append(candidate.modelString())
                    .append("@")
                    .append(candidate.channel() == null ? "" : candidate.channel().fingerprint());
        }
        return sb.toString();
    }

    /**
     * 单候选队列（测试形/无档位语境）：从模型串＋通道件直构——模型串经
     * {@link ModelRef} 形状校验拆前缀与实际模型名，档位键空（切换不留痕）。
     */
    public static ModelCandidateQueue single(String modelString, ModelChannel channel) {
        ModelRef ref = ModelRef.parse(modelString);
        return new ModelCandidateQueue(null, List.of(new ModelCandidate(ref.provider(),
                ref.modelId(), channel)));
    }
}
