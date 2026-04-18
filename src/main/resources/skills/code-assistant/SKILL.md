---
id: code-assistant
name: "编码代理"
description: "通过外部编码 CLI（Claude Code、Codex、Pi、OpenCode）完成多文件开发、重构、PR 审查等复杂编码任务。触发词：「写代码」「开发功能」「重构」「代码审查」「PR 审查」「用 Codex」「用 Claude Code」「启动 Claude Code」「启动 Codex」「后台跑 claude」「后台跑 codex」「跑个编码 Agent」「并行审查」「并行开发」。单文件小改直接用 file.write / file.edit，不需要本 Skill。"
version: "2.8.0"
suggested-tools:
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

---

## 适用 / 不适用

- ✅ 多文件开发、跨模块重构、PR 审查、并行任务分发
- ❌ 单文件小改（用 `file.edit`）、仅读代码（用 `file.read`）、跑脚本（用 `shell.exec` 或 `code.execute`）

---

## 支持的 CLI

必须用**非交互模式**（background 下无 PTY，交互模式会卡死），推荐用**结构化输出格式**便于解析事件。

**持久交互模式**：仅 Unix + tmux 可用（走 `shell.process(action=session-*)`）；Windows 下做不到，直接告诉用户换 `-p` 多次调用或 WSL。

| CLI | 非交互命令 | 结构化输出参数 |
|---|---|---|
| Claude Code | `claude -p "任务"` | `--output-format stream-json` |
| Codex | `codex exec "任务"` | `--json` |
| Gemini | `gemini -p "任务"` | `--output-format json` |

---

## 启动前准备

启动前用同步模式快速验证：

```
shell.exec(command="claude --version")      # CLI 是否可用
shell.exec(command="claude /status")         # 是否已登录
```

**未装** → 提示用户 `npm install -g @anthropic-ai/claude-code`，**不自行安装**。
**未登录** → 提示用户手动 `claude login`，**不代替用户走 OAuth**。

Windows 上 claude 还需要 bash，知微会自动注入 `CLAUDE_CODE_GIT_BASH_PATH` 环境变量（在设置页配置一次）。如果报这个错说明用户还没配，提示去设置页填。

**破坏性任务必须在 git worktree 或临时目录**，不要让 Agent 在用户主工作目录自由改动。

---

## 启动与监控单个 Agent

### 启动（推荐模板）

```
shell.exec(
  command="claude -p '任务描述' --output-format stream-json --permission-mode acceptEdits",
  workingDirectory="/path/to/worktree",
  background=true
) → { sessionId: "a3f8c1b2" }
```

启动后**必须立即**调 `shell.process(action=output, sessionId=xxx)` 验证：

- 看到 Claude 的 `{"type":"system","subtype":"init"}` 或 Codex 的 `{"type":"session_configured"}` → 启动成功，进入轮询
- state=FAILED 或 exitCode≠0 → 启动失败，读 stderr 报告用户，**不要进入轮询循环**
- 容忍 10 秒无输出（模型预热延迟），**不要 2 秒没输出就 kill**

### 监控（轮询间隔 5-15 秒）

每轮 `shell.process(action=output, sessionId=xxx)` 后做三件事：

1. **看 `lastResult` 字段**（后端已自动解析 stream-json 的最后一个 `type=result` 事件）：
   - 有值 → 任务已结束，`lastResult.subtype=success` 且 `lastResult.result` 是最终摘要
   - 无值 → 仍在运行，看 `output` 字段的原始 stream-json 提摘要
2. 从原始 stream-json 提**当前动作**（仅在 lastResult 缺失时需要）：
   - `type=assistant` → `message.content[].type=tool_use` 对应 `name`（调用的工具：Read/Write/Bash...）
   - `type=tool_use` 里的 `input` 里典型字段：`file_path` / `command` / `pattern`
3. 识别关键信号：里程碑、错误（ERROR/fatal/permission denied）、需要确认的发问
4. 检查 state → RUNNING 继续、COMPLETED/FAILED 结束

**向用户汇报节奏**：启动一条消息、里程碑更新、异常立即报。**不要每轮轮询都汇报**（胶囊 UI 已显示进度）。

### 完成

- COMPLETED 且 exitCode=0 → 读最终 result 事件 + git diff 看改动
- 失败 / 连续 2-3 轮无输出增量 → 读完整 output 排查，必要时 kill 重启
- 已完成的进程**不需要主动 kill**（30 分钟后自动清理）

---

## 编排多个 Agent

