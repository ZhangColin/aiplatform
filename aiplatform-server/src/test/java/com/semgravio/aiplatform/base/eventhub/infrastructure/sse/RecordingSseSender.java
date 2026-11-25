package com.semgravio.aiplatform.base.eventhub.infrastructure.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 测试夹具：记录每个 emitter 收到的事件与发送尝试数；可对指定 emitter 恒抛异常模拟断连。
 * 内核测试与通道应用服务测试共用（白盒验证通道语义的接线）。
 */
public final class RecordingSseSender implements SseSender {

    /** 测试线程 add、心跳/广播线程 contains 并发触碰：COW（评审补漏——并发安全化须整类一致）。 */
    private final List<SseEmitter> brokenEmitters = new CopyOnWriteArrayList<>();
    /** 心跳线程与广播线程并发写入、测试线程并发流读：值列表并发安全（COW，读多写少）。 */
    private final Map<SseEmitter, List<SseServerEvent>> received = new ConcurrentHashMap<>();
    private final Map<SseEmitter, AtomicInteger> attemptsPerEmitter = new ConcurrentHashMap<>();
    private final AtomicInteger attempts = new AtomicInteger();

    public void breakEmitter(SseEmitter emitter) {
        brokenEmitters.add(emitter);
    }

    public int attempts() {
        return attempts.get();
    }

    public int attemptsFor(SseEmitter emitter) {
        return attemptsPerEmitter.getOrDefault(emitter, new AtomicInteger()).get();
    }

    public List<SseServerEvent> framesOf(SseEmitter emitter) {
        return received.getOrDefault(emitter, List.of());
    }

    /** 只取事件行（排除心跳：具名 ping 事件无 id，#652 起与事件行同为具名形态）。 */
    public List<SseServerEvent> eventFramesOf(SseEmitter emitter) {
        return framesOf(emitter).stream().filter(frame -> frame.id() != null).toList();
    }

    @Override
    public void send(SseEmitter emitter, SseServerEvent event) throws IOException {
        attempts.incrementAndGet();
        attemptsPerEmitter.computeIfAbsent(emitter, key -> new AtomicInteger()).incrementAndGet();
        if (brokenEmitters.contains(emitter)) {
            throw new IOException("broken pipe");
        }
        received.computeIfAbsent(emitter, key -> new CopyOnWriteArrayList<>()).add(event);
    }
}
