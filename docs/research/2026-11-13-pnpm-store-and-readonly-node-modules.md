# pnpm store 与 node_modules 语义调研（Docker 只读基座＋增量装包场景）

调研日期：2026-11-13。全程一手来源：pnpm.io 官方文档（pnpm.io 仓库 markdown 正本）、pnpm/pnpm GitHub issue/PR 中维护者（zkochan）定论。背景约束：镜像层内置 node_modules（约 600M）与 .pnpm-store（约 600M CAS），工作目录挂独立卷；卷与镜像层是不同文件系统，跨设备硬链不可行；容器内 `pnpm add`（增量装包）与 `shadcn add` 必须可写可用。本文只钉事实，不做方案设计。

文档版本口径：pnpm 12.x（当前 docs 默认版本；pnpm 11 的对应文档在 pnpm.io versioned_docs 下）。

---

## 1. store 机制

### 1.1 storeDir 语义：一块盘一个 store，跨盘自动降级 copy

- 结论：store 是全局 content-addressable store，官方明确「store 应始终与安装发生在同一磁盘（一块盘一个 store）」；「可以把 store 配到另一块盘，但此时 pnpm 会从 store **copy** 包而非 hardlink（硬链只在同一文件系统内可能）」。不报错，静默降级。
- 来源：https://pnpm.io/settings/store#storedir
- 含义：store（镜像层）与 node_modules（卷）跨设备时，pnpm 不会失败，而是把每个用到的文件整体拷进卷——「不落卷」期望会被 copy 打穿。

### 1.2 store 位置自动探测：只有项目可写时 store 会退进项目内 node_modules/.pnpm-store

- 结论：若项目祖先链上没有任何可接受硬链的目录（agent 沙箱、只把项目 bind-mount 为可写的容器），pnpm 会把 store 建在项目内 `node_modules/.pnpm-store`；pnpm 通过在候选位置创建临时探测文件测硬链能力，pnpm 11 在「仅项目可写」场景会（bug 性地）落到项目内 store，且探测文件就写在项目根。
- 来源：https://pnpm.io/settings/store#storedir（正文显式引用该 issue）；https://github.com/pnpm/pnpm/issues/13525
- 含义：容器里若只把卷挂为可写位置，pnpm 默认行为会把 600M store 写进卷上的 node_modules/.pnpm-store——必须显式 `store-dir` 才能钉住 store 落点。

### 1.3 package-import-method：auto 在 Linux 上的降级链与 EXDEV 行为

- 结论：默认 `auto`，Linux 顺序 hardlink → clone → copy；macOS clone → hardlink → copy；Windows hardlink → clone → copy。文档明说：「store 与 node_modules 在真正不同的文件系统上时，hardlink 根本不可能，尝试以 `EXDEV` 失败，`auto` 自己就会回退到 copying」；可显式设 copy/clone/clone-or-copy。注意降级只在「链接失败」时发生。
- 来源：https://pnpm.io/settings/node-modules#packageimportmethod
- 含义：跨设备是官方预期内场景（copy 兜底），但要按「每个新装包全量拷贝」预算成本；clone 需要 CoW 文件系统（btrfs 等），overlay/普通卷通常拿不到。

### 1.4 virtual-store-dir：可移出 node_modules，但（无 GVS 时）不能多项目共享

- 结论：virtual store 默认 `node_modules/.pnpm`，是「与 store 链接的目录，项目全部直接/间接依赖都链到这里」；文档支持移出（示例：Windows 长路径选盘符根、或项目根下 `.pnpm` 让栈迹干净）。明确注记：**无 global virtual store 时，virtual store 不能在多个项目间共享，每个项目应有自己的 virtual store**（workspace 根共享除外）。
- 来源：https://pnpm.io/settings/node-modules#virtualstoredir
- 含义：「把 .pnpm 指到某个共享可写目录供多容器/多项目复用」不是该设置的用法；它只做单项目搬家。

### 1.5 移出后的解析行为：symlink 农场按 Node 解析规则工作；历史上有 hoist 边角，12.x 已可移出项目外

