# 激活升格＋知识检索混检：kind 扩 PRD/CASE/MODULE/RULE、孤儿沉淀、双通道 RRF

数据飞轮一期·第二层（2026-11-11，#465，承 #451 决策正本、ADR-0038 采集层与 #464「数据标注」审结面）。现状事实：#464 的激活键只落审结终态，升格未通；检索是纯向量单通道——CASE/MODULE/RULE 沉淀物（短文本、术语密集，如「退款规则」「登录模块」）只靠向量相似度召回不稳，且素材与来源项目的级联耦合会让「项目删除」误伤已沉淀知识。定案——激活即升格＋检索混检：

**激活升格**：`SedimentDraftAppService.activate` 在 CAS 翻终态（#464 已落地）后同步升格 `knw_materials`——kind 随草稿（CASE/MODULE/RULE，库面开放零表结构变更）、source_ref＝草稿 id（(kind, source_ref) 幂等键天然唯一，且与 `knw_sediment_drafts` 互溯）、标题/正文原样不改稿、正文经段落级分块（`KnowledgeChunker`——#30 PRD 沉淀同款口径，自 business.project 下沉底座共用）入 `knw_chunks`，meta 带 sedimentDraftId/summary 血统。升格失败口径＝知识线既有降级契约：embedding 不可用记日志跳过（丢失容忍，A5 §1）不翻回终态——embedding 停机不阻塞标注队列；向量库写失败上抛整体回滚（真实错误非降级范畴）。

**孤儿沉淀（激活即脱钩）**：升格素材 `project_id` 置空（NULL，V61 `DROP NOT NULL`）——「孤儿沉淀＝知识独立存在，来源项目删除不影响已激活知识」（#457 决策）。NULL 永不命中 `purgeByProject` 级联（`WHERE project_id = ?`），级联语义零改码即正确；既有 PRD 素材行 project_id 原值不动（项目绑定素材仍随项目级联）。登记/命中展示面以固定名「平台沉淀」承载来源位（脱钩后来源项目名不再可考，也不承载语义）。

**检索混检（自纯向量升级）**：`findRelevant(query, vector, topK)`＝向量通道（HNSW 余弦，A5 §3 全局跨项目不变）＋关键词通道（pg_trgm 三元组相似度——中文无分词依赖的库内原生模糊匹配，V61 `CREATE EXTENSION`）各取 `2×topK` 候选，RRF（k=60）融合取 topK；双通道同中块叠加进位。噪音闸＝关键词通道 `similarity > 0`（与 query 零三元组重叠的块不进候选——无词命中时通道零贡献，不硬塞）；停用过滤两通道同开（JOIN `knw_materials status=1` 恒开，#153 语义不变）。降级契约不变：embedding 不可用检索降级空列表（空注入不阻断），写失败上抛。按 kind 过滤检索一期不预置（留口——混检噪音大时启用）。

## Considered Options

- **tsvector 全文检索做关键词通道**——拒绝：PostgreSQL 默认分词器对中文不分词（连续汉字整段成单 token），需额外中文分词配置；pg_trgm 三元组不依赖分词，对短术语与长 query 的混合形态都稳。
- **应用层关键词抽取（Java 分词/子串匹配）**——拒绝：引入分词依赖与启发式维护面，且要把候选块拉回应用层逐条打分；pg_trgm 库内排序取候选，数据不出库。
- **project_id 哨兵值（空串/'orphan'）表达脱钩**——拒绝：魔法值与真实项目 id 混型，级联/过滤都要记挂「排除哨兵」特例；NULL 是「置空」的 SQL 正解，级联与等值过滤天然正确。代价是一列约束放宽（V61），换来语义零特例。
- **升格失败硬拒激活（embedding 停机即 5xx 回滚）**——拒绝：与知识线「尽力而为、失败丢失容忍」口径（A5 §1）一致——激活是审结动作，不该被向量化基础设施的可用性阻塞；降级有日志可观测、可重审补偿（重提新草稿）。
- **混检加按 kind 过滤（PRD 与沉淀物分池）**——拒绝：一期不预置（#457 Out of Scope），零重叠闸＋topK 已控噪音；kind 已在命中条目透出，注入面（#466）可按需分呈现。

## Consequences

- 「数据标注」激活键从「只落审结终态」变为「审结＋升格」一体（#464 seam 的「激活后 knw_materials 零行」边界钉死随之翻转为升格落库＋孤儿脱钩断言）；后台接口出入参契约不变，无 admin 对接票（行为面声明见 #465 票内评论）。
- 检索排序语义变化（纯向量 → 双通道 RRF）：既有 PRD 素材命中行为兼容（向量通道原样保留，融合只会把文本强相关的块提前），KnowledgeAppServiceTest 既有用例全绿佐证。
- 知识库出现多 kind 素材与 project_id 为 NULL 的登记行：后台素材管理面可见「平台沉淀」来源名，素材清单按项目过滤天然不含孤儿（等值不匹配 NULL）。
- pg_trgm 成为库依赖（pgvector/pgvector 官方镜像自带 contrib，dev/test/CI 同镜像族）；分块器 `KnowledgeChunker` 下沉底座，business.project 委托调用（口径单源）。
- 注入面（主智能体尾注/run 下发前置注入携带多类沉淀命中）归 #466——本 ADR 的混检与 kind 透出是其地基，零新通道口径不变。
