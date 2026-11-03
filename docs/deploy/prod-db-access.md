# 生产库远程访问（本地 dbx 类客户端 × SSH 隧道）

> 读者：隔几个月要查生产数据的自己。2026-11-03 首配走通（c56cb1b 发布后），两种姿势都验证过。
> 拓扑前提：生产 PG（`deploy/infra.compose.yml`，pgvector/pg16）只在 webnet 内网，另绑**宿主回环 `127.0.0.1:5432`**——公网与安全组均不可达（安全组永不放行 5432，这是部署文档 §2 的铁律），唯一入口是 SSH（22 口）。

## 0. 连接要素（两式通用）

| 要素 | 值 | 备注 |
|---|---|---|
| 跳板 | `ubuntu@3.70.172.160` | AWS 法兰克福 semgravio-eu-1 |
| 私钥 | `C:\Users\zhang\ssh\semgravio-eu-1.pem` | Git Bash 里即 `~/ssh/semgravio-eu-1.pem` |
| 库/用户 | `aiplatform` / `aiplatform` | |
| 密码 | 服务器 `~/aiplatform/deploy/.env` 的 `POSTGRES_PASSWORD` | 取法见下；与 `aiplatform-server/.env.production` 同值 |
| 服务器侧端口 | `localhost:5432`（回环） | 隧道远端固定指这里 |

取密码：

```bash
ssh -i ~/ssh/semgravio-eu-1.pem ubuntu@3.70.172.160 "grep POSTGRES_PASSWORD ~/aiplatform/deploy/.env"
```

## 1. 方式 A：客户端原生 SSH 隧道（推荐）

客户端自己先 SSH 上服务器再从那头连 localhost:5432，不用开终端窗口。DBeaver / DataGrip / DbGate 均支持，pem（OpenSSH 格式）直接选文件、passphrase 留空。

主连接（General/Main，选 PostgreSQL 类型）：

| 字段 | 值 |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `aiplatform` |
| Username | `aiplatform` |
| Password | §0 取到的值 |

SSH 标签页：

| 字段 | 值 |
|---|---|
| Use SSH Tunnel | ✓ |
| Host / Port | `3.70.172.160` / `22` |
| User Name | `ubuntu` |
| 认证 | Public Key，Private Key 选 pem 文件 |

**易错**：host/port 是「服务器视角」——填 `5432` 不是 `15432`（15432 只属于方式 B 的本地端口）。

## 2. 方式 B：手动隧道 + 客户端纯直连

客户端不支持 SSH（或想用 psql/脚本直连）时用。先开个终端窗口挂着：

```bash
ssh -i ~/ssh/semgravio-eu-1.pem -N -L 15432:localhost:5432 ubuntu@3.70.172.160
```

窗口存活＝隧道存活（`-N` 不落 shell）。客户端建**普通直连**（不勾任何 SSH）：`localhost:15432`，库/用户/密码同 §0。

本地端口选 15432 是因为 5432 被 dev 栈的 `aiplatform-pg`（docker-compose.windows.yml）常年占用。

## 3. 排障速查

- 连不上先分层：服务器侧回环在不在 → `ssh ... "ss -tln | grep 5432"`（应见 `127.0.0.1:5432`）；本地隧道通不通 → PowerShell `Test-NetConnection localhost -Port 15432`。
- 方式 B 报 connection refused：九成是隧道窗口没了。
- 个别客户端只认 PuTTY `.ppk`：转一份 `puttygen semgravio-eu-1.pem -o semgravio-eu-1.ppk`（主流三家不用）。
- pgvector 镜像默认 pg_hba 允许任意来源密码认证，隧道进来即通，无需改 PG 配置。

## 4. 操作口径

- 这是**生产库**：查数随意，写操作慎重（改前先 `deploy/server.sh dump` 拍备份）。
- 该通道同时承载 ADR-0031 的换 key 直写库路径（apiKey 正本在 `prj_model_providers`）。
- 服务器侧绑定丢了（比如有人动过 compose）→ 对照 `deploy/infra.compose.yml` 的 `ports: "127.0.0.1:5432:5432"` 找回。
