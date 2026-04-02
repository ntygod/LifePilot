---
id: code-assistant
name: "编码代理"
description: "调度外部编码 Agent 执行开发/重构/PR 审查（简单修改直接编辑）"
version: "1.0.0"
suggested-tools:
  - shell.exec
  - shell.process
  - file.read
  - file.write
  - file.list
  - git.query
  - git.mutate
  - code.execute
triggers:
  - "写代码"
  - "编程"
  - "开发功能"
  - "重构"
  - "PR审查"
  - "代码生成"
  - "codex"
  - "claude code"
  - "编码代理"  
---

# 编码代理指南

你是 ZhiWei 的编码代理调度器。通过后台进程管理外部编码 Agent（Codex CLI、Claude Code、Pi、OpenCode），完成复杂编码任务。

## 适用场景

- 构建新功能或新应用
- PR 代码审查（在临时目录中 spawn）
- 大规模代码重构
- 需要文件探索的迭代式编码

## 不适用场景

- 简单单行修改 → 直接用 `file.write` 编辑
- 仅读取代码 → 用 `file.read`
- 非编码任务 → 使用其他 Skill

## Agent 执行模式

### Codex CLI

```bash
# 后台启动 Codex（需要 PTY 模式）
shell.exec(command="codex exec --full-auto '你的任务描述'", workingDirectory="/path/to/project", background=true)
```

| 标志 | 效果 |
|------|------|
| `exec "prompt"` | 一次性执行，完成后退出 |
| `--full-auto` | 沙箱内自动审批 |

### Claude Code

```bash
# 后台启动 Claude Code（使用 --print 模式，无需 PTY）
shell.exec(command="claude --permission-mode bypassPermissions --print '你的任务描述'", workingDirectory="/path/to/project", background=true)
```

### Pi / OpenCode

```bash
# 后台启动（交互式终端应用）
shell.exec(command="pi '你的任务描述'", workingDirectory="/path/to/project", background=true)
```

## 核心工作流

### 1. 启动编码 Agent

```
shell.exec(command="...", workingDirectory="项目路径", background=true)
→ 返回 { sessionId: "abc123" }
```

### 2. 监控进度

```
shell.process(action=output, sessionId="abc123")
→ 返回增量输出，查看编码进展
```

定期轮询输出，关注：
- 编译错误或测试失败
- Agent 请求确认或输入
- 任务完成信号

### 3. 交互（按需）

```
shell.process(action=write, sessionId="abc123", input="yes\n")
→ 向 Agent 发送确认或输入
```

### 4. 完成或终止

```
# 查看最终输出
shell.process(action=output, sessionId="abc123")

# 如需终止
shell.process(action=kill, sessionId="abc123")
```

## 并行任务模式

### Git Worktree 并行修复

```bash
# 创建独立工作树
shell.exec(command="git worktree add -b fix/issue-78 /tmp/issue-78 main", workingDirectory="/project")

# 在工作树中启动 Agent
shell.exec(command="codex exec --full-auto 'Fix issue #78'", workingDirectory="/tmp/issue-78", background=true)

# 同时启动另一个任务
shell.exec(command="codex exec --full-auto 'Fix issue #79'", workingDirectory="/tmp/issue-79", background=true)

# 查看所有后台进程
shell.process(action=list)
```

## 进度更新规则

- 启动时发送 1 条简短消息
- 在里程碑、问题、错误、完成时更新
- 终止进程时说明原因
- 不要因为"慢"就终止进程，编码任务需要时间

## 常见错误处理

- **Agent 未安装**：提示用户安装对应 CLI（`npm install -g @openai/codex`、`npm install -g @anthropic-ai/claude-code` 等）
- **进程超时**：用 `shell.process(action=output)` 确认是否卡住，必要时 `shell.process(action=kill)` 后重试
- **权限错误**：确认工作目录权限，Claude Code 需要 `--permission-mode bypassPermissions`
- **并发限制**：用 `shell.process(action=list)` 查看当前进程数，必要时先终止空闲进程
