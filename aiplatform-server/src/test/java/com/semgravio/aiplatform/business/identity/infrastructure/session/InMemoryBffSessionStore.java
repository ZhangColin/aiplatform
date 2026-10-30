package com.semgravio.aiplatform.business.identity.infrastructure.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话替身（旧内存 store 语义降级为测试 fake）：真 store #329 起落 PG、依赖
 * JPA 仓储——排除数据面的窄上下文（SSE 回归，不依赖本机 PG）装配不了它，用本
 * 替身承载 put/get/remove。无过期语义（窄上下文不测过期面）。
 */
public class InMemoryBffSessionStore extends BffSessionStore {

    private final Map<String, BffSession> sessions = new ConcurrentHashMap<>();

    public InMemoryBffSessionStore() {
        super(null, null, null); // 窄上下文无 JPA/配置依赖；全部行为走覆写
    }

    @Override
    public void put(String sessionId, BffSession session) {
        sessions.put(sessionId, session);
    }

    @Override
    public Optional<BffSession> get(String sessionId) {
        return sessionId == null ? Optional.empty()
                : Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public int purgeExpired() {
        return 0;
    }
}
