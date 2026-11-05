# 预览网关 Origin 收口生产 cutover runbook（#398）

> 读者：执行本次生产生效的运营。目标：#394 的网关收口（`/_next/` 剥 Origin＋响应剥
> X-Frame-Options，ADR-0034）按既有部署流程在生产生效——**存量 workspace 卷/容器/文件
> 零触碰、平台 server/web 容器不动、无停机**（conf 热更 reload）——并用事发 demo 活体
> 验收红→绿留档。
>
> 分工（#398 口径）：部署动作由运营在部署服务器执行；agent 交付物＝本 runbook＋验收
> 探针 [deploy/preview-origin-probe.sh](../../deploy/preview-origin-probe.sh)＋红留档
> （已完成，见 §1）。#395（baseline 模板）与 #397（收口探针）不阻塞本票，随后版次经
> `release.sh` 带走。
>
> 关联：#392（根票，验收记录回帖处）／#394（收口实现＋冒烟）／#396（排练已绿）。
> 服务器事实：AWS 法兰克福 `ubuntu@3.70.172.160`，密钥 `~/ssh/semgravio-eu-1.pem`
> （开发机 Git Bash）；平台部署目录 `~/aiplatform`；infra 单元目录 `~/semgravio-infra`。

## 变更面（就这两样，其余全不动）

| 对象 | 动作 | 通道 |
|---|---|---|
| `~/semgravio-infra/nginx/conf.d/aiplatform.conf` | 换新（多出 `map $uri $preview_origin`） | 门禁 `install-nginx-conf.sh`（`nginx -t` 过才 reload，失败自动回滚） |
| `~/semgravio-infra/nginx/conf.d/aiplatform.assets/` | 换新（gateway-proxy.conf 多出 `proxy_set_header Origin $preview_origin`＋`proxy_hide_header X-Frame-Options`） | 同上（门禁第四参） |

随新 conf 一并生效的 XFO 剥离对浏览器活体无感知面（回归已由 #394 冒烟＋#396 排练覆盖，
不单独验收）。**不做清单**：不 `compose up/down`、不动 `.env`、不碰 `ws-*`/`snap-*` 容器与
卷、不装 #397 的 `preview-gateway` 网络别名（那是下次正式 release 的前置，见部署文档 §3
顺序硬要求，单独做别顺手）。

## 0. 前置核对（agent 已于 2026-11-05 完成留档，运营执行前可复核）

- [x] 现网包 REVISION `a06e5b8`（早于 #394）——现装 conf/assets 中 `preview_origin`
      出现次数为 0（收口未上生产，红态在 conf 层实锤）；
- [x] 排练绿：#396 浏览器头组合探针全过（网关剥 Origin／非 `/_next` 透传，沙箱回显可观测）；
- [ ] 开发机 develop 已含 #394（`git log --oneline -3` 应见 68bbb70 之后的提交）。

## 1. 红留档（部署前必做——reload 后红形态不可再现）

```bash
# 开发机仓库根（服务器解包后 ~/aiplatform/deploy/ 内是同款脚本，也可在服务器跑）
deploy/preview-origin-probe.sh ; echo "exit=$?"   # 无参＝事发 demo 工作区
```

**期望：exit=1（红）**。agent 留档（探针原样输出）：

```
== 预览网关 Origin 收口探针（#398）==
目标：http://376467559789526725.preview.semgravio.com/   时间：2026-11-05T22:13:25+0800
[1] 页面壳（无 Origin）→ 200 ✓ 在服
[2] chunk 定位 → /_next/static/chunks/%5Bturbopack%5D_browser_dev_hmr-client_hmr-client_ts_1w1qmhd._.js
[3] chunk 无 Origin（对照）→ 200
[4] chunk 带预览域 Origin（主断言）→ 403
[5] 页面路由带 Origin（守门·透传不误伤）→ 200
[6] HMR 握手带 Origin（/_next/webpack-hmr）→ 被拒（Unauthorized/403——HMR 断连形态）
结论：红——网关未剥 /_next Origin，Next dev 跨源闸门在场（chunk 403）。
```

退出码语义：0＝绿／1＝红（部署前预期）／2＝探针环境异常（沙箱不在服等，先人工看输出；
含守门变红＝收口误伤透传，立即 §8 回退）／4＝绿但 HMR 握手仍被拒（别当绿交差）。

## 2. 上新 conf＋资产到服务器（只覆盖文件树，不动任何容器）

```bash
# —— 开发机（仓库根，develop）——前置：本 runbook 相关提交已落在 develop（build-bundle
#    按 HEAD 打包且拒脏树，服务器侧探针/§8 回退命令都以探针脚本进包为前提）
deploy/build-bundle.sh          # 末行打印包路径 dist/aiplatform-deploy-<时间戳>.tar.gz
scp -i ~/ssh/semgravio-eu-1.pem dist/aiplatform-deploy-<时间戳>.tar.gz ubuntu@3.70.172.160:

# —— 服务器（文件名用确切时间戳，别用 glob：~/ 有多个历史包）——
ssh -i ~/ssh/semgravio-eu-1.pem ubuntu@3.70.172.160
cd ~/aiplatform && tar -xzf ~/aiplatform-deploy-<时间戳>.tar.gz --strip-components=1
```

