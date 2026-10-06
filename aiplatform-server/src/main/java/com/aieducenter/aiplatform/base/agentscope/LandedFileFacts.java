package com.aieducenter.aiplatform.base.agentscope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/**
 * 平台工具落盘事实（#292 出图真跑的观察面补位）：generate_image 这类平台工具件
 * 在容器侧落盘（docker exec 转存，不经引擎 write_file 工具）——流内文件变更
 * 观察（{@link FileChangeFacts}，只认写文件类工具的参数）看不见它，设计轨收口
 * 判据（design/ 有新稿）与收尾卡清单会漏图片模型路的稿。本件＝跨流段的落盘
 * 报告注册表：平台工具携 runId 报落盘（血统腿先例＝
 * {@link AgentscopeAgentClient#RUN_ID_CONTEXT_KEY} 上下文），客户端在 reply
 * 装配点按 runId 取走、并进 {@link AgentReply#changes()}——平台工具的写与执行体
 * 的写同为可观察事实，同一观察机制不立第二套。
 *
 * <p>生命周期＝run：reply 装配即取走（取走即清）；流段异常的尝试由客户端在
 * 错误分支弃取（失败尝试的变更不进收口清单，#84 口径）。正常场内无驻留；
 * runId 逐次换新、条目为字符串量级，无过期清扫面（异常泄漏上界＝失败 run 数）。</p>
 */
@Component
public class LandedFileFacts {

    private final Map<String, List<FileChange>> landed = new ConcurrentHashMap<>();

    /**
     * 平台工具报一次落盘：{@code anchoredPath}＝工作区锚定形（如
     * {@code /design/xxx.png}）；行数口径对位图无意义，added 计 1＝一文件落盘的
     * 活动量（与写文件类的「新增 N 行」同列呈现）。
     */
    public void land(String runId, String anchoredPath) {
        landed.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>())
                .add(new FileChange(anchoredPath, 1, 0));
    }

    /** reply 装配点取走（本 run 的全部落盘事实；无即空清单）。 */
    public List<FileChange> drain(String runId) {
        List<FileChange> changes = landed.remove(runId);
        return changes == null ? List.of() : List.copyOf(changes);
    }
}
