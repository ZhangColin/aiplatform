package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.AgentMiddlewareSupplier;
import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.skills.domain.repository.SkillStore;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintConfig;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 平台中间件装配（#261 使用计数首件）：全形态（主智能体/run 执行体/子智能体
 * ——三槽位技能同出库）挂 {@link SkillLoadCountMiddleware 技能加载计数}——
 * 观测面对全库一致、不分槽位不分配置键；无状态中间件单实例长生命周期（依赖
 * 单例 {@link SkillStore}，多 agent 共享安全）。
 *
 * <p>决策时微指令中间件（#437）只挂 <b>run 执行体</b>装配面（{@link
 * AgentProfile#EXECUTOR}——主智能体对话轮与设计执行体不消费；子智能体键
 * 非 executor 自然不带）：策略经 {@link AgentConfigAppService#decisionHintConfigOf}
 * 每迭代动态查库——改档即生效、不发版、进行中 run 不定格，<b>装配不进 agent
 * 实例缓存键</b>（与 #366 边缘点同逻辑）。每构建新实例（无状态，随构建发放）。</p>
 *
 * <p>verify 自决开关中间件（#442，#441-1）同只挂 <b>run 执行体</b>装配面：
 * 开关（executor 行 {@code verify_self_decision_enabled}，缺省 true＝自决开）
 * 经 {@link AgentConfigAppService#verifySelfDecisionEnabledOf} 每迭代动态查库——
 * 关闭态注入一条提示层收窄指令（每会话一次），生成过程中不自发委派 verify；
 * 缺省开＝零注入零扰动；fail-open 同 #437 口径。装配不进 agent 实例缓存键。</p>
 *
 * <p><b>每迭代配置读的短 TTL 进程内缓存（#453-2，两腿同挂）</b>：两个读腿
 * 各经一枚 {@link TtlCache}（TTL {@value #CONFIG_READ_TTL_SECONDS}s）——
 * ReAct 循环每迭代的开关/策略读从「每迭代一次主键 SELECT」降为「TTL 窗口内
 * 零查库」。语义口径：进行中 run 的档位/开关秒级不变，TTL 生效延迟对偶
 * 「进行中 run 不定格」（运营面改档下一个 TTL 窗口即生效，不发版）；读失败
 * 不毒化缓存（fail-open 口径不变）。</p>
 *
 * <p>本类归 business 装配缝（组合 base.skills 存取口与 agentscope 框架类型是
 * 业务侧装配的事，base 两 BC 不互相挂依赖）。</p>
 */
@Component
public class ProfileMiddlewareSupplier implements AgentMiddlewareSupplier {

    /** 每迭代配置读腿的 TTL 窗口秒数（#453-2：改档生效延迟＝窗口长度——对偶「进行中 run 不定格」）。 */
    static final int CONFIG_READ_TTL_SECONDS = 5;

    private final SkillLoadCountMiddleware loadCountMiddleware;
    private final AgentConfigAppService agentConfigs;
    private final Clock clock;

    @Autowired
    public ProfileMiddlewareSupplier(SkillStore skillStore, AgentConfigAppService agentConfigs) {
        this(skillStore, agentConfigs, Clock.systemUTC());
    }

    ProfileMiddlewareSupplier(SkillStore skillStore, AgentConfigAppService agentConfigs,
            Clock clock) {
        this.loadCountMiddleware = new SkillLoadCountMiddleware(skillStore);
        this.agentConfigs = agentConfigs;
        this.clock = clock;
    }

    @Override
    public List<MiddlewareBase> middlewaresFor(String agentKey, AgentWorkspace workspace) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(loadCountMiddleware);
        // #437：决策时注入只挂 run 执行体（agentKey 判别——主智能体/设计执行体/
        // 子智能体键不消费；工作区形态不判别：执行体 ProjectDev 与 fork 面同挂）
        if (AgentProfile.EXECUTOR.key().equals(agentKey)) {
            // 键透传（门控已保 executor——byKey 回落仅防御未知键，语义不偷换）；
            // 读腿经短 TTL 缓存（#453-2：每迭代查库降为窗口内零查库，改档 ≤TTL 生效）
            TtlCache<DecisionHintConfig> hintConfigCache = new TtlCache<>(
                    Duration.ofSeconds(CONFIG_READ_TTL_SECONDS), clock);
            middlewares.add(new DecisionHintMiddleware(agentKey,
                    key -> hintConfigCache.get(() -> agentConfigs.decisionHintConfigOf(
                            AgentProfile.byKey(key).orElse(AgentProfile.EXECUTOR)))));
            // #442：verify 自决开关中间件同挂执行体面（读腿同经短 TTL 缓存，#453-2
            // 同口径：关闭态注入提示层收窄指令；缺省开＝零注入零扰动，fail-open 同 #437）
            TtlCache<Boolean> verifySwitchCache = new TtlCache<>(
                    Duration.ofSeconds(CONFIG_READ_TTL_SECONDS), clock);
            middlewares.add(new VerifySelfDecisionMiddleware(
                    () -> verifySwitchCache.get(
                            () -> agentConfigs.verifySelfDecisionEnabledOf(AgentProfile.EXECUTOR))));
        }
        return middlewares;
    }
}
