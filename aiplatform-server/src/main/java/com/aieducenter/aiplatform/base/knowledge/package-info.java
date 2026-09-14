/**
 * Knowledge Context（base.knowledge）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>知识入库 / 检索（KnowledgePort：index(内容+元数据) / retrieve(query, topK)）</li>
 *   <li>素材状态治理（#153：disable/enable 可逆开关，停用素材的块退出检索命中）</li>
 *   <li>素材管理面（#166：清单/详情/停用⇄启用/删除——管理单元＝素材，内容面零写）</li>
 *   <li>EmbeddingClient（本机 fastembed :9091，512 维）+ pgvector HNSW 余弦检索</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座知识能力：「哪个阶段产出什么文件」是业务知识，归 business.project 在调用
 * 端口时传入。表前缀 {@code knw_}（素材登记表 knw_materials——身份/状态/操作者
 * 单一事实源＋块表 knw_chunks，kind/source_ref 幂等删插），错误码前缀 {@code KNW_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：模型（KnowledgeSpec/KnowledgeHit/Operator/
 *       MaterialRecord 素材登记行读模型）、枚举（MaterialStatus 素材状态）、端口
 *       （KnowledgePort 北向入口 / EmbeddingClient 南向向量化）、KnowledgeStore
 *       存取接口（两表）、KNW_ 错误</li>
 *   <li>application - 应用层：KnowledgeAppService（入库幂等删后插 / 检索降级 /
 *       素材停用⇄启用 / 级联清理）、BackofficeKnowledgeAppService（#166 素材
 *       管理读面与治理写口）</li>
 *   <li>infrastructure - 基础设施层：KnowledgeLocalAdapter（端口进程内适配）、
 *       persistence/PgvectorKnowledgeStore（JdbcTemplate + pgvector，登记表 upsert
 *       保状态跨重沉淀）、embedding/FastembedEmbeddingClient</li>
 *   <li>endpoints - 北向接口：controller/BackofficeMaterialController（#166 后台
 *       素材管理 REST 面，机机签名）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Knowledge", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.knowledge;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
