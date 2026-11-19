package com.semgravio.aiplatform.base.agentscope;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * 链式 failover 模型包装（#557，ADR-0053 ③）：持候选队列（{@link ModelCandidateQueue}）、
 * 懒装配候选[i+1]——收口模型包装层一处，主/子智能体、压缩、边缘点全部 resolve 面
 * 免费获得同一切换语义（不侵入 run 编排层）。与 #454 升档正交：升档＝能力轴，
 * 候选切换＝可用性轴。
 *
 * <p><b>链式升级语义</b>（ADR-0053 ③ 定稿）：
 * <ul>
 *   <li>候选内 {@link RetryingModel} 按 #321 分类重试（超时/IO/5xx/429）——候选
 *       装配即 {@code streamLayer} 包装（{@link MeteredModel} 外圈＋重试内圈，
 *       每候选独立计量归属：provider 前缀随候选走）；</li>
 *   <li>401/403/402（认证失效/余额）跳过候选内重试<b>立即切</b>——非可重试 4xx
 *       经内圈原样上抛（重试面不吞真错误，#321 口径），本层按状态码分类即切；</li>
 *   <li>候选内耗尽（可重试错误重试烧完）亦切——内圈 {@code onRetryExhaustedThrow}
 *       如实上抛末次原始错误（#321），本层对可重试类错误切下一候选；</li>
 *   <li><b>首块未流出才切</b>：本流（跨全部候选）向下游发过任何块即不切——已发块
 *       后切换重发＝输出重复/倒带（ADR-0053「已发块后整段重发」拒绝项），直接
 *       中断上抛保「可重发」（与 #321 已发块护栏同界，外圈版）；</li>
 *   <li>全队列尽：上抛<b>末候选末次真错误</b>（不被重试机制的皮裹走——#555 后
 *       真错误裸露，外层尝试环/错误现场拿真因，用户面文案可见 401/Authentication
 *       类签名）。</li>
 * </ul></p>
 *
 * <p><b>分类接线＝框架既有接口单点</b>（同 #321 doctrine）：可重试＝
 * {@link ExecutionConfig#RETRYABLE_ERRORS}（429/5xx/超时/IO），立即切＝
 * {@link ModelHttpException#getStatusCode()} 401/402/403；其余 4xx（请求形问题
 * ——400/404 等，换通道不解决）不切、原样上抛。</p>
 *
 * <p><b>懒装配</b>：候选 i+1 的模型解析（{@code ModelRegistry.resolve}＋流层包装）
 * 推迟到首次切换才发生——备通道配置坏不阻塞主通道正常运行（装配即解析会反客为主）；
 * 装配结果记忆化（同候选重复切换不重复解析）。首候选由工厂急切装配（构建即校验，
 * 与既有单候选装配行为一致）。Model 元信息面（modelName/结构化输出能力/上下文窗）
 * 委托首候选——候选队列按能力档配置（备选须匹配能力档），能力差异不在切换面。</p>
 *
 * <p><b>切换留痕</b>：每次切候选经 {@link ModelSwitchTraceSink} 追加 append-only
 * 留痕（何时/从哪到哪/触发错误/run 上下文——备账号被烧运营可见）。run 上下文
 * （runId/sessionId）从订阅链 {@link MeteringScope} 读（#324 订阅态传递机制，
 * 装配线程≠信号线程；无计量语境的调用面＝null 留痕，schema 允许）。留痕失败只
 * 告警不中断生成流（观察面不烧穿可用性面）。</p>
 */
final class FailoverModel implements Model {

    private static final Logger logger = LoggerFactory.getLogger(FailoverModel.class);

    private final ModelCandidateQueue queue;
    private final Function<ModelCandidate, Model> assembler;
    private final ModelSwitchTraceSink traceSink;
    /** 候选装配记忆（懒解析一次；候选数个位数，同步块足够）。 */
    private final Model[] assemblies;

    FailoverModel(ModelCandidateQueue queue, Function<ModelCandidate, Model> assembler,
            ModelSwitchTraceSink traceSink) {
        this.queue = queue;
        this.assembler = assembler;
        this.traceSink = traceSink;
        this.assemblies = new Model[queue.size()];
        // 首候选急切装配：构建即校验（与既有单候选装配行为一致——主候选坏响亮报错）
        this.assemblies[0] = assembler.apply(queue.first());
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options) {
        // run 上下文订阅态读取（#324 机制——压缩/委派链的模型直调在信号线程，
        // MeteringScope 随订阅链传播；无计量语境＝null 留痕腿）
        return Flux.deferContextual(contextView -> {
            MeteringScope scope = MeteringScope.fromContext(contextView);
            String runId = scope == null ? null : scope.runId();
            String sessionId = scope == null ? null : scope.sessionId();
            // 已发块护栏（外圈版）：本流跨全部候选向下游发过任何块即不切（首块未
            // 流出才切）——错误信号晚于最后一次发射串行到达，读取无竞态（同 #321）
            AtomicBoolean emitted = new AtomicBoolean(false);
            return attempt(0, messages, tools, options, emitted, runId, sessionId);
        });
    }

    /** 候选 i 的一次尝试：失败按分类切下一候选（递归订阅链——错误不重裹、末次真错误直通）。 */
    private Flux<ChatResponse> attempt(int index, List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options, AtomicBoolean emitted, String runId, String sessionId) {
        Model candidate = assemblyOf(index);
        return candidate.stream(messages, tools, options)
                .doOnNext(response -> emitted.set(true))
                .onErrorResume(error -> {
                    if (emitted.get() || index + 1 >= queue.size() || !switchable(error)) {
                        // 已发块（保「可重发」中断）/ 队列尽（末次真错误上抛）/
                        // 业务 4xx（换通道不解决）——一律如实上抛
                        return Flux.error(error);
                    }
                    ModelCandidate from = queue.candidate(index);
                    ModelCandidate to = queue.candidate(index + 1);
                    logger.warn("[model-failover] 档位 {} 候选 {} → {} 切换（触发: {}）",
                            queue.tierKey(), from.modelString(), to.modelString(),
                            error.toString());
                    recordSwitch(from, to, error, runId, sessionId);
                    return attempt(index + 1, messages, tools, options, emitted, runId,
                            sessionId);
                });
    }

    /**
     * 候选装配（懒解析＋记忆化；首候选已在构建时装配）。装配失败＝配置态错误
     * （前缀无路由/key 缺失等）而非通道故障——如实上抛（原错误语义：不切下一
     * 候选、不写切换留痕——留痕记「切换发生」，配置坏是解析层报错面），WARN
     * 显式化候选与原因供运营定位（备通道配置坏在切换时刻的表现不留静默）。
     */
    private synchronized Model assemblyOf(int index) {
        if (assemblies[index] == null) {
            ModelCandidate candidate = queue.candidate(index);
            try {
                assemblies[index] = assembler.apply(candidate);
            }
            catch (RuntimeException e) {
                logger.warn("[model-failover] 档位 {} 候选 {} 装配失败（配置态错误，"
                        + "如实上抛不切下一候选）: {}", queue.tierKey(), candidate.modelString(),
                        e.toString());
                throw e;
            }
        }
        return assemblies[index];
    }

    /**
     * 切换分类（ADR-0053 ③）：①认证失效/余额（401/403/402——账号级故障）立即切；
     * ②可重试类（超时/IO/5xx/429，#321 分类单点）——内圈重试耗尽后上抛，切下一
     * 候选（重试关闭配置＝零重试直切，同「耗尽」语义）；③其余（请求形问题）不切。
     */
    static boolean switchable(Throwable error) {
        if (error instanceof ModelHttpException http && http.getStatusCode() != null) {
            int status = http.getStatusCode();
            if (status == 401 || status == 402 || status == 403) {
                return true;
            }
        }
        return ExecutionConfig.RETRYABLE_ERRORS.test(error);
    }

    /**
     * 切换留痕（观察面不烧穿可用性面——记录失败在本层兜底告警，适配器如实上抛
     * 即可，无需重复防御）。
     */
    private void recordSwitch(ModelCandidate from, ModelCandidate to, Throwable error,
            String runId, String sessionId) {
        if (traceSink == null || queue.tierKey() == null) {
            // 无端口（测试形）/无档位语境——切换照走，留痕腿缺席
            return;
        }
        try {
            traceSink.record(new ModelSwitchTraceSink.Trace(queue.tierKey(),
                    from.providerKey(), from.modelName(), to.providerKey(), to.modelName(),
                    triggerErrorSummary(error), runId, sessionId, Instant.now()));
        }
        catch (RuntimeException e) {
            logger.warn("切换留痕失败（忽略，生成流不受影响）: {}", e.toString());
        }
    }

    /** 触发错误摘要：异常类签名＋文案（落库截断归适配层——列宽是业务侧事实）。 */
    static String triggerErrorSummary(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    @Override
    public String getModelName() {
        return assemblies[0].getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return assemblies[0].supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return assemblies[0].supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return assemblies[0].getContextWindowSize();
    }
}
