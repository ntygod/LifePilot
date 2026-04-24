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

通过 Gitee OpenAPI 管理代码仓库、PR 和 Issue。

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

### 前置条件

需要配置 Gitee 私人令牌 `GITEE_TOKEN`（在 gitee.com/profile/personal_access_tokens 创建）。

### 查看仓库

```
web.fetch(url="https://gitee.com/api/v5/user/repos?access_token=${GITEE_TOKEN}&type=all&page=1&per_page=20", method="GET")
```

### 创建 Issue

```
web.fetch(
  url="https://gitee.com/api/v5/repos/${owner}/${repo}/issues",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"access_token\": \"${GITEE_TOKEN}\", \"title\": \"标题\", \"body\": \"描述\"}"
)
```

### 创建 Pull Request

```
web.fetch(
  url="https://gitee.com/api/v5/repos/${owner}/${repo}/pulls",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"access_token\": \"${GITEE_TOKEN}\", \"title\": \"PR标题\", \"head\": \"源分支\", \"base\": \"目标分支\", \"body\": \"描述\"}"
)
```

### 本地 Git 操作

只读查询用 `git.query`：
```
git.query(action="status")
git.query(action="log", count=10)
```

写操作用 `git.mutate`：
```
git.mutate(action="commit", message="提交信息", files=["file1.java"])
git.mutate(action="branch", branchAction="create", name="feature-xxx")
```

推送用 `shell.exec`：
```bash
shell.exec(command="git push origin feature-branch")
```

## 规则

- API 频率限制 5000 次/小时
- 创建 PR/Issue 前确认标题和内容
- 删除仓库、强制推送等敏感操作需用户二次确认
- Token 不在日志或输出中暴露

## 常见错误处理

- **Token 无效** → 提示用户检查或重新生成令牌
- **仓库不存在** → 确认 owner/repo 拼写
- **权限不足** → 确认 Token 的权限范围