- 结论：isolated 布局＝.pnpm 内「真实文件（对 store 的硬链）」＋各级 symlink（直接依赖 symlink 到根 node_modules，传递依赖 symlink 到 .pnpm 内各包的 node_modules）；Node 解析 symlink 到真实路径后向上搜 node_modules，因此布局与 Node 解析算法完全兼容。维护者 zkochan 早期定论：把 virtual store 移出项目目录，包只向上找 node_modules，hoist 的（幽灵）依赖会找不到（该 issue 当年以此关闭）；2026-09 同一 issue 复核：**pnpm 12.5.1 上 `virtualStoreDir: ../shared-store` 可用，node_modules 正常链入**。hoisted 依赖目录（.pnpm/node_modules）位于 virtual store 内部，随 virtual store 一起搬。
- 来源：https://pnpm.io/symlinked-node-modules-structure ；https://github.com/pnpm/pnpm/issues/4819（zkochan 两条相隔 5 年的评语）
- 含义：把 .pnpm 搬出 node_modules 在 12.x 官方可用，但「Node 向上搜 node_modules」这条规则决定了搬家后一切解析都依赖 symlink 指回正确位置——不能只搬一半。

### 1.6 镜像层内预置 store：官方文档实测 overlayfs copy-up 让「硬链」名存实亡

- 结论：官方 docker 指南与 settings 文档有专节：「构建期不可能在容器与宿主文件系统之间创建 reflink/hardlink」；把热 store 烤进镜像层后，对 lower-layer 文件的硬链「成功」但 overlayfs 先把该文件 copy-up 到可写层——实测一次安装 copy-up 36.7k 文件/366MB；「EXDEV 降级帮不上忙，因为跨层硬链不失败」；官方建议该形态下 `packageImportMethod: copy` 通常更快，或改用 BuildKit cache mount（store 是挂载而非层，无此问题）；podman 可构建期挂 btrfs 卷。
- 来源：https://pnpm.io/docker（页首 note 与 caution）；https://pnpm.io/settings/node-modules#packageimportmethod（「A store baked into a container image layer」节）
- 含义：「镜像层里预置 store、运行期容器从中链接」已被官方文档用数据否定（copy-up 把 600M store 拷进可写层）；镜像层只读挂出来当 store 用要走 frozenStore（见 4.5）而非硬链。

---

## 2. 只读场景：node_modules 只读/来自只读挂载时 `pnpm add` 的行为

### 2.1 官方无「只读 node_modules」工作模式；维护者给的路是 modulesDir 搬家＋显式 store-dir

- 结论：issue #9246（标题「pnpm cannot install under a read-only directory」，动机就是 Docker 共享镜像依赖）由 zkochan 关闭定论：「**在 pnpm 12.5.1 上验证：把 `modulesDir` 指到可写目录，项目目录只读时安装可工作**」；报告者实测仍需同时显式设置 store 位置，否则 pnpm 在（只读的）项目根创建探测/lockfile 临时文件失败（`ERR_PNPM_LOCKFILE_WRITE_FILE`，strace 显示写 `.pnpm-lock.yaml.<pid>.tmp` 被拒）。即 pnpm 默认必须写项目目录（lockfile）与 node_modules（顶层 symlink、.bin、.modules.yaml、新包硬链）。
- 来源：https://github.com/pnpm/pnpm/issues/9246
- 含义：「镜像 node_modules 只读挂载＋卷上增量 pnpm add」没有官方直通路径；官方姿势是整个 modules 目录挪到可写处，而非「基座不动、增量另落」。

### 2.2 pnpm 12 安装会把既有 node_modules 搬进 staging：跨文件系统时踩 EXDEV（正是「镜像 node_modules＋卷」组合）

- 结论：pnpm 12（pacquet/Rust 路径）安装开始时 `preserve_modules_dir` 用一次 `fs::rename` 把已有 node_modules 挪到 staging 目录再重建；目录 rename 跨文件系统/跨 overlay 层报 `EXDEV`（overlayfs 目录 rename 无 copy-up 路径）。#14504：GVS 的 `links/` 来自镜像层时装包直接失败（triage 定为 accepted，TS 端 `renameEvenAcrossDevices` 本有 EXDEV→copy 回退，仅 Rust 端缺）；#14758：pnpm 12.0–12.4 在 Docker build 里把已有嵌套 node_modules 搬进 staging 时 EXDEV（11.26 正常，回归）；修复 PR #15508（zkochan，preserve node_modules across filesystems without failing on EXDEV）2026-09-24 已合。
- 来源：https://github.com/pnpm/pnpm/issues/14504 ；https://github.com/pnpm/pnpm/issues/14758 ；https://github.com/pnpm/pnpm/pull/15508
- 含义：即便 node_modules 可写但与安装目标跨设备（卷 vs 镜像层下缘），pnpm 12 的「对账式搬运」也会触发跨设备 rename；修复只进最新 12.x，旧版本是硬错不是降级。

