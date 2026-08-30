# Issue tracker：GitHub

本仓库的 issue 和 spec 存放在 GitHub Issues。所有操作使用 `gh` CLI。

## 约定

- **创建 issue**：`gh issue create --title "..." --body "..."`。多行 body 用 heredoc。
- **读取 issue**：`gh issue view <number> --comments`，用 `jq` 过滤评论并同时取标签。
- **列出 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需加 `--label` 和 `--state` 过滤。
- **评论**：`gh issue comment <number> --body "..."`
- **加 / 去标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**：`gh issue close <number> --comment "..."`

在 clone 内运行时，`gh` 会自动从 `git remote -v` 推断仓库。

## Pull requests 作为 triage 面

**PRs as a request surface: no.** _（如果本仓库把外部 PR 当 feature request 处理，改为 `yes`；`/triage` 会读这个标志。）_

设为 `yes` 时，PR 使用与 issue 相同的标签和状态，用对应的 `gh pr` 命令：

- **读 PR**：`gh pr view <number> --comments`；diff 用 `gh pr diff <number>`。
- **列出待 triage 的外部 PR**：`gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`，只保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的（丢弃 `OWNER` / `MEMBER` / `COLLABORATOR`）。
- **评论 / 打标 / 关闭**：`gh pr comment`、`gh pr edit --add-label` / `--remove-label`、`gh pr close`。

GitHub 的 issue 和 PR 共用同一编号空间，`#42` 可能是其中之一——先用 `gh pr view 42` 判断，失败再 `gh issue view 42`。

## 当 skill 说 "publish to the issue tracker"

创建一个 GitHub issue。

## 当 skill 说 "fetch the relevant ticket"

运行 `gh issue view <number> --comments`。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一个单独 issue，其 **child** issue 是各 ticket。

- **Map**：一个标注 `wayfinder:map` 的 issue，正文放 Notes / Decisions-so-far / Fog。`gh issue create --label wayfinder:map`。
- **Child ticket**：以 GitHub sub-issue 形式挂到 map 上（对 sub-issues 端点调 `gh api`）。sub-issues 不可用时，把 child 加进 map 正文的 task list，并在 child 正文顶部写 `Part of #<map>`。标签：`wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）。认领后 ticket assign 给驱动的 dev。
- **Blocking**：用 GitHub **原生 issue dependencies**——UI 可见的权威表示。加边：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，_不是_ `#number` 也不是 `node_id`）。GitHub 会在 `issue_dependencies_summary.blocked_by` 里报告（只算 open blocker——即活门）。dependencies 不可用时，退而在 child 正文顶部写 `Blocked by: #<n>, #<n>`。所有 blocker 关闭后 ticket 才算 unblocked。
- **Frontier 查询**：列出 map 的 open children（`gh issue list --state open`，限定到 map 的 sub-issues / task list），去掉有 open blocker（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行里有 open issue）或有 assignee 的；按 map 顺序取第一个。
- **Claim**：`gh issue edit <n> --add-assignee @me`——session 的第一次写操作。
- **Resolve**：`gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，最后把 context 指针（gist + 链接）追加到 map 的 Decisions-so-far。
