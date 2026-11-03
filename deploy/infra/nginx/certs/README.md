# certs/——接入服务证书目录

每个接入服务一个目录：`certs/<service>/`（证书＋私钥）。申请/续期走人工 certbot
（现网无 TLS；自动化不在 v1）。conf.d 里 `listen 443 ssl` 的证书路径写
`/etc/nginx/certs/<service>/...`（本目录的容器内挂载点）。
