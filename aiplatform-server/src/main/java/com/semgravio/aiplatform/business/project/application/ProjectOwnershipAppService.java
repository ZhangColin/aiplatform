package com.semgravio.aiplatform.business.project.application;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.identity.domain.port.GuestOwnershipPort;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 项目归属平移用例（#328 绑定平移）：访客名下未绑定项目过户给账户——实现
 * {@link GuestOwnershipPort}（identity 侧的南向需求），供登录完成时自动平移调用
 * （M01 与 OIDC 两路）。
 *
 * <p>单一事务（{@code @Transactional} REQUIRED，与调用方 GuestBindingAppService
 * 的「访客行作废」同事务）：按 owner_guest_id 取名下项目，逐个走聚合的
 * {@link Project#transferToAccount}（owner 双列互斥不变量在聚合，库层 CHECK
 * 兜底），一次 saveAll 落库。幂等：已平移（owner_guest_id 清空）的项目不再命中。</p>
 */
@Service
public class ProjectOwnershipAppService implements GuestOwnershipPort {

    private final ProjectRepository projectRepository;

    public ProjectOwnershipAppService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional
    public int migrateProjectsToAccount(Long guestId, Long accountId) {
        if (guestId == null || accountId == null) {
            return 0;
        }
        List<Project> projects = projectRepository.findByOwnerGuestId(guestId, Sort.unsorted());
        if (projects.isEmpty()) {
            return 0;
        }
        for (Project project : projects) {
            project.transferToAccount(accountId);
        }
        projectRepository.saveAll(projects);
        return projects.size();
    }
}
