# gh CLI 速查

不在 git 工作树时须显式 `--repo <owner>/<repo>`，结构化输出加 `--json`/`--jq`。

## PR

| 场景 | 命令 |
|------|------|
| 列出 | `gh pr list --repo <o/r> --state open --json number,title,author,updatedAt` |
| 详情 | `gh pr view <pr> --repo <o/r> --json number,title,body,state,commits,files` |
| 变更文件 | `gh pr diff <pr> --repo <o/r> --name-only` |
| 创建 | `gh pr create --repo <o/r> --title "..." --body "..." --base <base> --head <head>` |
| 合并 | `gh pr merge <pr> --repo <o/r> --squash`（合并前先 `gh pr checks` 看 CI） |

## Issue

| 场景 | 命令 |
|------|------|
| 列出 | `gh issue list --repo <o/r> --state open --json number,title,assignees,labels` |
| 创建 | `gh issue create --repo <o/r> --title "..." --body "..."` |
| 加评论 | `gh issue comment <issue> --repo <o/r> --body "..."` |

## CI/Actions

| 场景 | 命令 |
|------|------|
| 列出 workflow | `gh workflow list --repo <o/r>` |
| 最近运行 | `gh run list --repo <o/r> --limit 10` |
| 失败日志 | `gh run view <run> --repo <o/r> --log-failed` |
| 时间窗口收窄 | `gh run list --repo <o/r> --created 2026-04-25..2026-04-28` |

## 代码审查

| 场景 | 命令 |
|------|------|
| 拉 diff | `gh pr diff <pr> --repo <o/r>` |
| 审查通过 | `gh pr review <pr> --repo <o/r> --approve --body "..."` |
| 提修改建议 | `gh pr review <pr> --repo <o/r> --request-changes --body "..."` |

## 约束

- 不默认 `git clone`，能 API 直读就不拉整库
- token 走 env `GITHUB_TOKEN`，不写明文
- PR 合并冲突不在 gh 解决，提示用户本地 `git rebase` 后 `git push --force-with-lease`
- 多仓批量操作加 `--limit`，不用 `--paginate`
