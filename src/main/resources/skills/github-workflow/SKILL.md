---
name: github-workflow
description: 当用户要操作 GitHub PR / Issue / CI、代码审查、合并 PR、查看工作流运行、读取或更新远程仓库文件时使用。
version: 2.1.2
metadata:
  zhiwei:
    tags:
      - github
      - pr
      - issue
      - ci
      - code-review
      - gh-cli
      - actions
    suggested_tools:
      - shell.exec
      - web.fetch
      - file.read
      - file.write
    outputs:
      - text
      - file
    requires:
      bins:
        - gh
      env:
        - GITHUB_TOKEN
---

# GitHub 协作指南

`gh` CLI + REST/GraphQL API 管理 GitHub PR / Issue / CI / 代码审查。

## 触发判断
- PR 创建 / 审查 / 合并
- Issue 创建 / 管理 / 关闭
- CI / Actions 状态查 + 失败排查
- 代码审查（看 diff、留 review comment）
- 远程仓库文件查看与编辑（Contents API）

不要触发：

- 纯本地 Git 操作（commit / branch / log）→ `shell.exec` / `shell.exec`
- 代码编写本身 → code-assistant
- 其他平台（Gitee / GitLab / Bitbucket）→ `shell.exec` 调对应 CLI 或 API

## 决策路径

| 操作 | 路径 |
|---|---|
| 本地 commit + push + 创 PR | `shell.exec(command="git add ... && git commit -m '...'")` → `shell.exec(command="git push")` → `gh pr create` |
| 仅 GitHub 侧（看 / 评论 / 合并 PR） | `shell.exec` + `gh pr ...` |
| Issue 创建 / 查询 / 评论 | `shell.exec` + `gh issue ...` |
| CI 状态 + 失败排查 | `shell.exec` + `gh run list / view --log-failed` |
| 代码审查（拉 diff + 留意见） | `gh pr diff` + `gh pr review` |
| 远程文件直改（不 clone） | `shell.exec` + `gh api`（Contents API） |
| 复杂查询（跨仓 / 批量） | `gh api graphql` 或 `web.fetch` GraphQL |

各路径要点：

- **CI 通过才合并**：合 PR 前 `gh pr checks <pr>` 看全绿
- **git 操作走 shell.exec**：所有 git 命令（status/diff/log/commit/push 等）统一用 `shell.exec` 执行
- **Contents API 整体解码**：返回的 Base64 含换行，必须先去空白再整体 base64 解码；逐行解会破坏多字节 UTF-8
- **时间窗口收窄**：`since` / `until` 限定范围；结果太多缩窗口而不是 `--paginate`
- **token 走 env**：`GITHUB_TOKEN` 由系统注入，命令里不写明文


## 输出标准

- 输出 PR/Issue/CI 的编号、链接、状态、关键检查和下一步。
- 代码审查要按严重程度列问题，附文件路径和行号。
- 远程文件变更要说明分支、文件、diff 摘要和验证结果。


## 失败策略

- gh 未登录、token 缺失或权限不足时说明配置缺口。
- 会修改远程状态的操作必须先确认目标仓库、分支和影响。
- CI 或 API 失败时保留错误摘要和可重试命令。

## 详细参考
- gh CLI 命令速查（PR / Issue / CI / 审查）：`{skill_dir}/references/gh-cli-reference.md`
- Contents API 文件更新（Bash / PowerShell）+ 时间范围查询：`{skill_dir}/references/content-api.md`
