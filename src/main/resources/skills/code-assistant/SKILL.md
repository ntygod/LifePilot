---
name: code-assistant
description: 当用户要通过外部编码 CLI（Claude Code / Codex / Gemini）完成多文件开发、跨模块重构、PR 审查、并行任务分发等需要后台 Agent 执行的复杂编码任务时使用。关键词：写代码、开发功能、重构、代码审查、PR 审查、后台跑、编码 Agent、并行审查、并行开发、Claude Code、Codex。单文件小改直接用 file.write/file.edit，仅读代码用 file.read，跑脚本用 shell.exec 或 code.execute。
version: 2.8.0
metadata:
  zhiwei:
    category: automation
    priority: normal
    tags:
      - code
      - cli
      - claude-code
      - codex
      - refactor
      - code-review
      - orchestration
    suggested_tools:
      - shell.exec
      - shell.process
      - file.read
      - file.write
      - file.list
      - file.edit
      - git.query
      - git.mutate
---

# 编码代理指南

知微作为调度层，启动 Claude Code / Codex 等外部 CLI 作为子 Agent，后台执行复杂编码任务，通过 `shell.process` 监控进度、收集产出，最后汇总交付。

## 适用场景

- 多文件开发、跨模块重构
- PR 审查（和实现使用不同 CLI 可互相纠偏）
- 并行任务分发（多个 issue、多个模块、多方案探索）
- 串行编排（实现→审查、设计→实现、迁移→验证）
- 反馈环迭代（审查→修→再审查）

## 不适用场景

- 单文件小改 → 用 `file.edit`
- 仅读代码 → 用 `file.read`
- 跑脚本 → 用 `shell.exec` 或 `code.execute`

## 工作流

### 启动前准备

用同步模式快速验证 CLI：

```
shell.exec(command="claude --version")
shell.exec(command="claude /status")
```

**未装** → 提示用户 `npm install -g @anthropic-ai/claude-code`，**不自行安装**。
**未登录** → 提示用户手动 `claude login`，**不代替用户走 OAuth**。

Windows 上 claude 还需要 bash，知微会自动注入 `CLAUDE_CODE_GIT_BASH_PATH`（在设置页配置一次）。报错提示用户去设置页填。

**破坏性任务必须在 git worktree 或临时目录**，不要让 Agent 在用户主工作目录自由改动。

### 启动单个 Agent（推荐模板）

```
shell.exec(
  command="claude -p '任务描述' --output-format stream-json --permission-mode acceptEdits",
  workingDirectory="/path/to/worktree",
  background=true
) → { sessionId: "a3f8c1b2" }
```

启动后**立即**调 `shell.process(action=output, sessionId=xxx)` 验证：

- 看到 `{"type":"system","subtype":"init"}` 或 Codex 的 `{"type":"session_configured"}` → 启动成功，进入轮询
- state=FAILED 或 exitCode≠0 → 启动失败，读 stderr 报告用户，**不要进入轮询循环**
- 容忍 10 秒无输出（模型预热延迟），**不要 2 秒没输出就 kill**

### 监控（轮询间隔 5-15 秒）

每轮 `shell.process(action=output, sessionId=xxx)` 后：

1. 看 `lastResult` 字段（后端已解析 stream-json 最后一个 `type=result` 事件）。有值 → 结束；无值 → 仍在运行
2. 从原始 stream-json 提**当前动作**：`type=assistant` → `message.content[].type=tool_use` 对应 `name`（Read/Write/Bash...）
3. 识别关键信号：里程碑、错误（ERROR/fatal/permission denied）、需要确认的发问
4. 检查 state → RUNNING 继续、COMPLETED/FAILED 结束

**向用户汇报节奏**：启动一条消息、里程碑更新、异常立即报。**不要每轮轮询都汇报**。

### 完成

- COMPLETED 且 exitCode=0 → 读最终 result 事件 + `git diff` 看改动
- 失败 / 连续 2-3 轮无输出增量 → 读完整 output 排查，必要时 kill 重启
- 已完成的进程不需要主动 kill（30 分钟后自动清理）

### 编排多个 Agent（三种模式）

| 模式 | 场景 | 关键规则 |
|---|---|---|
| 串行 | 前后依赖（实现→审查） | 前序 `exitCode=0` 才启动后序；失败就 skip |
| 并行 | 互相独立（多 issue / 多方案） | 改同一文件必须独立 worktree；每轮 `list` 看全貌 |
| 反馈环 | 迭代改进（审查→修→再审查） | **必须硬上限 2-3 次**；传摘要不传完整报告；超限停下问用户 |

详细的 CLI 选择原则、上下文传递、汇总模板、安全红线、常见问题速查：参见 {skill_dir}/references/orchestration.md

## 规则

- LLM 的 ReAct 循环本质串行，"并行"靠胶囊 UI 实时展示；每轮扫 output 只读 `lastResult` 或提摘要，**不要把完整 stream-json 读进推理**
- 全部 COMPLETED 后汇总；部分失败就失败的单独报；反馈环超限停下问用户
- **`--permission-mode bypassPermissions` / `--full-auto` 仅在三条件同时满足才允许**：目录是 git worktree 或临时目录；目录不含未提交改动 / 敏感凭据 / `.env`；用户明确授权
- API Key 只通过 `env` 参数注入，不写入文件；kill/output 响应不回显 env 内容
- 不该装依赖 / 调外部 API 的任务，在 prompt 里**显式约束**："不要安装新依赖"、"不要发起网络请求"
