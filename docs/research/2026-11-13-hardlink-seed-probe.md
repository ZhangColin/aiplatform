# 工作区卷硬链种子方案——本地 Docker 原型实验报告

- 日期：2026-11-13
- 性质：原型/载荷假设验证（临时资源一律 `p474-` 前缀，实验后已全部清理，见文末清单）
- 结论速览：**①本地容器内不成立（挂载层隔离）；②同挂载内成立（2.3s/零数据增量）；③条件成立（唯一原地串写文件＝`.pnpm-workspace-state-v1.json`）；④成立（symlink 农场正常）。**

## 0. 环境与素材

| 项 | 值 |
|---|---|
| 宿主 | Windows 11 + Docker Desktop（WSL2 后端），内核 6.18.58.1-microsoft-standard-WSL2，15.33GiB |
| 存储驱动 | `overlayfs`（containerd snapshotter，`/var/lib/desktop-containerd/...`） |
| 命名卷落盘 | `/dev/sdd` ext4（1,055,762,868 KB），dockerd ns 路径 `/data/docker/volumes/<name>/_data`；VM 侧视图 `/mnt/docker-desktop-disk/data/docker/volumes/...` |
| 素材镜像 | `aiplatform/dev:0.17`（dde72e9fb1ce，只读使用） |
| 素材内容 | `/opt/baseline`（next 16.3.1 全家桶工程）＋ `/workspace/.pnpm-store`；76,990 文件＋1,727 symlink |
| pnpm | 镜像 CLI 10.34.9；corepack 依 `packageManager` 自动切 **10.28.2**（`.modules.yaml` 的创建者） |
| 关键元数据 | `.modules.yaml`：`storeDir: /workspace/.pnpm-store/v10`、`nodeLinker: isolated`、layoutVersion 5 |
| 现行种子机制 | 镜像 entrypoint `/opt/init-workspace.sh`：卷内无 package.json 时 `cp -a /opt/baseline/. /workspace/`（全量拷贝）——本实验的对照基线 |

**镜像内部硬链事实（E0 意外发现）**：镜像内 `/opt/baseline/node_modules` 与 `/workspace/.pnpm-store` 的文件本是硬链共享（构建期 `--store-dir /workspace/.pnpm-store` 所致）。同一次 `du -sh A B` 调用跨参数去重，才出现「node_modules 662M ＋ store 29M」的假象；两次独立 `cp -a` 落卷后各自实体化为 677,500K ＋ 682,768K ≈ 1.36G。**若生产 golden 用单次 `cp -a` 同时拷两棵树，可保留这层共享（唯一内容 ≈ 691M）。**

## 1. E1 跨卷硬链（假设①）

步骤：建卷 `p474-golden`/`p474-ws`，busybox 双卷互挂，写 `/g/f1` 后 `ln /g/f1 /ws/f1`。

| 实验 | 结果 | 关键证据 |
|---|---|---|
| 容器内 ln（golden rw） | **失败 EXDEV** | `ln: /ws/f1: Invalid cross-device link`；`/g/f1 dev=2096 ino=1795900`，容器 overlay 根 `dev=98` |
| 容器内 ln（golden :ro） | **失败 EXDEV**（同错） | 只读挂载本身写也正确被拒（`Read-only file system`） |
| mountinfo | 两卷**同一 superblock** | `8:48 ext4 /dev/sdd`，bind 自 `/data/docker/volumes/p474-{golden,ws}/_data`，`master:29`；`st_dev` 双侧同为 2096 |
| 单卷内 ln（对照） | 成功 | golden 内、ws 内各 OK |
| **同卷双挂载** ln `/g1/f1 /g2/...` | **失败 EXDEV** | 隔离粒度＝**每挂载**，不是每卷 |
| 跨卷 `cp -al`（GNU cp） | 目录可建、**每个文件硬链 EXDEV**，rc=1 | `cp: cannot create hard link '/ws/demo/a' to '/g/demo/a': Invalid cross-device link` |
| **VM 侧直连 ln**（`wsl -d docker-desktop`，经 `/mnt/docker-desktop-disk/...`） | **成功** | 两路径 dev=2096、同 inode 1795900、nlink=2 |

