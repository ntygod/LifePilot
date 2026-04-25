---
name: gitee
description: 当用户要在 Gitee（码云）上操作仓库、PR、Issue，或向 Gitee 推送代码、查看 Gitee CI 状态时使用。关键词：Gitee、码云、Gitee PR、Gitee Issue、码云仓库、gitee.com。GitHub 操作用 github-workflow，纯本地 Git 操作用 git.query / git.mutate。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
    priority: normal
    tags:
      - gitee
      - 码云
      - pr
      - issue
      - openapi
    suggested_tools:
      - shell.exec
      - web.fetch
      - git.query
      - git.mutate
    requires:
      env:
        - GITEE_TOKEN
---

# Gitee 代码托管指南

通过 Gitee OpenAPI 管理代码仓库、PR 和 Issue。所有远程操作走 `web.fetch`，本地 Git 走 `git.*`。

## 适用场景

- 管理 Gitee 仓库（创建 / 查看 / 设置）
- 创建和管理 Pull Request
- 创建和管理 Issue
- 查看 CI/CD 状态

## 不适用场景

- GitHub 操作 → 用 github-workflow
- 纯本地 Git → 直接用 `git.query` / `git.mutate`
- 代码编写 → 用 code-assistant

## 工作流

1. **准备 Token**：读取 `GITEE_TOKEN` 环境变量，不明文输出
2. **调用 OpenAPI**：`web.fetch` 拼 URL `/api/v5/...`，POST 需 `Content-Type: application/json`，详见参考
3. **本地提交与推送**：`git.query` / `git.mutate` 操作本地仓库，`git push` 用 `shell.exec`
4. **敏感操作**（删库 / 强制推送 / 撤回）需用户二次确认

## 详细参考

- OpenAPI 命令模板（仓库 / Issue / PR / 本地 Git 协作）：`{skill_dir}/references/openapi-reference.md`
</content>
</invoke>