### 2.3 `pnpm add` 是对账式增量，不是「只追加」：会复用既有内容，也会删/搬多余内容

- 结论：官方机制证据链——(a) modules 目录内保留 orphan 包作缓存（`modulesCacheMaxAge` 默认 10080 分钟＝7 天，加速切分支/降级）；(b) `optimisticRepeatInstall` 默认 true：安装前做快检（比对 manifest/lockfile/patch 内容），无变化则整场跳过；(c) store 里旧版本包不自动删（`pnpm store prune` 才清「unreferenced packages」）。结合 2.2 的 staging 搬运语义：pnpm add 走「保留/复用＋补链新增＋按 lockfile 对账（含删多余）」，前提是它对 node_modules 有写控制权。
- 来源：https://pnpm.io/settings/node-modules#modulescachemaxage ；https://pnpm.io/settings/other#optimisticrepeatinstall ；https://pnpm.io/cli/store
- 含义：增量装包本身高效（不重装全树），但「对账」意味着 pnpm 认为自己拥有整个 node_modules 的写权限——与「基座只读」根本冲突。

### 2.4 官方对「共享/不可变 node_modules」的正面替代：enableModulesDir=false ＋ FUSE 挂载（实验）

- 结论：`enableModulesDir` 默认 true；设 false 时「pnpm 不会往 modules 目录写任何文件」，文档明说这是为「modules 目录以 FUSE（用户态文件系统）挂载」的场景准备的，并链接实验 CLI `@pnpm/mount-modules`（现 npm 包 `@pnpm/modules-mounter.daemon`，README：先 `pnpm install --lockfile-only` 把包取进 store，再 `mount-modules` 用 FUSE 挂出 node_modules，出问题用 `unmount`）。
- 来源：https://pnpm.io/settings/node-modules#enablemodulesdir ；https://www.npmjs.com/package/@pnpm/mount-modules ；monorepo 内 README（pnpm11/modules-mounter/daemon/README.md）
- 含义：官方对「pnpm 不写 node_modules」的答案是实验性 FUSE 虚拟挂载（按需从 store 呈现），不是共享只读真树；Linux 专属、实验期。

---

## 3. node-linker 三模式在「node_modules 期望只读＋增量写」约束下的可行性

（注：现行文档中 symlinked 已更名 isolated；取值为 isolated/hoisted/pnp/loaded。）

- **isolated（默认）**：依赖 symlink 自 `node_modules/.pnpm` 的 virtual store 呈现；「真实文件」全部集中在 .pnpm（对 store 的硬链），根 node_modules 只放直接依赖 symlink＋.bin。来源：https://pnpm.io/settings/node-modules#nodelinker ＋ https://pnpm.io/symlinked-node-modules-structure 。含义：写放大最小——新增包只写 .pnpm 新目录＋若干顶层 symlink；与「把 bulk 移出、增量写别处」的意图最贴合，但顶层 symlink/.bin/.modules.yaml 仍必须写进根 node_modules。
- **hoisted**：平铺无 symlink 的 node_modules（npm/Yarn Classic 形态），包真实文件直接铺在 node_modules 各层；官方列举的正当理由是不兼容 symlink 的工具链、serverless 无 symlink、bundledDependencies、--preserve-symlinks。来源同上。含义：新增/更新包要向 node_modules 顶层写真实目录，与只读基座冲突面最大，且失去 store 去重优势的一半（结构去重没了）。
- **pnp**：无 node_modules，Plug'n'Play（Yarn Berry 策略），配套 `symlink: false`。来源同上。含义：彻底消灭 node_modules 只读写 loader/清单文件，理论上「基座只读」问题不存在，但工具生态（含 shadcn 类 CLI）对 pnp 的兼容要单独验证，文档亦仅列为可选项。
- **loaded（pnpm 12 新实验）**：直接从 content-addressable store 加载，仅 materialize 白名单包到 global virtual store。来源同上（nodeLinker.type: loaded，链接 store-loader 文档）。含义：官方正朝「node_modules 不落地」方向试验，但明确 experimental。

---

## 4. 官方对跨容器/跨机器共享 store 的立场与已知模式

### 4.1 store 是信任域：只应在互信主体间共享

- 结论：`storeDir` 文档 important 提示：「pnpm store 只打算在**互信**的用户、作业、进程之间共享；配置共享 storeDir 时须用文件系统权限保护，防止不可信者写入；store 属于 pnpm 信任域（包可能被从中硬链，store 索引 index.db 记录用于校验的哈希）」。CI 文档同样要求只把 store/cache 缓存在可信 job 可写的位置。
- 来源：https://pnpm.io/settings/store#storedir ；https://pnpm.io/continuous-integration
- 含义：多容器共享一个可写 store 首先是信任/权限问题，其次才是技术问题。

