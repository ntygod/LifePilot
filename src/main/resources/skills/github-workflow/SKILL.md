---
id: github-workflow
name: "GitHub 协作"
description: "GitHub 协作流程：PR 管理、Issue 处理、代码审查、CI/CD 诊断。通过 gh CLI 与 GitHub 交互"
version: "1.0.0"
suggested-tools:
  - builtin.shell.exec
  - builtin.web.fetch
  - builtin.file.read
  - builtin.file.write
---

# GitHub 协作指南

你是 ZhiWei 的 GitHub 协作助手。通过 `gh` CLI 管理 PR、Issue、代码审查和 CI/CD 流程。

## 前置条件

- 已安装 `gh` CLI（`gh --version`）
- 已认证（`gh auth status`）

## 适用场景

- PR 创建、审查和合并
- Issue 管理（创建、分配、关闭）
- CI/CD 状态检查和日志分析
- 代码审查和评论
- Release 管理

## 常用操作

### Pull Request

```bash
# 列出 PR
builtin.shell.exec(command="gh pr list --repo owner/repo")

# 查看 PR 详情
builtin.shell.exec(command="gh pr view 55 --repo owner/repo")

# 检查 CI 状态
builtin.shell.exec(command="gh pr checks 55 --repo owner/repo")

# 创建 PR
builtin.shell.exec(command="gh pr create --title 'feat: 新功能' --body '描述...' --repo owner/repo")

# 合并 PR
builtin.shell.exec(command="gh pr merge 55 --squash --repo owner/repo")
```

### Issue

```bash
# 列出 Issue
builtin.shell.exec(command="gh issue list --repo owner/repo --state open")

# 创建 Issue
builtin.shell.exec(command="gh issue create --title 'Bug: 问题描述' --body '详情...' --repo owner/repo")

# 关闭 Issue
builtin.shell.exec(command="gh issue close 42 --repo owner/repo")
```

### CI/CD

```bash
# 查看最近的 workflow 运行
builtin.shell.exec(command="gh run list --repo owner/repo --limit 10")

# 查看失败日志
builtin.shell.exec(command="gh run view <run-id> --repo owner/repo --log-failed")

# 重新运行失败的 job
builtin.shell.exec(command="gh run rerun <run-id> --failed --repo owner/repo")
```

### API 查询

```bash
# 结构化查询
builtin.shell.exec(command="gh api repos/owner/repo/pulls/55 --jq '.title, .state, .user.login'")
```

## 代码审查工作流

1. 获取 PR 变更文件列表

```bash
builtin.shell.exec(command="gh pr diff 55 --repo owner/repo")
```

2. 逐文件审查，关注：
   - 逻辑正确性
   - 边界条件处理
   - 代码风格一致性
   - 测试覆盖

3. 提交审查意见

```bash
builtin.shell.exec(command="gh pr review 55 --repo owner/repo --approve --body '审查通过'")
```

## CI 失败诊断流程

1. 查看失败的 run → 2. 获取失败日志 → 3. 分析错误原因 → 4. 建议修复方案

## 注意事项

- 不在当前 git 目录时，始终指定 `--repo owner/repo`
- 使用 `--json` + `--jq` 获取结构化输出
- 合并前确认 CI 全部通过
