package com.semgravio.aiplatform.base.workspace.infrastructure.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceFreshReads;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * {@link WorkspaceFreshReads} 实现（#476）：抛弃销 EntityManager 直读——每次
 * 调用新建独立持久化上下文、自动提交读、即读即关，调用方上下文（一级缓存/
 * 在途事务快照）影响不到这次读。
 *
 * <p>为何不用 {@code REQUIRES_NEW}：无外层事务时它等同 REQUIRED，事务照样
 * 开在线程绑定的 EntityManager 上、照样命中一级缓存——救不了。全史见
 * CONTEXT.md「独立读」词条（正本）。500ms 一次的轮询频率下，一次
 * EntityManager 分配不构成成本。</p>
 */
@Component
public class WorkspaceFreshReadsImpl implements WorkspaceFreshReads {

    private final EntityManagerFactory entityManagerFactory;

    public WorkspaceFreshReadsImpl(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public Optional<Workspace> findFreshById(Long id) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return Optional.ofNullable(em.find(Workspace.class, id));
        } finally {
            em.close();
        }
    }
}
