package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.skills.domain.repository.SkillStore;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintConfig;
import com.semgravio.aiplatform.business.project.domain.decisionhint.HintPolicy;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;

/**
 * 平台中间件装配缝（#453-2 TTL 降频腿的装配断言）：执行体座装配的两条每迭代
 * 配置读腿各经一枚 {@link TtlCache}——同窗口内多次迭代只查一次库，越过 TTL
 * 窗口后下一次迭代刷新（运营改档 ≤TTL 生效的语义载体）；非执行体座不挂
 * 决策/verify 中间件（装配面不变）。
 */
class ProfileMiddlewareSupplierTest {

    /** 可变时钟（推进墙钟越过 TTL 窗口）。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        private void advance(Duration delta) {
            now.updateAndGet(instant -> instant.plus(delta));
        }
    }

    private static ReasoningInput plainInput() {
        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("做订单页").build()).build();
        return new ReasoningInput(List.of(user), List.of(), null);
    }

    /** 透传 next（断言面只看配置读腿调用次数）。 */
    private static class Passthrough
            implements Function<ReasoningInput, Flux<io.agentscope.core.event.AgentEvent>> {
        @Override
        public Flux<io.agentscope.core.event.AgentEvent> apply(ReasoningInput input) {
            return Flux.empty();
        }
    }

    @Test
    void given_executor_iterations_within_ttl_when_on_reasoning_then_config_read_once() {
        AgentConfigAppService configs = mock(AgentConfigAppService.class);
        when(configs.verifySelfDecisionEnabledOf(any())).thenReturn(true);
        when(configs.decisionHintConfigOf(any()))
                .thenReturn(DecisionHintConfig.enabled(HintPolicy.defaults()));
        MutableClock clock = new MutableClock(Instant.EPOCH);
        ProfileMiddlewareSupplier supplier = new ProfileMiddlewareSupplier(
                mock(SkillStore.class), configs, clock);
        List<MiddlewareBase> middlewares = supplier.middlewaresFor(
                AgentProfile.EXECUTOR.key(), new AgentWorkspace.Local(Path.of("ws")));
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        ReasoningInput input = plainInput();

        iterate(middlewares, ctx, input);
        iterate(middlewares, ctx, input);
        iterate(middlewares, ctx, input);

        // TTL 窗口内三次迭代：两条读腿各只查一次库（#453-2 降频断言）
        verify(configs, times(1)).verifySelfDecisionEnabledOf(AgentProfile.EXECUTOR);
        verify(configs, times(1)).decisionHintConfigOf(AgentProfile.EXECUTOR);

        clock.advance(Duration.ofSeconds(6)); // 越过 5s 窗口
        iterate(middlewares, ctx, input);

        verify(configs, times(2)).verifySelfDecisionEnabledOf(AgentProfile.EXECUTOR);
        verify(configs, times(2)).decisionHintConfigOf(AgentProfile.EXECUTOR);
    }

    @Test
    void given_non_executor_key_when_assemble_then_no_iteration_middlewares() {
        ProfileMiddlewareSupplier supplier = new ProfileMiddlewareSupplier(
                mock(SkillStore.class), mock(AgentConfigAppService.class), Clock.systemUTC());

        List<MiddlewareBase> middlewares = supplier.middlewaresFor(
                AgentProfile.MAIN.key(), new AgentWorkspace.Local(Path.of("ws")));

        // 主智能体座：只有技能加载计数——决策/verify 中间件不挂（装配面不变）
        assertThat(middlewares).hasSize(1);
        assertThat(middlewares.get(0)).isInstanceOf(SkillLoadCountMiddleware.class);
    }

    /** 逐中间件过一次 onReasoning（决策/verify 两中间件都在链上才触发读腿）。 */
    private static void iterate(List<MiddlewareBase> middlewares, RuntimeContext ctx,
            ReasoningInput input) {
        for (MiddlewareBase middleware : middlewares) {
            middleware.onReasoning(null, ctx, input, new Passthrough());
        }
    }
}