### 4.2 共享 store 的并发锁放在 store 外：多容器各自 /tmp 时互不等待

- 结论：pnpm 把协调共享 store 进程的锁放在 per-user 目录（Linux 为 `$XDG_RUNTIME_DIR`，否则 `/tmp`）；「使用同一 store 的进程应看到相同的 `XDG_RUNTIME_DIR`，因为 pnpm 进程只在共享锁目录时才互相等待」。
- 来源：https://pnpm.io/settings/store#storedir
- 含义：多个容器共享同一个卷 store 时，若各自 /tmp/XDG_RUNTIME_DIR 独立，pnpm 之间没有互斥——并发写同一 store 有竞态风险。

### 4.3 CI 官方建议：缓存搬运 store（keyed by lockfile），而非在线共享

- 结论：CI 指南全部示例都是把 store 目录作为缓存制品保存/恢复（key 按 OS＋pnpm-lock.yaml），并明说「缓存 store 并非必需，也不保证更快」；另建议同时缓存 metadata cache（v11.22+ `pnpm cache path`）。
- 来源：https://pnpm.io/continuous-integration
- 含义：官方认可的跨机器 store 复用形态是「整目录缓存搬运」；没有「常驻共享 store 服务」的现行方案。

### 4.4 网络文件系统多机共享 store：storeIndexFormat=files（v12.13+）

- 结论：`storeIndexFormat: files`「用于被多台机器同时使用的网络文件系统上的 store，例如 CI job 共享」：每包一个索引文件、临时文件＋rename 原子落位，免锁；格式属于 store 本体，切换后旧 SQLite 索引里的包不被识别（首次重下）；pnpm v11 只认 index.db。
- 来源：https://pnpm.io/settings/store#storeindexformat
- 含义：若走「多容器共享一个网络/卷 store」，官方给的是 files 索引形态；且 store 布局按 pnpm 大版本演进（v11/v12 互不兼容），共享方必须同版本族。

### 4.5 只读 store 是官方一等场景：frozenStore（Nix/只读 bind mount/OCI 镜像层）

- 结论：`frozenStore` 让 `pnpm install` 面对只读文件系统上的 store 运行（文档原话点名 Nix store、read-only bind mount、**OCI image layer**）：以 immutable 模式打开 store 的 SQLite index.db、压制所有写 store 的代码路径；要求 store 已含所需一切（含生命周期脚本构建产物），配 `--offline --frozen-lockfile`；期间不得有进程改 store；与 --force、pnpr server 互斥；side-effects cache 也不写。#11703 的定论评述进一步确认：「pnpm 提供 --frozen-store 正是为了『store 放进只读 Nix store』这一用例，粒度是整个 store 而非单包」，并指出 pnpm store 本体是全局 CAS（`files/<2-char>/<hash>`，按文件内容寻址）。
- 来源：https://pnpm.io/settings/store#frozenstore ；https://github.com/pnpm/pnpm/issues/11703
- 含义：「镜像层只读挂出 store」官方支持——但只覆盖「按既有 lockfile 离线装」，`pnpm add` 新包必须写 store，只读 store 无法承接增量装包。

### 4.6 store server 已被官方移除

- 结论：`pnpm server`（store server，HTTP 常驻服务）由 zkochan 在 PR #10463（2026-01-15 合并，changeset 标 `pnpm: major`，即随 pnpm 12 移除）删除，原话：「`pnpm server` 很多年前为 Glitch 而造，那家公司已不存在…如果将来重新加，会以插件形式加回」。
- 来源：https://github.com/pnpm/pnpm/pull/10463
- 含义：任何「常驻 server 共享 store 给多容器」的设想没有官方支撑，历史方案已死。

---

## 5. 有没有官方途径「既有 node_modules 不动、新增包落到可写位置」

### 5.1 没有文档化的「只读基座＋可写增量」模式

- 结论：文档中不存在「pnpm 视既有 node_modules 为不可变基座、仅把新增写到别处」的用法；install/add 的语义是拥有并维护整个 modules 目录（见 2.2 staging 搬运、2.1 必写清单）。官方显式反对/未支持的相邻主张：virtual store 不可多项目共享（1.4）；Node 只在祖先 node_modules 里解析依赖（zkochan：#4819「包只向上搜 node_modules 目录」；Node.js 官方解析算法同义）。
- 来源：https://pnpm.io/settings/node-modules#virtualstoredir ；https://github.com/pnpm/pnpm/issues/4819 ；https://nodejs.org/api/modules.html
- 含义：「镜像 node_modules 原样只读复用＋增量另落」在 pnpm 的模型里没有对应开关。

