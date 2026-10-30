package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

import org.junit.jupiter.api.Test;

/**
 * 在途标记的停止面（#359 生成取消）：requestStop 置停＋取消已登记任务，生命周期
 * 跟 begin/end 同锚——新轨不带旧停。
 */
class CodingRunTrackTest {

    /** 无在途：停止请求无的放矢（幂等 200 stopped=false 的判定源）。 */
    @Test
    void given_no_in_flight_when_request_stop_then_false() {
        CodingRunTrack track = new CodingRunTrack();
        assertThat(track.requestStop(42L)).isFalse();
        assertThat(track.stopRequested(42L)).isFalse();
    }

    /** 在途停止：置停＋已登记任务被取消（主轨/波任务的中断通道）。 */
    @Test
    void given_in_flight_when_request_stop_then_registered_tasks_cancelled() {
        CodingRunTrack track = new CodingRunTrack();
        track.begin(42L);
        FutureTask<String> main = new FutureTask<>(() -> "done");
        FutureTask<String> wave = new FutureTask<>(() -> "done");
        track.registerTask(42L, main);
        track.registerTask(42L, wave);

        assertThat(track.requestStop(42L)).isTrue();
        assertThat(track.stopRequested(42L)).isTrue();
        assertThat(main.isCancelled()).isTrue();
        assertThat(wave.isCancelled()).isTrue();
    }

    /** 已停止后补登记的任务立即取消（停止与波提交的竞态——不漏网起跑）。 */
    @Test
    void given_stop_requested_when_register_task_then_cancelled_immediately() {
        CodingRunTrack track = new CodingRunTrack();
        track.begin(42L);
        track.requestStop(42L);

        FutureTask<String> late = new FutureTask<>(() -> "done");
        track.registerTask(42L, late);

        assertThat(late.isCancelled()).isTrue();
    }

    /** 收工释放即清停止面：续起新轨不带旧停标志（「继续生成」不受残留停止影响）。 */
    @Test
    void given_stopped_track_when_end_then_next_track_not_stopped() {
        CodingRunTrack track = new CodingRunTrack();
        track.begin(42L);
        track.requestStop(42L);
        track.end(42L);

        assertThat(track.requestStop(42L)).isFalse();

        track.begin(42L);
        assertThat(track.stopRequested(42L)).isFalse();

        // 未完成的登记任务不反噬（completed future 的 cancel 是无害 no-op）
        CompletableFuture<String> completed = CompletableFuture.completedFuture("ok");
        track.registerTask(42L, completed);
        assertThat(completed.isDone()).isTrue();
    }
}
