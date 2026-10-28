# Issue tracker：GitHub

本仓库的 issue 和 spec 存放在 GitHub Issues。所有操作使用 `gh` CLI。

## 约定

- **创建 issue**：`gh issue create --title "..." --body "..."`。多行 body 用 heredoc。
- **读取 issue**：`gh issue view <number> --json number,title,body,labels,comments`。
- **列出 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需加 `--label` 和 `--state` 过滤。
- **挂 sub-issue**：`gh issue create --parent <parent> ...`，或事后 `gh issue edit <parent> --add-sub-issue <child>`（`gh` 2.94+）。旧版 `gh`：`gh api --method POST repos/<owner>/<repo>/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>`（database id，见下面 **Blocking**）。sub-issue 不可用时，在 child 正文顶部写 `Part of #<parent>`。
- **Blocking**：用 GitHub **原生 issue dependencies**——UI 可见的权威表示。加边：`gh issue create --blocked-by <n>,<n> ...` 或 `gh issue edit <child> --add-blocked-by <blocker>`（`gh` 2.94+）。旧版 `gh`：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，_不是_ `#number` 也不是 `node_id`）。dependencies 不可用时，退而在 child 正文顶部写 `Blocked by: #<n>, #<n>`。所有 blocker 关闭后 ticket 才算 unblocked。
- **数 open blocker**：`gh api repos/<owner>/<repo>/issues/<n> --jq .issue_dependencies_summary.blocked_by`（只算 open blocker；0 即可开工）。`gh issue view <n> --json blockedBy` 会连已关闭的一起列，需过滤：`--jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`。
- **评论**：`gh issue comment <number> --body "..."`
- **加 / 去标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**：先用 `gh issue comment <number> --body "..."` 发说明，再 `gh issue close <number>`。已关闭的 issue 上 `gh issue close --comment` 会丢弃评论但仍退出 0——别依赖它。

在 clone 内运行时，`gh` 会自动从 `git remote -v` 推断仓库。

**只往用户自己的仓库发 issue。** 绝不发到 `mattpocock/skills`（这些 skill 的来源仓库），除非用户拥有它。在 fork 里 `gh` 可能默认指向上游父仓库，所以每个 session 第一次 `gh issue create` 之前，先用 `gh repo view --json nameWithOwner` 核对目标；不是用户自己的仓库就先和用户确认。

## Pull requests 作为 triage 面

**PRs as a request surface: no.** _（如果本仓库把外部 PR 当 feature request 处理，改为 `yes`；`/triage` 会读这个标志。）_

设为 `yes` 时，PR 使用与 issue 相同的标签和状态，用对应的 `gh pr` 命令：

- **读 PR**：`gh pr view <number>`，再 `gh pr view <number> --comments`（非 TTY 下 `--comments` 只打印评论、不含正文），diff 用 `gh pr diff <number>`。
- **列出待 triage 的外部 PR**：`gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`。
- **评论 / 打标 / 关闭**：`gh pr comment`、`gh pr edit --add-label` / `--remove-label`、`gh pr close`。

GitHub 的 issue 和 PR 共用同一编号空间，`#42` 可能是其中之一——先用 `gh pr view 42` 判断，失败再 `gh issue view 42`。

## 当 skill 说 "publish to the issue tracker"

创建一个 GitHub issue。

## 当 skill 说 "fetch the relevant ticket"

按上面 **读取 issue** 的方式读。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一个单独 issue，其 **child** issue 是各 ticket。

- **Map**：一个标注 `wayfinder:map` 的 issue，正文放 Notes / Decisions-so-far / Fog。`gh issue create --label wayfinder:map`。
- **Child ticket**：以 GitHub sub-issue 形式挂到 map 上（挂法见上面 **挂 sub-issue**）。sub-issues 不可用时，把 child 加进 map 正文的 task list，并在 child 正文顶部写 `Part of #<map>`。标签：`wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）。认领后 ticket assign 给驱动的 dev。
- **Blocking**：见上面 **约定** 里的 **Blocking**。
- **Frontier 查询**：列出 map 的 open children（`gh issue list --state open`，限定到 map 的 sub-issues / task list），去掉有 open blocker（见 **数 open blocker**，或 `Blocked by` 行里有 open issue）或有 assignee 的；按 map 顺序取第一个。
- **Claim**：`gh issue edit <n> --add-assignee @me`——session 的第一次写操作。
- **Resolve**：`gh issue comment <n> --body "<answer>"`，然后 `gh issue close <n>`，最后把 context 指针（gist + 链接）追加到 map 的 Decisions-so-far。
