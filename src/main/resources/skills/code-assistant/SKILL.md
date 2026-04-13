---
id: code-assistant
name: "编码代理"
description: "通过后台编码 Agent 完成多文件开发、重构与审查。用户说「写代码」「开发功能」「重构」「代码审查」「PR 审查」「用 Codex」「用 Claude Code」时使用。简单单行修改直接用 file.write，不需要本 Skill。"
version: "2.0.0"
suggested-tools:
  - shell.exec
  - shell.process
  - file.read
  - file.write
  - file.list
  - file.edit
  - git.query
  - git.mutate
  - code.execute
---

# 编码代理指南

通过后台进程管理外部编码 Agent（Codex CLI、Claude Code、Pi、OpenCode），完成复杂编码任务。

## 适用场景

- 构建新功能或新应用
- PR 代码审查（在临时目录中 spawn）
- 大规模代码重构
- 需要文件探索的迭代式编码

## 不适用场景

- 简单单行修改 → 直接用 `file.write` / `file.edit`
- 仅读取代码 → 用 `file.read`
- 非编码任务 → 使用其他 Skill

## 工作流

### 1. 启动编码 Agent

**Codex CLI：**
```
shell.exec(command="codex exec --full-auto '任务描述'", workingDirectory="/project", background=true, pty=true)
→ 返回 { sessionId: "abc123" }
```

**Claude Code：**
```
shell.exec(command="claude --permission-mode bypassPermissions --print '任务描述'", workingDirectory="/project", background=true)
```

### 2. 监控进度

```
shell.process(action=output, sessionId="abc123")
```

关注：编译错误、测试失败、Agent 请求确认、任务完成信号。

前端可订阅 `GET /api/processes/stream` 获取 SSE 实时推送。

### 3. 交互（按需）

```
shell.process(action=write, sessionId="abc123", input="yes\n")
```

### 4. 完成或终止

```
shell.process(action=output, sessionId="abc123")
shell.process(action=kill, sessionId="abc123")
```

### 并行任务

通过 Git Worktree 实现并行：

```bash
shell.exec(command="git worktree add -b fix/issue-78 /tmp/issue-78 main", workingDirectory="/project")
shell.exec(command="codex exec --full-auto 'Fix issue #78'", workingDirectory="/tmp/issue-78", background=true)
```

查看所有后台进程：`shell.process(action=list)`

### shell.exec 关键参数

| 参数 | 说明 |
|------|------|
| `background=true` | 后台化，返回 sessionId |
| `yieldMs=5000` | 同步等待 5 秒，未结束自动转后台 |
| `pty=true` | 分配伪终端（交互式 TUI 应用需要） |
| `env={...}` | 注入环境变量 |

## 规则

- 启动时发一条简短消息，之后在里程碑、问题、错误、完成时更新
- 不因为"慢"就终止进程，编码任务需要时间
- Agent 未安装时提示用户安装对应 CLI，不自行安装
- 用 `shell.process(action=list)` 管控并发数，必要时先终止空闲进程

## 常见错误处理

- **Agent 未安装** → 提示安装命令（`npm install -g @openai/codex` 等）
- **进程超时** → 用 output 确认是否卡住，必要时 kill 后重试
- **权限错误** → 确认工作目录权限
- **并发限制** → 查看进程列表，终止空闲进程