校验点（服务器）：

```bash
cat ~/aiplatform/REVISION                                   # 应为含 #394 的 develop 提交
grep -c 'preview_origin' ~/aiplatform/deploy/nginx/aiplatform.conf        # ≥1（map 声明）
grep -c 'preview_origin' ~/aiplatform/deploy/nginx/assets/gateway-proxy.conf  # ≥1（消费点）
```

（解包只覆盖文件树：`.env*` 打包即排除，现网 env 不会被覆盖；运行中的容器都是镜像
产物，不受文件树影响。）

## 3. 备份现装 conf＋资产（回退根基，秒级动作）

```bash
# —— 服务器 ——
B=~/preview-fix-backup-$(date +%F-%H%M); mkdir -p "$B"
cp -a ~/semgravio-infra/nginx/conf.d/aiplatform.conf "$B/"
cp -a ~/semgravio-infra/nginx/conf.d/aiplatform.assets "$B/"
echo "回退根基：$B"
```

## 4. 资产装配＋门禁安装（生效步；内含 nginx -t → reload）

```bash
# —— 服务器 ——
cd ~/semgravio-infra
~/aiplatform/deploy/nginx/assemble-assets.sh ~/aiplatform/deploy/nginx/assets ~/aiplatform
./install-nginx-conf.sh aiplatform \
  ~/aiplatform/deploy/nginx/aiplatform.conf "" ~/aiplatform/deploy/nginx/assets
date +%F'T'%T%z    # 记录 reload 时刻（回帖用）
```

- 门禁行为：写入前查撞车＋基线 `nginx -t`；写入后 `nginx -t` 不过**自动回滚**、不 reload，
  现网存活不受影响；通过则 reload（优雅退出，活跃 HMR ws 断线一次自动重连——预期行为
  不是故障）。
- 校验点：门禁输出 `✓ nginx -t 通过，已 reload：conf.d/aiplatform.conf 生效…资产已装入…`；且

```bash
docker exec semgravio-nginx grep -c 'map \$uri \$preview_origin' /etc/nginx/conf.d/aiplatform.conf
docker exec semgravio-nginx grep -c 'preview_origin' /etc/nginx/conf.d/aiplatform.assets/gateway-proxy.conf
```

两处均 ≥1（conf.d 目录挂载＝热更，reload 后即新行为）。

## 5. 绿复核（同一条探针命令）

```bash
deploy/preview-origin-probe.sh ; echo "exit=$?"    # 开发机或服务器皆可
```

**期望：exit=0（绿）**，逐行核对：`[4]` 主断言 403→**200**；`[5]` 守门仍 **200**（非
`/_next` 透传不误伤——变红＝收口误伤，立即 §8 回退）；`[6]` HMR 未被拒。

## 6. 浏览器活体（AC2/AC3——curl 验不了视觉，必做）

1. 打开平台该项目的 Demo 体验页（事发 demo 工作区 `376467559789526725`）看沙箱窗预览：
   **页面完整渲染、不再卡加载**；DevTools Console 无 chunk 403；Network 里 `/_next/*` 全 200。
2. HMR 抽查：DevTools → Network → WS：`/_next/webpack-hmr` 应为 101 且保持连接、无
   断连重试报错。
3. HMR 活体（AC 原文口径「预览内改动即时可见」）：经平台对话对该 demo 发一句小改动
   （如文案微调），预览 iframe **不经刷新**即时可见。不想触发迭代 run 时的替代弱证据＝
   第 2 条的 WS 常连接态。

## 7. 验收记录回帖 #392

内容：§1 红输出（时间点）＋ §5 绿输出（时间点）＋ §4 reload 时刻＋ §6 浏览器观察
（渲染/HMR/迭代即时可见各一句）；回帖后勾掉 #398 的四条 AC。

## 8. 回退（仅当 §5 绿不达或守门变红）

```bash
# —— 服务器（B＝§3 的备份目录）——
cd ~/semgravio-infra
./install-nginx-conf.sh aiplatform "$B/aiplatform.conf" "" "$B/aiplatform.assets"
~/aiplatform/deploy/preview-origin-probe.sh   # 回到 exit=1 红即回退成功（回到已知态）
```

conf 级回退秒级、无数据面；门禁 `nginx -t` 保证坏配置进不来。回退后红即回到本次变更前
的已知态，问题复盘走 issue。

## 9. 本次不做、随后版次带走

- #395 baseline 模板（dev 镜像 0.14）与 #397 收口探针走网关——经下次正式 `release.sh`；
  **release 前先补 `preview-gateway` 网络别名**（部署文档 §3 顺序硬要求，否则后端收口
  探针全量待期红）。
- 主站/快照路由行为随新 conf 一致变化（同一 server 块族），无单独动作。
