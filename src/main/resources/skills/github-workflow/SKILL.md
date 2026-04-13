---
id: github-workflow
name: "GitHub 协作"
description: "GitHub PR/Issue 管理、代码审查与 CI/CD 诊断。用户说「创建 PR」「查看 Issue」「CI 失败了」「合并 PR」「GitHub 上看看」「提交到 GitHub」「代码审查」时使用。Gitee 操作用 gitee，纯本地 Git 操作直接用 git.query / git.mutate。"
version: "2.0.0"
suggested-tools:
  - shell.exec
  - web.fetch
  - git.query
  - git.mutate
  - file.read
  - file.write
---

# GitHub 协作指南

通过 `gh` CLI、REST API 和 GraphQL 管理 GitHub PR、Issue、代码审查和 CI/CD 流程。

## 适用场景

- PR 创建、审查、合并
- Issue 创建和管理
- CI/CD 状态检查和故障排查
- 代码审查
- 远程仓库文件查看和编辑

## 不适用场景

- Gitee 操作 → 用 gitee
- 纯本地 Git 操作 → 直接用 `git.query` / `git.mutate`
- 代码编写 → 用 code-assistant

## 工具选择

| 场景 | 工具 |
|------|------|
| 本地仓库状态/提交/分支 | `git.query` / `git.mutate` |
| GitHub PR/Issue/CI | `shell.exec` + `gh` CLI |
| GitHub API 直接调用 | `web.fetch` |
| 本地文件读取 | `file.read` |

## 工作流

### PR 管理

```bash
shell.exec(command="gh pr list --repo owner/repo --state open --json number,title,author,updatedAt")
shell.exec(command="gh pr view 55 --repo owner/repo --json number,title,body,state,commits,files")
shell.exec(command="gh pr create --repo owner/repo --title \"feat: 新功能\" --body \"变更说明\"")
shell.exec(command="gh pr merge 55 --repo owner/repo --squash")
```

### Issue 管理

```bash
shell.exec(command="gh issue list --repo owner/repo --state open --json number,title,assignees,labels")
shell.exec(command="gh issue create --repo owner/repo --title \"Bug: 问题\" --body \"详情\"")
```

### CI/CD 诊断

```bash
shell.exec(command="gh run list --repo owner/repo --limit 10 --json databaseId,workflowName,status,conclusion")
shell.exec(command="gh run view <run-id> --repo owner/repo --log-failed")
shell.exec(command="gh run rerun <run-id> --repo owner/repo --failed")
```

### 时间范围查询

```bash
shell.exec(command="gh api \"repos/owner/repo/commits?sha=main&since=2026-04-01T00:00:00Z&until=2026-04-08T00:00:00Z&per_page=100\" --jq '.[] | {sha: .sha, message: .commit.message}'")
```

### 代码审查

1. 获取 PR 变更文件：`gh pr diff 55 --repo owner/repo --name-only`
2. 用 `file.read` 读取本地相关代码辅助理解
3. 逐文件审查
4. 提交意见：`gh pr review 55 --repo owner/repo --approve --body "审查通过"`

## 规则

- 禁止使用 `--paginate`
- 禁止默认执行全量 `git clone`
- 时间范围查询必须用 REST API 的 `since` / `until`，结果过多时缩小时间窗口，不翻页拉全量
- 不在当前 git 目录时始终指定 `--repo owner/repo`
- 合并前确认 CI 全部通过
- 优先使用 `--json` / `--jq` 获取结构化输出

## 常见错误处理

- **gh 未安装或未认证** → 仅在命令报错时才诊断，不每次操作前检查
- **API 频率限制** → 减少请求频率，使用 GraphQL 合并查询
- **PR 冲突** → 提示用户本地 rebase 解决
