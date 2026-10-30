package com.semgravio.aiplatform.business.identity.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.identity.domain.aggregate.Guest;

/**
 * 访客仓储（{@code idn_guests}）。按凭证直查（值即凭证——无状态校验的唯一查询面，
 * 唯一索引 uk_idn_guests_credential 承载）。
 */
public interface GuestRepository extends BaseRepository<Guest, Long> {

    Optional<Guest> findByCredential(String credential);

    /**
     * 原子作废（绑定平移的并发占位，CAS）：仅当行仍「未作废」才把 {@code revoked_at}
     * 由 NULL 翻成 {@code now}，返回受影响行数（1＝本次拿到平移权，0＝已被并发先绑）。
     * 库级原子——并发两登录同携一凭证时，READ COMMITTED 下后到者的 UPDATE 待前者
     * 提交后重判谓词（revoked_at 已非 NULL）即 0 行，杜绝双移（#328「并发不双移」）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Guest g set g.revokedAt = :now "
            + "where g.credential = :credential and g.revokedAt is null")
    int revokeIfActive(@Param("credential") String credential, @Param("now") LocalDateTime now);
}
