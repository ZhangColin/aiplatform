package com.aieducenter.aiplatform.base.workspace.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 快照孤儿清扫（#92 启动自愈）：快照容器是「查看当时」的临时只读视图（ADR 0007），
 * 不承载持久状态、查看结束即销毁。平台进程重启后，在途查看会话的在途注册表
 * （进程内 map）丢账，快照容器即孤儿——启动期扫清全部 {@code ws-*-snap-*} 容器，
 * 不留常驻孤儿（对齐「快照容器用完即销毁，不留孤儿容器」验收）。与置备重启自愈
 * （{@link WorkspaceProvisionRecovery}）分列：那是 PROVISIONING 工作区续置备，
 * 这是临时视图清扫，各自单一职责。
 */
@Component
public class SnapshotRecovery implements ApplicationRunner {

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public SnapshotRecovery(WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    @Override
    public void run(ApplicationArguments args) {
        workspaceLifecycleAppService.sweepSnapshotContainers();
    }
}
