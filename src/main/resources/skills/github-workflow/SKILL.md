---
name: github-workflow
description: 当用户要操作 GitHub PR / Issue / CI、代码审查、合并 PR、查看工作流运行、读取或更新远程仓库文件时使用。关键词：GitHub、PR、pull request、issue、CI、gh cli、代码审查、合并、工作流。纯本地 Git 操作直接用 git.query / git.mutate，不进本 Skill；其他平台（Gitee/GitLab）走 shell.exec 调用对应 CLI。
version: 2.0.0
metadata:
  zhiwei:
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
- 远程仓库文件查看和编辑（Contents API）

## 不适用场景

- 纯本地 Git 操作（commit/branch/log）→ 直接用 `git.query` / `git.mutate`
- 代码编写 → 用 code-assistant
- 其他平台（Gitee/GitLab/Bitbucket）→ `shell.exec` 调用对应 CLI 或 API

## 工作流

1. **选工具**：本地仓库用 `git.*`；GitHub 侧用 `shell.exec + gh`；API 直调用 `web.fetch`
2. **执行操作**：PR / Issue / CI / 代码审查，命令模板见参考
3. **远程文件更新**：Contents API 返回的 Base64 含换行符，必须去空白后整体解码；逐行解码会造成多字节 UTF-8 乱码
4. **时间范围查询**：REST API 用 `since` / `until`，结果过多时缩小时间窗口，不翻页拉全量
5. **合并前**：确认 CI 全部通过

## 详细参考

- gh CLI 命令速查（PR / Issue / CI / 审查）：`{skill_dir}/references/gh-cli-reference.md`
- Contents API 文件更新（Bash / PowerShell）+ 时间范围查询：`{skill_dir}/references/content-api.md`
</content>
</invoke>