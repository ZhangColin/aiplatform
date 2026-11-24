# Demo 阶段快模型档（运营口径）

Demo 阶段的模型配置初档与改配置 SOP。机制正本＝[ADR-0021](../adr/0021-agent-operational-config-persisted-skill-snapshot-install.md)（智能体运营配置落库、身份与配置分治）；本指引是其缺省档与切换动作上的执行面（#341）。

## 一、Demo 阶段识别

交付生命周期四阶段（需求分析 → Demo 体验 → 签约付款 → 生产承接）中，**Demo 阶段**承接「首 Demo ＋ 迭代」两类任务：

- **首 Demo** ＝ 用户确认 PRD 后平台自动起的首个生成 run；
- **迭代** ＝ 首 Demo 收口后，用户在对话区提意见经主智能体受理判定、平台据此自动起的更新 run。

两类任务由同一个 run 执行体（`AgentProfile.EXECUTOR`，寻址键 `executor`）承载——Demo 与正式生产同一条全栈生成轨、同一副协议（Demo 是系统的售前真实形态，不是降配假货）。因此 **Demo 阶段模型档 ＝ run 执行体的模型档**。

正式生产档为未来独立槽位（平台内生产机制未做，能力到位前不设），与本档互不牵连——首 Demo/迭代任务的模型档可独立于正式生产档配置（两座智能体各持自身缺省档位、运营配置面按 `agentKey` 各自覆盖）。

## 二、初档配置（缺省）

Demo 阶段激进用快模型（质量靠生成内核自证＋收口判据兜底——内核按完成判据自跑 `pnpm build`、平台侧机器判据双件〔build 探活＋8081 探活〕承担质量面）。缺省档正本＝`AgentProfile.EXECUTOR` 枚举默认：

- **缺省档 ＝ 快模型档**（配置键 `flash`）；清空覆盖即回落此值。
- 主智能体（`main`）不在本档范围——对话/需求侧，档位另配；设计执行体（`designer`）随设计线整条退役拆除（#610）。

## 三、改配置 SOP（不发版）

档位经智能体运营配置机制切换，**不发版**——改配置下一次命令构建即生效（每次命令构建动态查库取生效值，无缓存）。模型配置值为**语义键**（ADR-0031：`flash`/`pro`/`doubao-flagship` 这类运营短名，指向实际模型名与供应商通道——模型版本升级改配置行指向即可，逐处绑定不动）：

1. **改配置**：`PUT /api/backoffice/agent-configs/executor`（机机五头 HMAC 签名；`X-User-Id`/`X-User-Name` 必带——写操作留痕）。请求体全量语义：`{"modelConfigKey": "pro"}` 换强模型档，`{"modelConfigKey": "flash"}` 回快模型档，`{"modelConfigKey": null}`（或纯空白/缺省）清空覆盖、回落枚举缺省（快模型档）。配置键即时校验（#367）：非空须在模型配置面（`/api/backoffice/model-configs`）有行且启用——错值 400 当场拒（PRJ_074 缺行 / PRJ_075 停用），不再靠留痕回滚兜底；null 清空不校验。

**模型版本升级（方舟名带日期后缀、季度级更新）**：改配置行指向即全部引用自动跟上——`PUT /api/backoffice/model-configs/{configKey}` 改 `modelName`（同面可换通道、改显示名、停启用、开/关深度思考〔`thinkingEnabled`，缺省保持现值；缺省关＝显式关——不配置不默认拖慢首 token，#370〕）。V79 起（#556/ADR-0053）配置＝有序候选队列、写面改配置同步镜像 priority 1 首选候选（`prj_model_config_candidates`）——单档改指向语义不变；**主备多通道编排（#559/ADR-0053 ③ 后台写面）走候选子资源** `/api/backoffice/model-configs/{configKey}/candidates`：增（POST，缺省追加队尾/显式序插位）、改指向/启停/排序（PUT `/{priority}`——缺省保持、动到 priority 1 配置行镜像跟随）、删（DELETE——队列压实、最后候选不可删）、留痕读面（GET `/candidates/traces`——身份轴前后快照，回滚＝旧值写回即一次新变更）。建新配置走 POST、删行有绑定守卫（被引用 409）。**换 key/换通道/换归一**（不发版不重启）：`/api/backoffice/model-providers` 面——apiKey 写收全量值、读面与留痕一律掩码（尾 4 位），`PUT` 不携 apiKey＝保持现值、携全量新值＝换 key；vendor 受控值（小写 slug，缺省＝通道键同值——同供应商多账号行显式同填，#559）改完下一轮命令构建即生效。curl 执行面：`aiplatform-server/scripts/backoffice-model-channel.sh`（`demo` 全流程演示〔可弃键自动清场，含主备候选编排段〕＋单动词运维操作——`add-candidate`/`set-candidate`/`enable-candidate`/`move-candidate`/`delete-candidate`/`traces-candidates`）。
2. **查生效值**：`GET /api/backoffice/agent-configs/executor`——生效 `modelConfigKey` ＋覆盖标记 `modelConfigKeyOverridden` ＋缺省预览 `defaultModelConfigKey`。
3. **回滚**：`GET /api/backoffice/agent-configs/executor/traces` 取某痕旧值快照，把旧值 `PUT` 回写（即一次新变更、留新痕——不做版本树）。

**主备候选（高可用）配置与演练（ADR-0053，#560 收口移交）**：单通道 401/403/402（认证失效/余额）类故障下平台命令面自动切备候选、无需人工干预（机制面 `FailoverModel` 收口，切换留痕 `prj_model_config_switch_traces` 运营可见）。**当前态＝种子单候选**（flash/pro 各一 deepseek 官方行）——failover 机制在位但**备候选缺位**，主备配置是运营动作：

