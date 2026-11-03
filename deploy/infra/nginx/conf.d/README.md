# conf.d/——接入服务配置目录

每个接入服务恰好一个文件：`conf.d/<service>.conf`，只写自己名下的文件。
**不要手工放文件**——必须经 infra 门禁脚本安装（语法校验通过才 reload、失败自动回滚、
server_name 冲突即拒），见上级目录 README.md「接入契约」。
