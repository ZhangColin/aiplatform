package com.aieducenter.aiplatform.base.workspace.domain.port;

import java.net.URI;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * 环境后端端口（CONTEXT.md「环境」六条能力面的 Phase A 子集）：薄接口只归一化，
 * 不塞业务语义。后端可替换：本地 Docker（Docker CLI 子进程）→ 上云 TKE/远端
 * （配置切换适配器，接口不动）。
 *
 * <p>本片实现四条：createWorkspace（单容器 all-in-one 沙箱：容器入口脚本自愈起
 * pg/redis，{@code /workspace/.env} 连接串注入）/ destroyWorkspace（容器→卷级联清理）/
 * exec（容器内跑命令取结果）/ exposePort（预览 URL）。snapshot+restore、
 * attachResource 按需随各自切片扩。</p>
 */
@Port(PortType.CLIENT)
public interface EnvironmentBackend {

    /** 工作区应用服务的约定容器端口（编码智能体起服、平台预览取流量的单一事实，#44）。 */
    int DEV_APP_CONTAINER_PORT = 8081;

    /**
     * 创建工作区并落定全部真实副作用（容器/中间件/.env），返回句柄与资源清单。
     * 幂等倾向：对同名残留容器先清理再建（卷保留，重建即自愈、数据不丢）。
     */
    WorkspaceProvision createWorkspace(WorkspaceId workspaceId, EnvKind kind);

    /**
     * 销毁工作区：级联清理容器 → 数据卷（尽力而为，失败不抛——记录清理由调用方负责）。
     */
    void destroyWorkspace(WorkspaceHandle handle);

    /**
     * 在工作区内执行一条命令，取 stdout/stderr/exitCode。
     */
    ExecResult exec(WorkspaceHandle handle, String command);

    /**
     * 暴露容器端口为可访问的预览 URL（本地 = Docker 端口映射；线上 = Ingress/负载均衡）。
     * 渐进预览口径（#45）：映射置备时已落定、URL 确定，本调用只做探活——应用服务
     * 由编码智能体按约定自起（#44），平台不代起静态兜底；探活通过才返回 URL
     * （调用方以此作「应用可访问」判据），短窗未就绪抛 WSP_012（待期，非故障）。
     */
    URI exposePort(WorkspaceHandle handle, int containerPort);

    /**
     * 打包工作区源码为 tar.gz 字节流（「取走工作区内容」的能力面：调用方拿去做
     * 下载交付，业务语义归调用方）。实现负责排除平台生成的机密
     * （/workspace/.env 连接串）与可重建的重物（node_modules）——包里是源码
     * 事实，不是环境镜像。
     */
    byte[] packSource(WorkspaceHandle handle);
}
