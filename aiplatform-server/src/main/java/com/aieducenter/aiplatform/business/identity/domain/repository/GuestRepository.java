package com.aieducenter.aiplatform.business.identity.domain.repository;

import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Guest;

/**
 * 访客仓储（{@code idn_guests}）。按凭证直查（值即凭证——无状态校验的唯一查询面，
 * 唯一索引 uk_idn_guests_credential 承载）。
 */
public interface GuestRepository extends BaseRepository<Guest, Long> {

    Optional<Guest> findByCredential(String credential);
}
