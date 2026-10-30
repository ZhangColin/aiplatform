package com.semgravio.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 编码 run 在途标记（#100 回滚并发守卫的地基）＋停止面（#359 生成取消）：生成、
 * 修正与设计轨同口径的进程内事实——一场产出轨 run（生成首跑、修正迭代或设计过程
 * 轨道，#289）在途时，工作区正被执行体读写，回滚到此会丢其在途未提交改动（或在
 * 其后 commit 覆写回滚），故须在回滚前据以拒绝。
 *
 * <p>三条轨道天然互斥（生成要求未生成、修正在生成完成后才可达、设计轨对设计类
 * 终点在未生成态承接），故共用一个在途集合；单例组件——{@link
 * GenerationAppService} 起跑/释放、{@link IterationAppService} 起跑/排队/释放、
 * {@link DesignProcessAppService} 起跑/释放、{@link ProjectVersionAppService}
 * 回滚前查询，四处同锚。进程内态（重启即清）与轨道表（#220/#289 持久）分立：
 * 重启后本标记消失即中断态，用户重提意见即经收口链续跑。</p>
 *
 * <p><b>停止面（#359）</b>：在途标记的主动对偶——用户叫停在途 run。请求经
 * {@link #requestStop} 置停并 {@link Future#cancel(boolean) 中断}已登记的执行任务
 * （主轨 stripe 任务＋并行波任务；中断打断模型流订阅即停烧 token）；执行体各环
 * （尝试环起跑前/失败处置）经 {@link #stopRequested} 查停，停止不走重试环、不
 * 触发段回滚（用户停止＝中断口径，非判死——续跑走断点脏续，ADR-0020）。生命周期
 * 跟 begin/end 同锚：释放即清停止面，续起新轨不带旧停。内部无锁（入口并发低；
 * 修正轨的排队临界区以本组件为 monitor，不在内部加锁防自锁）。</p>
 */
@Component
public class CodingRunTrack {

    /** 在途项目的执行事实：停止标志＋已登记任务（主轨/波），随 begin 创建、end 释放。 */
    private static final class InFlight {

        final AtomicBoolean stopRequested = new AtomicBoolean();
        final List<Future<?>> tasks = new CopyOnWriteArrayList<>();
    }

    /** 在途编码 run 的项目集（生成或修正在途，含已提交未起跑——排队中）。 */
    private final Map<Long, InFlight> inFlight = new ConcurrentHashMap<>();

    /** 起跑登记：已在途返回 false（调用方按各自语义分岔——生成拒 / 修正排队）。 */
    public boolean begin(Long projectId) {
        return inFlight.putIfAbsent(projectId, new InFlight()) == null;
    }

    /** 收工/异常释放（幂等）——停止面随清，续起新轨不带旧停。 */
    public void end(Long projectId) {
        inFlight.remove(projectId);
    }

    /** 在途探针（回滚守卫用）：项目是否有一场编码 run 在途。 */
    public boolean isInFlight(Long projectId) {
        return inFlight.containsKey(projectId);
    }

    /**
     * 停止请求（#359）：在途即置停＋中断已登记任务，返回 true；无在途返回 false
     * （幂等——REST 面 stopped=false 如实回，不 409 防连点/与自然收口的竞态）。
     * 置停后补登记的任务在 {@link #registerTask} 即时取消，不漏网起跑。
     */
    public boolean requestStop(Long projectId) {
        InFlight entry = inFlight.get(projectId);
        if (entry == null) {
            return false;
        }
        entry.stopRequested.set(true);
        for (Future<?> task : entry.tasks) {
            task.cancel(/* mayInterruptIfRunning= */ true);
        }
        return true;
    }

    /** 停止探针：尝试环/轨道层各安全点查停（置停后的任何失败按停止处置，不重试）。 */
    public boolean stopRequested(Long projectId) {
        InFlight entry = inFlight.get(projectId);
        return entry != null && entry.stopRequested.get();
    }

    /**
     * 执行任务登记（主轨 stripe 任务＋并行波任务）：停止请求到达时经
     * {@link Future#cancel(boolean) cancel(true)} 中断执行线程（打断模型流订阅）。
     * 已置停时登记即取消（提交与停止的竞态——不起跑即停）；completed future 的
     * cancel 是无害 no-op（passthrough 测试直通执行已完）；null 跳过（测试桩
     * submit 不回句柄的容错，生产 submit 恒非 null）。
     */
    public void registerTask(Long projectId, Future<?> task) {
        if (task == null) {
            return;
        }
        InFlight entry = inFlight.get(projectId);
        if (entry == null) {
            return; // 释放后才登记（竞态边角）：无在途无停止面，任务自跑自收
        }
        entry.tasks.add(task);
        if (entry.stopRequested.get()) {
            task.cancel(true);
        }
    }
}
