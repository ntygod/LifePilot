# gh CLI 命令速查

按操作分类。所有命令在不在 git 工作树时必须显式 `--repo <owner>/<repo>`，结构化输出加 `--json` / `--jq`。

## PR 管理

| 场景 | 命令 |
|---|---|
| 列出 PR | `gh pr list --repo <owner>/<repo> --state open --json number,title,author,updatedAt` |
| 看 PR 详情 | `gh pr view <pr> --repo <owner>/<repo> --json number,title,body,state,commits,files` |
| 看变更文件名 | `gh pr diff <pr> --repo <owner>/<repo> --name-only` |
| 看完整 diff | `gh pr diff <pr> --repo <owner>/<repo>` |
| 创建 PR | `gh pr create --repo <owner>/<repo> --title "<title>" --body "<body>" --base <base> --head <head>` |
| 合并 PR | `gh pr merge <pr> --repo <owner>/<repo> --squash` |

合并前必须先 `gh pr checks <pr> --repo <owner>/<repo>` 看 CI 是否全绿。

## Issue 管理

| 场景 | 命令 |
|---|---|
| 列 Issue | `gh issue list --repo <owner>/<repo> --state open --json number,title,assignees,labels` |
| 看 Issue | `gh issue view <issue> --repo <owner>/<repo> --json number,title,body,state,comments` |
| 创建 Issue | `gh issue create --repo <owner>/<repo> --title "<title>" --body "<body>"` |
| 关闭 Issue | `gh issue close <issue> --repo <owner>/<repo>` |
| 加评论 | `gh issue comment <issue> --repo <owner>/<repo> --body "<comment>"` |

## CI / Actions 排查

| 场景 | 命令 |
|---|---|
| 看最近运行 | `gh run list --repo <owner>/<repo> --limit 10 --json databaseId,workflowName,status,conclusion` |
| 看运行详情 | `gh run view <run-id> --repo <owner>/<repo>` |
| 拉失败日志 | `gh run view <run-id> --repo <owner>/<repo> --log-failed` |
| 重跑失败 | `gh run rerun <run-id> --repo <owner>/<repo> --failed` |
| 看 PR 检查 | `gh pr checks <pr> --repo <owner>/<repo>` |

## 代码审查

| 场景 | 命令 |
|---|---|
| 拉变更文件名 | `gh pr diff <pr> --repo <owner>/<repo> --name-only` |
| 拉完整 diff | `gh pr diff <pr> --repo <owner>/<repo>` |
| approve | `gh pr review <pr> --repo <owner>/<repo> --approve --body "<comment>"` |
| 请求改动 | `gh pr review <pr> --repo <owner>/<repo> --request-changes --body "<comment>"` |
| 留 comment | `gh pr review <pr> --repo <owner>/<repo> --comment --body "<comment>"` |

## 远程仓库内容

| 场景 | 命令 |
|---|---|
| 读单文件 | `gh api repos/<owner>/<repo>/contents/<path>?ref=<branch>` |
| 列目录 | `gh api repos/<owner>/<repo>/contents/<dir>?ref=<branch>` |
| 改单文件 | 见 `content-api.md`（必须带 sha） |

## 通用 API 与 GraphQL

| 场景 | 命令 |
|---|---|
| REST 通用 | `gh api <path> --jq '<filter>'` |
| GraphQL | `gh api graphql -f query='<query>' -F <var>=<value>` |
| 时间窗口提交 | 见 `content-api.md` |

## 全局规则

- 禁用 `--paginate`，结果太多就缩条件 / 时间窗口
- 不在当前 git 目录时强制 `--repo <owner>/<repo>`
- 优先 `--json` + `--jq` 取结构化字段，不要拿全量人类可读文本
- 不要默认 `git clone`，能 API 直读就不要拉整库

## 错误处理

| 现象 | 处理 |
|---|---|
| `gh: command not found` / 未认证 | 提示用户安装 gh + `gh auth login`；不要每次操作前主动检查 |
| `API rate limit exceeded` | 改用 GraphQL 合并查询；缩小时间 / 数量范围 |
| PR 合并冲突 | 不在 gh 解决，提示用户本地 `git rebase <base>` 后 `git push --force-with-lease` |
| `Resource not accessible by integration` | token 权限不足，确认 GITHUB_TOKEN scope 含 repo / workflow |
| 输出含 `null` 字段 | `--jq` 过滤前用 `select(.field != null)` |
