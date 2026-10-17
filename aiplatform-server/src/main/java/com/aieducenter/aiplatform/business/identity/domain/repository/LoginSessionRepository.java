package com.aieducenter.aiplatform.business.identity.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.LoginSession;

/**
 * 登录会话仓储（{@code idn_sessions}）。会话 id（cookie 值）为唯一查询面——
 * 自然主键直查；过期谓词内联查询（过期行等同不存在，行由扫描清）。
 */
public interface LoginSessionRepository extends BaseRepository<LoginSession, String> {

    /**
     * 有效会话直查：行存在且 {@code expiresAt > now}（过期行不认——谓词进查询面，
     * 正确性不依赖清扫节奏）。id 即会话 id（cookie 值，自然主键）。
     */
    Optional<LoginSession> findByIdAndExpiresAtAfter(String id, LocalDateTime now);

    // 删行（登出）用继承的 deleteById：自持事务、缺行 no-op——登出幂等的底。

    /**
     * 扫描清过期行（#329 定时清扫入口）：{@code expiresAt <= now} 即删，返回清除
     * 行数。纯 DELETE 幂等——多实例并发清扫安全（后到者 0 行）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LoginSession s where s.expiresAt <= :now")
    int deleteExpiredBefore(@Param("now") LocalDateTime now);
}