**结论①**：ext4 superblock 层面跨卷硬链完全合法（VM 侧实证）；阻断发生在 **Docker Desktop 的容器卷挂载层**——同一 superblock、同一 `st_dev`，跨挂载 link 仍 EXDEV、挂载内正常，签名与「每挂载独立 idmapped mount」一致（Docker Desktop 行为，原生 Linux dockerd 的普通 bind mount 无此隔离）。即：**本地（Docker Desktop WSL2）容器内跨命名卷硬链不成立；生产 eu-1 原生 Docker 预期成立但须在 eu-1 实测一次**（一条 ln 命令即可判）。

## 2. E2 种子速度与零增量（假设②，单挂载几何）

跨卷被 ① 挡住后，改为**同卷内**几何验证机理：素材落 `p474-ws` 卷 `/workspace/.seed/`（golden 代理），再 `cp -al /workspace/.seed/. /workspace/`（ws 根）。

| 步骤 | 耗时 | df 增量（used KB） | 备注 |
|---|---|---|---|
| 全量实拷 `cp -a`（现行机制对照） | **10,348 ms** | +1,360,648（50,499,932→51,860,580） | 1.36G 全量落盘 |
| **`cp -al` 硬链种子** | **2,328 ms** | **+30,112**（51,860,576→51,890,688） | ≈2.2%，全部为**目录块/目录 inode**，文件数据零增量 |
| `pnpm add`（见 E3） | — | +712 | 新增字节全落 ws |

- 名义占用：ws 根 `node_modules` 677,500K＋`.pnpm-store` 682,772K（du 全量计价），文件 40,460＋symlink 1,726。
- inode 配对验证：`.modules.yaml`（ino1795962）、`package.json`（ino1795922）、`pnpm-lock.yaml`（ino1838839）、`.pnpm` 内抽样文件（ino1801946）——双侧同 inode、nlink=2；**顶层 symlink 也是同 inode 硬链**（如 `tailwindcss` ino1838835 双侧一致，GNU cp -al 对 symlink 走 linkat）。

**结论②：成立（同挂载内）**——秒级（1.36G→2.3s）、文件数据零增量；唯一真实成本＝目录元数据 ~29M（≈2.2%）。

## 3. E3 pnpm add 与可变元文件清单（假设③，最关键）

前置：ws 根已 `cp -al` 种子；golden（`.seed`）侧打 marker＋全量快照（watch 文件 inode/nlink/mtime、顶层 26 条、`.bin` 12 个、store `projects/` 清单）。
执行：`pnpm add left-pad --store-dir /workspace/.pnpm-store`（cwd=/workspace；corepack 自动用 pnpm 10.28.2）。

**(a) 增量性：成立。** rc=0，宿主墙钟 5,079ms（pnpm 自报 `Done in 4s`），`resolved 714, reused 591, downloaded 1, added 1`——只下载 left-pad 一个包，其余 591 复用，非全量重装；无 UNEXPECTED_STORE、无 store 报错。

**(b) golden 侧被原地串写的既有文件：恰好 1 个**

| 文件 | 证据 | 定性 |
|---|---|---|
| **`node_modules/.pnpm-workspace-state-v1.json`** | 同 inode 1838783、nlink 仍=2、mtime 1794495219→1794502440 | **O_TRUNC 原地改写，golden 被污染——`cp -al` 后必须实拷断链** |
| `node_modules/.modules.yaml` | golden nlink 2→1、大小/mtime 不变；ws 换新 inode 1881558 | rename 语义，天然断链，golden 安全 |
| `node_modules/.pnpm/lock.yaml` | 同上（ws 1881559，225643→225922） | 同上 |
| `package.json` | golden 原封（grep left-pad=0）；ws 新 inode 1881560（1277→1255，pnpm 重排格式） | 同上 |
| `pnpm-lock.yaml` | 同上（ws 1881557） | 同上 |
| `node_modules/.bin/*`（12 个 shim） | 全部换新 inode 1881546–1881556 重生成；golden `.bin` 未动 | 同上 |
| 顶层既有 symlink | 未动（不在 -newer 清单），仅新增 `left-pad`→`.pnpm/left-pad@1.3.0/...` | 无串写 |