根据任务结构选模式（三选一或组合）。**选哪个 CLI、几个阶段、用几个 Agent，由 LLM 根据任务判断**，skill 不规定具体编排。

### 三种模式

| 模式 | 用在什么场景 | 关键规则 |
|---|---|---|
| **串行** | 前后依赖：A 的产出是 B 的输入（实现→审查、设计→实现、迁移→验证） | 前序 `exitCode=0` 才启动后序；失败就 skip 不硬上 |
| **并行** | 互相独立：多个 issue、多个模块、多方案探索 | 改同一文件必须独立 worktree；改不同文件同目录 OK；每轮 `list` 看全貌 |
| **反馈环** | 迭代改进：审查→修→再审查、测试失败→修→再测 | **必须硬上限 2-3 次**；传摘要不传完整报告；超限停下问用户 |

### Agent 选择原则

- **任一阶段的 CLI 都从"支持的 CLI"表里选**，用户没指定就用可用的里最熟悉的
- **审查阶段推荐和前序用不同 CLI**（多一重视角、减少同一模型盲区）；成本敏感时也可以同 CLI 不同 prompt
- **小任务用便宜模型**：如 `--model claude-haiku-4-5` 切 haiku
- **并发约束**：受 `maxConcurrent=5` 限制，看到 `后台进程数已达上限` 时先清理

### 执行要点

- LLM 的 ReAct 循环本质串行，"并行"靠胶囊 UI 实时展示；每轮扫 output 只读 `lastResult` 或提摘要，**不要把完整 stream-json 读进推理**
- 监控时用 `shell.process(action=list)` 看全貌、对 RUNNING 的逐个 `output`
- 全部 COMPLETED 后汇总；部分失败就失败的单独报；反馈环超限停下问用户

---

## 上下文传递

Agent 间的"交接"遵循两条原则：

1. **优先共享 worktree**：后续 Agent 和前序在同一目录，自己读文件/跑 `git diff`，无需在 prompt 里传改动
2. **必须传摘要时提炼再传**：别把上一个 Agent 的完整 stream-json（几十 KB）塞进下一个 Agent 的 prompt，token 爆炸且信息密度低

---

## 汇总模板

整个编排链路完成后给用户结构化汇报。**每个阶段的标题和 Agent 按实际使用情况填**，不是固定两阶段。

```markdown
## 任务完成摘要

**<阶段 1 名称>**（<用的 CLI>, ~X 分钟）
- 改动文件（git diff --stat）
- 核心改动（从 lastResult 提取）

**<阶段 2 名称>**（<用的 CLI>, ~Y 分钟）
- 关键产出 / 发现问题 / 建议

...（按实际阶段数展开）

**建议行动**
- [ ] merge / 修改 / 重跑 / ...

worktree: <路径>（保留，用户决定是否清理）
```

举例：如果只是"实现一个功能"，单阶段就够（不用套两阶段模板）；如果是"实现 + 审查 + 修复"，三阶段。

---

## 安全红线

### 🚫 `--permission-mode bypassPermissions` / `--full-auto` 的边界

绕过所有权限确认。**三条件同时满足才允许**：
1. 工作目录是 **git worktree** 或临时目录（`/tmp/xxx`、`~/.zhiwei/sandbox/xxx`）
2. 目录不含未提交改动 / 敏感凭据 / `.env`
3. 用户明确授权

**禁止**用于用户主工作目录。审查类任务用 `--permission-mode acceptEdits` 或对应只读模式。

### 🚫 API Key 泄漏

- 只通过 `env` 参数注入，不写入文件
- kill/output 响应不要回显 env 内容

### 🚫 未授权网络操作

如果任务不该装依赖 / 调外部 API，在 prompt 里**显式约束**："不要安装新依赖"、"不要发起网络请求"。

---

## 常见问题速查

| 症状 | 对策 |
|---|---|
| 启动秒退 FAILED | 读 stderr：API key 过期？参数错？CLI 版本过旧？ |
| Windows 下报 `CLAUDE_CODE_GIT_BASH_PATH` 找不到 | 让用户去设置页配置 Bash 路径（知微自动注入 env） |
| Codex 输出 `hit your usage limit` | 配额耗尽，停止编排，建议换 claude 或等配额重置 |
| 长时间 RUNNING 无输出 | 连续 2-3 轮确认后 kill 重试 |
| `[部分输出已被覆盖]` | 轮询太慢，提高频率或 `--output-file` 落盘再读 |
| `后台进程数已达上限` | `action=list` 清理空闲任务 |
| 反馈环超上限 | 停下来问用户 |
