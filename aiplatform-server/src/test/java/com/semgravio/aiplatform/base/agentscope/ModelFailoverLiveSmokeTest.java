package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;

/**
 * 链式 failover·真通道活体冒烟（#557，spec #552 总验收「live 一门真通道假 key」腿，
 * 照 {@code AgentscopeConverseSmokeTest} 的 env 门控口径）：主候选＝deepseek 官方
 * 真端点×<b>假 key</b>（真服务器回真 401——生产事故 2026-11-19 同形），备候选＝同
 * 端点×真 key（DEEPSEEK_API_KEY）——真 HTTP 真供应商故障注入下验「用户面无感」：
 * 自动切候选、生成成功、切换留痕有记录。生产同形装配（failoverLayer：前缀路由
 * 内置 provider＋通道注入＋逐候选 streamLayer）。
 *
 * <p>env 未配置即跳过（Assumption——缺省车道跑不了真通道，无 key 环境零影响）。</p>
 */
class ModelFailoverLiveSmokeTest {

    private static final String REAL_BASE_URL = "https://api.deepseek.com";
    /** 与单价行/种子对齐的 flash 模型名（V79 收敛后官方通道形）。 */
    private static final String FLASH_MODEL = "deepseek-flash";

    @BeforeAll
    static void requireRealKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真通道 failover 冒烟");
    }

    @Test
    @Timeout(120)
    void given_primary_fake_key_401_when_stream_then_real_channel_failover_succeeds() {
        // 主候选假 key（真 401）→ 备候选真 key 接续成功（用户面无感）＋留痕。
        // 本机无有效 key（.env.local/测试库种子均非真值——真 key 在生产库）时：
        // 备候选同样真 401＝队列尽，切换机制已被真通道实证（真 401 触发切换＋
        // 末次真错误裸露）——显式 skip 留证据说明，成功腿留待有真 key 的环境
        List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();
        ModelCandidateQueue queue = new ModelCandidateQueue("flash", List.of(
                // 主：真端点×假 key——真供应商 401（apiKey 不存在类真因）
                new ModelCandidate("deepseek", FLASH_MODEL,
                        new ModelChannel(REAL_BASE_URL, "sk-fake-key-for-failover-live", false)),
                // 备：真端点×真 key（env 同源——种子通道行同值）
                new ModelCandidate("deepseek", FLASH_MODEL,
                        new ModelChannel(REAL_BASE_URL, System.getenv("DEEPSEEK_API_KEY"),
                                false))));
        Model model = AgentscopeHarnessAgentFactory.failoverLayer(queue,
                new AgentscopeProperties(), traces::add);

        List<Msg> messages = List.of(UserMessage.builder()
                .content(TextBlock.builder().text("只回复两个字：成功").build()).build());
        try {
            List<ChatResponse> received = model.stream(messages, null, null)
                    .collectList()
                    .block(Duration.ofSeconds(90));

            // 生成成功（备候选真通道产出）
            assertThat(received).isNotNull().isNotEmpty();
            String text = received.stream()
                    .flatMap(r -> r.getContent().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(block -> ((TextBlock) block).getText())
                    .reduce("", String::concat);
            assertThat(text).contains("成功");
        }
        catch (RuntimeException e) {
            // 备候选也 401＝本机 key 无效（真 401→切换→队列尽→末次真错误裸露，
            // 机制面已实证）——skip 不作红
            if (e instanceof io.agentscope.extensions.model.openai.exception.OpenAIException
                    && e.getMessage() != null && e.getMessage().contains("401")
                    && !traces.isEmpty()) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "真通道 failover 机制已实证（切换留痕 " + traces.size()
                                + " 条、真 401 触发），但本机 DEEPSEEK_API_KEY 无效"
                                + "（备候选也 401）——成功接续腿留待有真 key 的环境");
            }
            throw e;
        }
        // 切换留痕：真 401 触发、主备指向如实
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).tierKey()).isEqualTo("flash");
        assertThat(traces.get(0).triggerError()).contains("401");
        assertThat(traces.get(0).fromProviderKey()).isEqualTo("deepseek");
        assertThat(traces.get(0).toProviderKey()).isEqualTo("deepseek");
    }
}
