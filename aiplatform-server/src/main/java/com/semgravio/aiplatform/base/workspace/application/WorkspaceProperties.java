package com.semgravio.aiplatform.base.workspace.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作区配置（#63/#128/#129/#171，前缀 {@code app.workspace}）：置备最大尝试次数——后台置备失败的
 * 自动重试上界（含首次，即 {@code N-1} 次自动重试）；达上限转 failed 待手动重试
 * （{@link WorkspaceLifecycleAppService#retry}）。预览基域名与 scheme（#128 网关化、
 * #129 生产化）——预览 URL 子域 {@code {scheme}://{id}.{previewBase}} 的两个正交维度：
 * 开发 {@code http} + {@code localhost}，生产 {@code https} + {@code preview.{domain}}。
 * 闲置休眠（#171，ADR-0016）：闲置阈值（last-touch 逾此即休眠，工作区与快照查看
 * 会话同一阈值——#197 钉「同一闲置口径」）与休眠器开关（#197 内移：休眠扫描入口
 * 自持，业务侧调度器不再跨域读此开关；test profile 关闭——休眠扫描静默不扰测试库，
 * 快照清扫与之无关、随轮照跑）。封存（#172）：封存阈值（休眠满此即封存）与封存包目录
 * （本地磁盘 v1，换对象存储时目录配置退役、接口不动）；就绪等待超时（深度唤醒
 * 分钟级，3 分钟不够装依赖安装）。
 */
@Component
@ConfigurationProperties(prefix = "app.workspace")
public class WorkspaceProperties {

    /** 置备最大尝试次数（含首次，默认 3——即 2 次自动重试）。 */
    private int provisionMaxAttempts = 3;

    /** 预览基域名（#128，默认 localhost——开发子域 {id}.localhost）。 */
    private String previewBase = "localhost";

    /** 预览 URL scheme（#129，默认 http——开发关 TLS；生产 https）。 */
    private String previewScheme = "http";

    /** 闲置休眠阈值（#171，默认 60 分钟）：last-touch 逾此即休眠。 */
    private Duration idleThreshold = Duration.ofMinutes(60);

    /** 休眠器开关（#171，默认开；#197 内移——休眠扫描入口自持，test profile 关休眠扫描）。 */
    private boolean hibernationEnabled = true;

    /**
     * 封存阈值（#172，默认 1 天；#496 自 30 天收紧）：休眠满此即自动封存（删卷换包）。
     * #496 决策理由：硬链种子候选 5 双环境 EXDEV 出局（ADR-0046），活跃项目基座
     * 暂不可省、磁盘收敛全押封存——闲置项目卷存活窗口由 ~31 天（60m+30d）收紧至
     * ~25 小时（60m+1d），封存唤醒分钟级代价已确认可接受。
     */
    private Duration sealThreshold = Duration.ofDays(1);

    /**
     * 封存包目录（#172，默认 {@code seal-archives} 工作目录下）：本地磁盘 v1 的
     * 存放根；生产应挂独立卷/盘。
     */
    private String sealArchiveDir = "seal-archives";

    /**
     * 就绪等待超时（#172，默认 10 分钟）：置备中工作区执行需要环境的能力前的
     * 轮询上界——深度唤醒是分钟级（解包 + 依赖重装），旧 3 分钟不够。
     */
    private Duration readinessTimeout = Duration.ofMinutes(10);

    /**
     * 置备线程池大小（#476 容量口径，默认 12＝eu-1 单机上限）：可并行置备的
     * 工作区数，同时就是「同时冷启动无排队」的并发数。eu-1（8 vCPU/30G）标定：
     * 12 路并发置备的 CPU/IO 尖峰无压力，且 12 个项目随后全活跃（各 ~1.25G）
     * RAM 仍在墙内；再往上置备批时长退化、全活跃踩 OOM——换机器再调，别在本
     * 机加数。常驻线程组，改动重启生效（非运营配置热面）。
     */
    private int provisionThreads = 12;

    public int getProvisionMaxAttempts() {
        return provisionMaxAttempts;
    }

    public void setProvisionMaxAttempts(int provisionMaxAttempts) {
        this.provisionMaxAttempts = provisionMaxAttempts;
    }

    public String getPreviewBase() {
        return previewBase;
    }

    public void setPreviewBase(String previewBase) {
        this.previewBase = previewBase;
    }

    public String getPreviewScheme() {
        return previewScheme;
    }

    public void setPreviewScheme(String previewScheme) {
        this.previewScheme = previewScheme;
    }

    public Duration getIdleThreshold() {
        return idleThreshold;
    }

    public void setIdleThreshold(Duration idleThreshold) {
        this.idleThreshold = idleThreshold;
    }

    public boolean isHibernationEnabled() {
        return hibernationEnabled;
    }

    public void setHibernationEnabled(boolean hibernationEnabled) {
        this.hibernationEnabled = hibernationEnabled;
    }

    public Duration getSealThreshold() {
        return sealThreshold;
    }

    public void setSealThreshold(Duration sealThreshold) {
        this.sealThreshold = sealThreshold;
    }

    public String getSealArchiveDir() {
        return sealArchiveDir;
    }

    public void setSealArchiveDir(String sealArchiveDir) {
        this.sealArchiveDir = sealArchiveDir;
    }

    public Duration getReadinessTimeout() {
        return readinessTimeout;
    }

    public void setReadinessTimeout(Duration readinessTimeout) {
        this.readinessTimeout = readinessTimeout;
    }

    public int getProvisionThreads() {
        return provisionThreads;
    }

    public void setProvisionThreads(int provisionThreads) {
        this.provisionThreads = provisionThreads;
    }
}
