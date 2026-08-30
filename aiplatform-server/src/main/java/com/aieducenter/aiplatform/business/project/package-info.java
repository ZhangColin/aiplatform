/**
 * Project Context（business.project）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>项目聚合（业务字段 + workspaceId + owner）与生命周期编排（一句话建项目、
 *       归档、改名、删除级联、源码包、预览）</li>
 *   <li>BA 访谈编排（ba-{projectId} 会话稳定绑定与恢复、问答答复续跑；直调
 *       base.agentscope 内核——编排缝极薄）与 BA 智能体资产（ask_user / savePrd
 *       工具集 + PRD 落盘业务登记）</li>
 *   <li>角色卡 preset（v1 仅 BA，代码配置不落库）与 PRD 读侧</li>
 *   <li>SSE 编排层发射：平台通知（workspace-created / preview-ready /
 *       workspace-destroyed / document-updated / project-renamed）+ 智能体流
 *       projectId 桥接与 role-assigned 发射（eventhub 唯一 SSE 管道）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>交付业务核心：编排 base 各能力（环境 / 智能体内核 / 事件 / 知识 / 计量）。
 * 表前缀 {@code prj_}，错误码前缀 {@code PRJ_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：聚合根（Project）、角色卡 preset 与产物路径（model）、仓储接口、枚举、错误码</li>
 *   <li>application - 应用层：应用服务（Lifecycle / BaInterview / Query / Naming / Knowledge）、SSE 事件名册常量、DTO</li>
 *   <li>infrastructure - 基础设施层：BA 工具集装配（agentscope/，含 savePrd 的
 *       PRD 产物登记——置状态位 + document-updated）</li>
 *   <li>endpoints - 北向接口适配器层：REST API（ProjectController）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Project", subDomain = SubDomain.CORE)
package com.aieducenter.aiplatform.business.project;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
