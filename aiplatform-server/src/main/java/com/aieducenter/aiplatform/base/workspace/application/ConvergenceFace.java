package com.aieducenter.aiplatform.base.workspace.application;

/**
 * 向活收敛的面（#196，CONTEXT.md「收敛」词条）：收敛模块的入口身份——失败语义、
 * 等待策略、拨针口径全部是面的关联属性，不允许调用方自由组合未测配对。
 * 新面 = 显式加枚举值 + 判定表行（{@link WorkspaceConvergenceAppService} 内
 * switch 无 default，枚举加值编译期即炸，判定不静默漏分支）。
 *
 * <ul>
 *   <li>{@link #TOUCH} 触碰面（异步）：项目域 API 经过即活跃——入口无条件拨针
 *       （让路也不丢），随后互斥异步探查收敛；探查 UNKNOWN 让路下轮。</li>
 *   <li>{@link #SCAN} 扫描面（异步）：休眠扫描发现「期望运行而容器实死」的漂移
 *       收敛——平台内部自愈不是活跃信号，不拨针；UNKNOWN 让路下轮。</li>
 *   <li>{@link #DOWNLOAD} 下载面（同步）：项目文件包取件前把沙箱拉回可用——
 *       「取完包该继续睡」不是活跃信号，不拨针、不拉应用；UNKNOWN 跳过（容器
 *       若在打包自成，真死由打包如实失败）。</li>
 *   <li>{@link #ADMIN} 后台动作面（同步）：管理员显式唤醒等结果——发生收敛动作
 *       （对齐/重建/深度唤醒）后拨针（醒完不该秒睡），探查发现本就健康或纯等
 *       他人收敛落定不拨；UNKNOWN 如实抛 WSP_002（可重试，不盲重建）。</li>
 * </ul>
 */
public enum ConvergenceFace {
    TOUCH,
    SCAN,
    DOWNLOAD,
    ADMIN
}
