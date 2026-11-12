package com.semgravio.aiplatform.base.workspace.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceFreshReads;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 就绪轮询的事务可见性回归（#476）：轮询读必须对「等待期外部翻转」可见——等待方
 * 自身的持久化上下文（一级缓存）/事务快照不得弄瞎轮询。11-12 生产实录：/prd 进
 * awaitReady 后 500ms 轮询 10 分钟全程看不见置备器翻转的 READY，挂满超时回 500。
 *
 * <p>两个致盲形态各钉一例：①请求长事务先装载实体（一级缓存持有 PROVISIONING 态，
 * 轮询 {@code findById} 命中缓存不发 SQL）；②OSIV 无事务上下文（web 请求级
 * EntityManager——/prd 现场，事务注解救不了的那种形态，线程绑定 Entity­ManagerHolder
 * 忠实复刻）。两例都由「置备线程独立事务在等待期中点翻 READY」收口：数秒内返回
 * READY 而非等满超时抛 WSP_011。</p>
 */
@IntegrationTest
class WorkspaceReadinessWaiterIntegrationTest {

    /** 等待超时上界：远大于翻转到点（300ms）＋轮询间隔，旧实现等满即红（非悬挂）。 */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    /** 翻转延迟：让 awaitReady 先进入轮询态，翻转发生在等待期中（生产时序）。 */
    private static final long FLIP_DELAY_MILLIS = 300;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceFreshReads freshReads;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<WorkspaceId> seeded = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (WorkspaceId id : seeded) {
            workspaceRepository.findById(id.id()).ifPresent(workspaceRepository::delete);
        }
    }

    @Test
    void given_request_tx_preloaded_when_provisioner_flips_ready_then_awaitReady_returns_promptly() throws Exception {
        WorkspaceId id = seedProvisioning();
        ExecutorService provisioner = Executors.newSingleThreadExecutor();
        try {
            // 请求形：长事务内先装载实体（持久化上下文持有 PROVISIONING）再等就绪
            Workspace woken = transactionTemplate.execute(status -> {
                Workspace loaded = workspaceRepository.findById(id.id()).orElseThrow();
                Future<?> flip = scheduleFlip(provisioner, id);
                try {
                    return waiter().awaitReady(loaded);
                } finally {
                    awaitFlip(flip);
                }
            });

            assertThat(woken.getStatus()).isEqualTo(ProvisioningStatus.READY);
            assertThat(woken.getContainerName()).isEqualTo("ws-" + id.value());
        } finally {
            provisioner.shutdownNow();
        }
    }

    @Test
    void given_osiv_bound_context_when_provisioner_flips_ready_then_awaitReady_returns_promptly() throws Exception {
        WorkspaceId id = seedProvisioning();
        ExecutorService provisioner = Executors.newSingleThreadExecutor();
        // OSIV 形：web 请求级 EntityManager 绑线程（无事务）——/prd 现场
        EntityManager requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory,
                new EntityManagerHolder(requestScoped));
        try {
            Workspace loaded = workspaceRepository.findById(id.id()).orElseThrow();
            Future<?> flip = scheduleFlip(provisioner, id);
            try {
                Workspace woken = waiter().awaitReady(loaded);

                assertThat(woken.getStatus()).isEqualTo(ProvisioningStatus.READY);
                assertThat(woken.getContainerName()).isEqualTo("ws-" + id.value());
            } finally {
                awaitFlip(flip);
            }
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestScoped.close();
            provisioner.shutdownNow();
        }
    }

    // ---------- 装配与测试数据 ----------

    private WorkspaceReadinessWaiter waiter() {
        return new WorkspaceReadinessWaiter(freshReads, WAIT_TIMEOUT, POLL_INTERVAL);
    }

    /** 种一棵置备中工作区（真库落定，PROVISIONING 已提交）。 */
    private WorkspaceId seedProvisioning() {
        WorkspaceId id = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(id, EnvKind.DEV));
        seeded.add(id);
        return id;
    }

    /** 置备线程形：延迟后在独立事务里翻 READY（findById 拿 detached → complete → save 提交）。 */
    private Future<?> scheduleFlip(ExecutorService provisioner, WorkspaceId id) {
        return provisioner.submit(() -> {
            sleepQuietly(FLIP_DELAY_MILLIS);
            transactionTemplate.executeWithoutResult(status -> {
                Workspace fresh = workspaceRepository.findById(id.id()).orElseThrow();
                fresh.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                        id, WorkspaceNaming.containerName(id), WorkspaceNaming.PREVIEW_NETWORK)));
                workspaceRepository.save(fresh);
            });
        });
    }

    private void awaitFlip(Future<?> flip) {
        try {
            flip.get(WAIT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("置备线程翻转未收口", e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
