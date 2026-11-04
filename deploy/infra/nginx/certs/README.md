# certs/——接入服务证书目录

每个接入服务一个目录：`certs/<service>/`（证书＋私钥）。申请/续期走人工 certbot
（现网无 TLS；自动化不在 v1）。conf.d 里 `listen 443 ssl` 的证书路径写
`/etc/nginx/certs/<service>/...`（本目录的容器内挂载点）。

**放入/更新证书的唯一合法通道是门禁脚本的第三参数**（与 conf 一起过 `nginx -t`
并回滚，见上级 README.md「nginx 配置安装」）：

```bash
../install-nginx-conf.sh <服务名> <conf 文件路径> <证书目录>
```
