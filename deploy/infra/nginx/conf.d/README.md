# conf.d/——接入服务配置目录

每个接入服务恰好一个文件：`conf.d/<service>.conf`，只写自己名下的文件。
**不要手工放文件**——安装/更新唯一合法通道是 infra 门禁脚本（用法与裁决语义见
上级 README.md「nginx 配置安装」）：

```bash
../install-nginx-conf.sh <服务名> <conf 文件路径>
```

门禁语义：server_name 与既有其它服务配置撞车即拒（写文件前）、`nginx -t` 通过才
reload、失败自动回滚你刚写的文件且 nginx 存活不受影响。
