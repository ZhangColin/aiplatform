# 修正轮·仓库题（v0r2d-q3）：构建后开发服务器消失一事的现场证据

- 第 82 轮 `pnpm build` 之后平台起的开发服务器不再响应（app.log 无报错、进程消失）；模型第 84–86 轮自己拉起（kill=0，约 40 秒恢复）。
- app.log：服务器完整日志（两次 Ready，中间没有错误行）。
- ps.txt：核对时仍存的两个生成期僵尸 node 进程。
- next-ls.txt：.next 目录形态（build 与 dev 共存情况）。
- scripts.txt：package.json 的 dev/build/start 脚本。
- 原因未查明；候选＝build 清理 .next 时影响 dev。供底座排查（#590 修订评论「顺带发现」）。
