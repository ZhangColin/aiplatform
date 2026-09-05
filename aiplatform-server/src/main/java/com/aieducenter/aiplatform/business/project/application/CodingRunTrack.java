package com.aieducenter.aiplatform.business.project.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 编码 run 在途标记（#100 回滚并发守卫的地基）：生成与修正同口径的进程内事实——
 * 一场编码 run（生成首跑或修正迭代）在途时，工作区正被 run 执行体读写，回滚到此
 * 会丢其在途未提交改动（或在其后 commit 覆写回滚），故须在回滚前据以拒绝。
 *
 * <p>生成与修正天然互斥（生成要求未生成、修正在生成完成后才可达），故共用一个
 * 在途集合；单例组件——{@link GenerationAppService} 起跑/释放、{@link
 * IterationAppService} 起跑/排队/释放、{@link ProjectVersionAppService} 回滚前
 * 查询，三处同锚。进程内态与轨道状态同口径（重启即清）：重启后在途 run 标失败，
 * 用户重新发起即重新起轨。</p>
 */
@Component
public class CodingRunTrack {

    /** 在途编码 run 的项目集（生成或修正在途，含已提交未起跑——排队中）。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 起跑登记：已在途返回 false（调用方按各自语义分岔——生成拒 / 修正排队）。 */
    public boolean begin(Long projectId) {
        return inFlight.add(projectId);
    }

    /** 收工/异常释放（幂等）。 */
    public void end(Long projectId) {
        inFlight.remove(projectId);
    }

    /** 在途探针（回滚守卫用）：项目是否有一场编码 run 在途。 */
    public boolean isInFlight(Long projectId) {
        return inFlight.contains(projectId);
    }
}
