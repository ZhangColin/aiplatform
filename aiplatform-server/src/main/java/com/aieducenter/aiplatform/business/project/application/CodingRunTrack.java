package com.aieducenter.aiplatform.business.project.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 编码 run 在途标记（#100 回滚并发守卫的地基）：生成、修正与设计轨同口径的进程
 * 内事实——一场产出轨 run（生成首跑、修正迭代或设计过程轨道，#289）在途时，
 * 工作区正被执行体读写，回滚到此会丢其在途未提交改动（或在其后 commit 覆写
 * 回滚），故须在回滚前据以拒绝。
 *
 * <p>三条轨道天然互斥（生成要求未生成、修正在生成完成后才可达、设计轨对设计类
 * 终点在未生成态承接），故共用一个在途集合；单例组件——{@link
 * GenerationAppService} 起跑/释放、{@link IterationAppService} 起跑/排队/释放、
 * {@link DesignProcessAppService} 起跑/释放、{@link ProjectVersionAppService}
 * 回滚前查询，四处同锚。进程内态（重启即清）与轨道表（#220/#289 持久）分立：
 * 重启后本标记消失即中断态，用户重提意见即经收口链续跑。</p>
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