golden 总量完全不变：文件 76,991、symlink 1,727、du 1,360,656K 逐字节一致。`find .seed -newer marker` 输出仅上述 state json 一行。

**(c) 新增字节落点：全在 ws 侧。** df used +712K；ws `node_modules` 677,500→677,556K、store 682,772→682,828K；left-pad 的 `.pnpm` 文件（ino1881490, nlink=2）是 ws 内 store↔`.pnpm` 新链，与 golden 无关。另：store `v10/projects/08cccc2d...` 是指向 `../../../../opt/baseline`（镜像构建工程路径）的 24 字节 symlink，add 未新增 project 条目。

**结论③：条件成立**——种子后 `pnpm add` 照常、增量；断链最小集＝`.pnpm-workspace-state-v1.json` 一个文件（607B）。防御性建议连 `.modules.yaml`＋`.pnpm/lock.yaml` 一起实拷（合计 ~260KB，成本可忽略）——rename 语义是 pnpm 10.28.2 在 `add` 下的实测行为，不能担保其它操作/其它工具不原地改写。`package.json`/`pnpm-lock.yaml` 属 ws 私产，无需处理。

## 4. E4 冒烟（假设④）

- `pnpm exec next --version` → **`Next.js v16.3.1`**，rc=0，1,394ms。
- `require("/workspace/node_modules/next/package.json").version` → 16.3.1；left-pad → 1.3.0。

**结论④：成立**——硬链＋断链后 symlink 农场、`.bin` shim、模块解析全部正常。

## 5. 对生产方案的落点

1. **本地 Docker Desktop 无法承载「双命名卷 helper 硬链」**：每挂载隔离使容器内跨卷/跨挂载 link 一律 EXDEV（同 superblock 也不行）。上生产前应在 eu-1 用一次性 helper 容器（busybox 双卷互挂 + 一条 ln）实测——原生 dockerd 的卷是普通 bind，预期通过；若 eu-1 也有隔离，方案整体不成立。
2. 通过 ① 后，②③④ 的机理数字可直接沿用：种子 600M 量级 ≈2s/零数据增量（目录元数据 ~2%），`pnpm add` 增量正常，断链实拷集 ≤3 个文件（~260KB）。
3. 若 eu-1 实测不过，退路是「golden 与工作区同卷」几何（本实验 E2/E3 即按此验证，机理全部成立），代价是 golden 冗余份数＝并发工作区布局策略问题。
4. 附带发现：镜像基座 node_modules 与 store 在镜像内本就硬链共享（单次 du 双参数去重曾致 29M 假读数）；golden 制作若用单次 `cp -a` 同拷两树可保住这层共享（唯一内容 ≈691M 而非 1.36G）。

## 6. 清理清单（已执行，凭证 `/tmp/p474/90-cleanup.txt`）

| 资源 | 处置 |
|---|---|
| 容器 `p474-*` | 无残留（全程 `docker run --rm`；`docker ps -a --filter name=p474` 为空） |
| 卷 `p474-golden` | `docker volume rm` 已删 |
| 卷 `p474-ws` | `docker volume rm` 已删 |
| 核验 | 卷/容器 `grep -c p474`＝0/0；既有 vol-ws-*/pgdata/aiplatform-* 等资源原样未动（16 项与预检一致）；镜像只读使用、无 mvn/无 git |

## 7. 原始日志索引（Git Bash `/tmp/p474/`，全量输出未过 grep）

`00-docker-version/info/images/volumes/existing` 预检；`01-inspect-config/env`；`02-init-script`（现行 cp -a 种子机制）；`03-material-inspect`；`10-e1-hardlink`；`11-e1-diag`（st_dev/挂载对照）；`12-e1-diag2-vm`；`13-e1-diag2-hostpath`；`14-e1-diag3`（同卷双挂载/cp-al/VM 布局）；`15/17-e1-diag5*`；`18-e1-diag5c`（VM 侧 link 成功）；`20-e2-fill`；`21-e2-cpal`；`22-e3-pre`＋`22b-e3-pre-inventories`；`23-e3-add`（完整输出）；`24-e3-post`；`25-e4-next`；`26-e4-require`；`90-cleanup`。
