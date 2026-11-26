package com.semgravio.aiplatform.base.eventhub.infrastructure.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 线格式行（内核内部用语）：事件行（id + name + data）。
 *
 * <p>内核以本值对象组装与测试发送内容，{@link #toBuilder()} 才转 Spring 的
 * {@link SseEmitter.SseEventBuilder}——Spring builder 不可内省，测试缝留在本类型。
 * 历史上的心跳注释行（comment）形态已随 #652 改为具名事件拆除：注释行对浏览器
 * 不可见，客户端看门狗读不到心跳是僵死连接假死的根因。</p>
 *
 * @since 0.1.0
 */
public record SseServerEvent(String id, String name, Object data) {

    public static SseServerEvent of(String id, String name, Object data) {
        return new SseServerEvent(id, name, data);
    }

    /**
     * 转 Spring SSE 事件 builder（发送缝的最后一寸）。
     */
    public SseEmitter.SseEventBuilder toBuilder() {
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (id != null) {
            builder.id(id);
        }
        if (name != null) {
            builder.name(name);
        }
        if (data != null) {
            builder.data(data);
        }
        return builder;
    }
}
