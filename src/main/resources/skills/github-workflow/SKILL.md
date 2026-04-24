---
name: github-workflow
description: 当用户要操作 GitHub PR / Issue / CI、代码审查、合并 PR、查看工作流运行、读取或更新远程仓库文件时使用。关键词：GitHub、PR、pull request、issue、CI、gh cli、代码审查、合并、工作流。Gitee 操作用 gitee，纯本地 Git 操作直接用 git.query / git.mutate，不进本 Skill。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
    priority: normal
    tags:
      - github
      - pr
      - issue
      - ci
      - code-review
      - gh-cli
    suggested_tools:
      - shell.exec
      - web.fetch
      - git.query
      - git.mutate
      - file.read
      - file.write
    requires:
      bins:
        - gh
      env:
        - GITHUB_TOKEN
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

## 工作流

### 工具选择

| 场景 | 工具 |
|------|------|
| 本地仓库状态/提交/分支 | `git.query` / `git.mutate` |
| GitHub PR/Issue/CI | `shell.exec` + `gh` CLI |
| GitHub API 直接调用 | `web.fetch` |
| 本地文件读取 | `file.read` |

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

### 代码审查

1. 获取 PR 变更文件：`gh pr diff 55 --repo owner/repo --name-only`
2. 用 `file.read` 读取本地相关代码辅助理解
3. 逐文件审查
4. 提交意见：`gh pr review 55 --repo owner/repo --approve --body "审查通过"`

## 规则

- 禁止使用 `--paginate`
- 禁止默认执行全量 `git clone`
- 时间范围查询必须用 REST API 的 `since` / `until`，结果过多时缩小时间窗口
- 不在当前 git 目录时始终指定 `--repo owner/repo`
- 合并前确认 CI 全部通过
- 优先使用 `--json` / `--jq` 获取结构化输出
- GitHub Contents API 返回的 Base64 含换行符，**禁止逐行解码**，必须拼接去除空白后整体解码（否则多字节 UTF-8 会乱码）

## 详细参考

- Base64 文件更新（Bash / PowerShell 示例）：参见 {skill_dir}/references/content-api.md
- 时间范围查询与常见错误：参见 {skill_dir}/references/content-api.md

## 常见错误处理

- **gh 未安装或未认证** → 仅在命令报错时才诊断，不每次操作前检查
- **API 频率限制** → 减少请求频率，使用 GraphQL 合并查询
- **PR 冲突** → 提示用户本地 rebase 解决
