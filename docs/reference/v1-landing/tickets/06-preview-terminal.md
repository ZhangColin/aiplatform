## Parent

#36 · v1 平台完整落地（spec）

## What to build

主面板补两个 tab：预览（复活死代码 PreviewPanel，iframe sandbox 三允许不给 same-origin + 手动刷新 + 独立浏览器页打开，`GET projects/{id}/preview`）+ 终端（命令输入 → `POST workspaces/{workspaceId}/exec` → stdout / stderr / exitCode，workspaceId 取自 project.workspaceId）。非 0 退出码是命令失败非环境故障，如实呈现。

## Acceptance criteria

- [ ] 预览 tab 呈现产物 iframe，手动刷新 + 独立打开可用
- [ ] 终端 tab 执行命令显示 stdout / stderr / exitCode，非 0 退出码如实呈现
- [ ] exec 载荷 + 结果归化有纯逻辑单测

## Blocked by

- #37 · 工作台三栏壳（预览 / 终端是主面板 tabs，需壳就位）