### 5.2 官方工具箱里最接近的拼装件（各有明确代价，均非为该场景设计）

- **virtualStoreDir 移出 node_modules/项目**：文档支持（长路径/栈迹用途）；12.5.1 起移出项目目录可用（#4819 复核）。但顶层 symlink、.bin、`.pnpm/node_modules` 隐藏 hoist 与 modules.yaml 状态仍写根 node_modules——根 node_modules 仍须可写。来源：https://pnpm.io/settings/node-modules#virtualstoredir 。
- **virtualStoreType: global（GVS，实验）**：单个机器级共享 virtual store（默认 `<store>/links/`），各项目 node_modules 只含 symlink（#14504 实测：node_modules 仅写 7.7MB/1669 个 symlink，对比硬链方案 copy-up 366MB）；「目标是未来版本默认开启」但当前实验期；限制：node_modules 仍须可写、ESM 的 hoist 解析靠 NODE_PATH＋`NODE_OPTIONS --import` resolve hook（Node 不为 ESM 尊重 NODE_PATH 是 pnpm 文档自述事实）、CI 冷缓存无益、信任域共享写状态。且 #14504 实测 GVS 在「links/ 来自镜像层」时恰踩 EXDEV（修复 #15508 已合）。来源：https://pnpm.io/global-virtual-store ；https://pnpm.io/settings/node-modules#virtualstoretype ；https://github.com/pnpm/pnpm/issues/14504 。
- **modulesDir 指到可写目录**：zkochan 认证的「项目只读」解法（2.1）；代价：Node 只解析名为 node_modules 的祖先目录，modules 目录改名后脱离标准解析路径（NODE_PATH 仅 CJS 且 pnpm 文档明言 ESM 不认）。
- **enableModulesDir=false ＋ FUSE mount-modules**：pnpm 完全不写 modules 目录，由 FUSE 从 store 呈现（2.4，实验）。
- **virtualStoreOnly / pnpm fetch**：只填 store 不建 importer symlink（Nix 预置、Docker 构建缓存场景），是「预置」而非「增量」工具。来源：https://pnpm.io/settings/node-modules#virtualstoreonly ；https://pnpm.io/cli/fetch 。
- 含义：这些件拼起来最接近「node_modules 大头不落卷」的是 GVS（node_modules 缩成纯 symlink）或 modulesDir/virtualStoreDir 搬家，但每一个都改变了「镜像里那份 node_modules 原样可用」的前提，且没有一件让「基座只读」成立。

---

## 关键结论速览

1. 跨设备（卷 vs 镜像层）不报错：auto 静默降级 copy，每个新包装全量拷贝（1.1/1.3）。
2. 只把卷挂可写时，pnpm 会把 store 自动建进卷内 node_modules/.pnpm-store，必须显式 store-dir（1.2）。
3. 镜像层预置 store 供热链接已被官方数据否定（overlayfs copy-up；1.6）；只读 store 的官方形态是 frozenStore＋offline＋frozen-lockfile，且无法承接 pnpm add（4.5）。
4. virtual store 可移出 node_modules/项目（12.5.1 验证），但不可多项目共享；根 node_modules 仍必写（1.4/1.5/5.2）。
5. pnpm 无只读 node_modules 模式；zkochan 定论的只读项目解法是 modulesDir 搬家＋显式 store-dir（2.1）。
6. pnpm add 是对账式增量：高效复用，但会搬/删既有内容；pnpm 12 的 staging rename 在跨设备上踩 EXDEV（#14504/#14758，修复 #15508）（2.2/2.3）。
7. node-linker 对比：isolated 写放大最小、hoisted 冲突面最大、pvp/loaded 消灭或外移 node_modules 但实验/生态受限（3）。
8. 跨容器共享可写 store：信任域要求＋锁在 store 外（XDG_RUNTIME_DIR/ tmp，多容器互不等待）＋版本族一致；官方在线共享方案（store server）已删（4.1/4.2/4.4/4.6）。
9. 「基座只读＋增量可写」无官方成品；最接近的官方拼装件是 GVS（node_modules 纯 symlink 化，实验期）与 modulesDir/virtualStoreDir 搬家，均改变镜像 node_modules 原样复用的前提（5.1/5.2）。