1. **备账号 key 就位后配置**（不发版不重启，改完下一轮命令构建即生效）：先建备通道行 `POST /api/backoffice/model-providers`（同供应商多账号行 vendor 显式同填 deepseek；前缀即协议路由——通道行键取受支持前缀，OpenAI 兼容端点用 `openai` 键＋base_url 指实际端点），再经候选面 `POST /api/backoffice/model-configs/{configKey}/candidates` 追加备选（curl 执行面同 `backoffice-model-channel.sh` 的 `add-candidate`）。
2. **定期活体演练**（建议随改配置窗口跑）：`ModelFailoverDrillLiveTest`（live 车道，`set -a; source .env.local; set +a && mvn test -P live -Dtest=ModelFailoverDrillLiveTest`）——测试库 flash 主候选注入假 key（真 401）→ 经真实解析链＋平台命令面验证自动切备、生成成功、留痕与计量归因（2026-11-20 全绿收口：假 key 真 401→切换留痕→备候选生成成功；演练自注入自还原，红先查 `.env.local` key 死活——本日旧 dev key 曾在供应商侧失效，法证靠全量落盘日志）。

**边缘调用点同面改配置（#366）**：项目取名（`naming`）、上下文压缩（`compaction`）、入口分类（`classification`）、技能预检（`precheck`）四件一次性轻调用的模型配置收编同一配置面——端点 `/api/backoffice/agent-configs/edge/{edgeKey}`（GET/PUT/traces 同制，值面仅模型配置：`{"modelConfigKey": "pro"}`；缺省一律 `flash`）。改配置下一轮调用即生效（装配动态查库）。

**收口与验证面开关（#424／#427／#433／#442）已随审查与收口组退役（#605，ADR-0055）**：验证子智能体、整轨终验、收口三运营开关与基座 lint 阻断开关整体出局——`prj_agent_configs` 六列经 V84 迁移拆除，后台 GET/PUT/留痕面不再有这些字段；收口判据只余平台侧机器双件（build 探活＋8081 探活）。生成会话形态两开关随切片与轨道机器退役（#602，V82 迁移拆除）。

## 四、进行中 run 不定格

配置读取时机＝**起跑时读档**：模型配置在每次命令构建（下发 run/对话轮）时取一次生效值，随命令交给智能体工厂；工厂缓存键含模型串，新值即新实例。因此**改配置不影响在途 run**——在跑的那一段沿用起跑时读到的配置跑完，改配置只影响**下一轮**命令构建。修订中的工作协议（systemPrompt）同理。

## 五、模型路由已退役（#606）

模型路由（#354／ADR-0030、结构性信号开关 #454／ADR-0037、升档窗口 #472／ADR-0041）已随从零重设整组退役（ADR-0055 #583 死旋钮）：路由判定服务、`prj_agent_configs` 六列与留痕镜像列、后台读写面与删除护栏的路由强档校验、收尾卡 `closing.modelTier` 留档全部拆除（V88 迁移，#606）。run 直接取执行体生效档；无信号选档、无升档窗口。本档仍是**配置态初档**，运营经第三节 `PUT` 换档（不发版）。重启条件见 ADR-0055 组⑤（实证「快配置稳定干不动、强配置干得动」且运营手动换档跟不上）。

**正式生产档**：平台内生产机制设计未做，正式生产档不设；能力到位再立独立槽位。

## 六、活体对比留档（速度 / 质量）

速度目标（与 Replit 相当，首版 40 分钟量级带）的推算靠 Demo 阶段快/强档实测对比。做法（真运行是唯一准绳）：

1. 起 dev 栈（`scripts/dev.sh`），建项目 → 改一条需求触发首 Demo（或对已有项目发一条迭代意见）跑一轮；
2. 记录本轮**时长**（run 级已运行时钟 / 收尾卡轮末统计）与**质量**（预览可用性、收口判据〔build 探活＋8081 探活〕是否通过）；
3. 对照轮次按同一任务各跑一轮，逐轮记录时长、质量、项目账 token 与生效档位（收尾卡 `durationBreakdown.attempts[].modelId`——供给链归因）：
   - **快档基准**：`GET /api/backoffice/agent-configs/executor` 确认生效 `modelConfigKey=flash`；
   - **强档对照（质量基线）**：`PUT` 写 `{"modelConfigKey": "pro"}`（改档不发版，下一轮命令构建即生效）；
4. 对比结果留档（时长量级、质量差异、项目账 token）——快档轮供速度目标推算，强档轮为质量基线。

**enableThinking 开关对比（#369 留下、#370 开关后台化后可做）**：方舟 DeepSeek V4 系均标深度思考，装配显式表态不留给模型默认；开关跟配置走（`prj_model_configs.thinking_enabled`，缺省 false＝显式关——#369 活体结论「维持 false」）。对比法：配置维护面 `PUT /api/backoffice/model-configs/{configKey}` 带 `"thinkingEnabled": true` 开→同一任务跑一轮，与关态轮对比首轮延迟/质量/token——改完下一轮命令构建即生效（通道指纹含 thinking 腿，进行中 run 不定格），不发版不重启；结论按档回写（若开态显著拖慢首 token 则维持关，用户故事 12 承诺不破），留痕可回滚。成本面联动：跑完经成本观测面核 volcengine 模型行有单价匹配（单价表已开三档位实际模型行，#369 种子）、无 unpriced 警示。
