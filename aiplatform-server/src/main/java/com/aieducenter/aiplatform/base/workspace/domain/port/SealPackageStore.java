package com.aieducenter.aiplatform.base.workspace.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 封存包存储端口（#172，ADR-0016）：封存包在平台侧的落盘/取包/清理——本地磁盘
 * v1，将来换对象存储换适配器、接口不动（路径是存储内寻址键，入库即事实）。
 * 按工作区确定性命名，重复封存覆盖旧包。
 */
@Port(PortType.CLIENT)
public interface SealPackageStore {

    /**
     * 落盘封存包（覆盖语义：同工作区重复封存覆盖旧包），返回存储事实（路径 + 大小）。
     */
    SealPackage save(WorkspaceId workspaceId, byte[] content);

    /**
     * 按路径取包内容；包不在/不可读抛（调用方决定口径——深度唤醒遇不可读包保持
     * 封存态待人工介入，不以空卷顶替）。
     */
    byte[] open(String path);

    /**
     * 按路径清理封存包（项目删除时一并清理）；包不在为 no-op，尽力而为不抛。
     */
    void delete(String path);
}
