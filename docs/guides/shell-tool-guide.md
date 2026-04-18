# 知微 Shell 工具使用指南

> 本文档面向知微（ZhiWei）的 Skill 作者和工具集成开发者，介绍 `shell.exec` 和 `shell.process` 两个内置工具的完整用法、设计意图和最佳实践。
> 如果你正在为 LLM 编写需要执行系统命令的 Skill，本文档是必读。

---

## 目录

- [工具概览](#工具概览)
- [快速入门](#快速入门)
- [shell.exec — 命令执行](#shellexec--命令执行)
- [shell.process — 后台进程管理](#shellprocess--后台进程管理)
- [shell.process — 持久会话（tmux）](#shellprocess--持久会话tmux)
- [安全与权限](#安全与权限)
- [跨平台差异](#跨平台差异)
- [配置参考](#配置参考)
- [常见陷阱与最佳实践](#常见陷阱与最佳实践)
- [为 Skill 编写提示词参考](#为-skill-编写提示词参考)

---

## 工具概览

知微将 Shell 相关能力拆成两个工具（参考 OpenClaw 设计）：

| 工具 | 职责 | 风险等级 |
|---|---|---|
| `shell.exec` | 启动一次命令执行，支持同步 / 后台 / yieldMs 三种模式 | HIGH |
| `shell.process` | 管理已启动的后台进程和持久会话（list / output / write / kill / session-*） | MEDIUM |

设计上的边界：
- `shell.exec` 只管"启动"，启动后的交互（看输出、发信号、杀进程）全部走 `shell.process`
- `shell.process` 下面又分两个子域：**后台进程**（action: `list` / `output` / `write` / `kill`）和 **持久会话**（action: `session-*`，仅 Unix 环境有 tmux 时可用）

---

## 快速入门

### 场景一：执行一次性命令，等待结果

```json
{
  "tool": "shell.exec",
  "args": {
    "command": "git status"
  }
}
```

> 推荐默认不传 `workingDirectory`，由知微使用用户配置的默认工作目录。仅在明确需要其他目录时才显式传绝对路径。

返回结构（成功）：
```json
{
  "exitCode": 0,
  "stdout": "...",
  "stderr": "",
  "effectiveWorkingDirectory": "/Users/me/.zhiwei/workspace"
}
```

`effectiveWorkingDirectory` 是**实际使用的工作目录**，所有 success 响应都会带这个字段。如果 LLM 传入的 `workingDirectory` 被判定为无效并回退，还会额外返回 `workingDirectoryWarning`（见下文"工作目录硬控制"一节）。

### 场景二：启动长跑服务

```json
// 启动
{ "tool": "shell.exec", "args": { "command": "npm run dev", "background": true } }
→ { "sessionId": "a3f8c1b2" }

// 查看输出
{ "tool": "shell.process", "args": { "action": "output", "sessionId": "a3f8c1b2" } }

// 结束
{ "tool": "shell.process", "args": { "action": "kill", "sessionId": "a3f8c1b2" } }
```

### 场景三：命令快慢不可预测

```json
{
  "tool": "shell.exec",
  "args": { "command": "./deploy.sh", "yieldMs": 3000 }
}
```

3 秒内完成就返同步结果；超过 3 秒自动转后台，返 sessionId 继续后续交互。

---

## shell.exec — 命令执行

### 参数一览

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `command` | string | ✅ | — | 要执行的 Shell 命令。Windows 下 stderr 可能以 PowerShell CLIXML 格式返回（以 `#< CLIXML` 开头的 XML），这是 stderr 而非 stdout |
| `workingDirectory` | string | ❌ | 用户配置的默认工作目录 | **推荐默认不传**。若传，必须是绝对路径；传 `/`、`C:\`、相对路径、`.` 等会被硬回退（见下文） |
| `timeoutSeconds` | integer | ❌ | 120 | 同步模式超时（秒），超时后进程被强杀 |
| `background` | boolean | ❌ | false | 立即后台执行 |
| `yieldMs` | integer | ❌ | -1（不启用） | 同步等待毫秒数（上限 120000），超时转后台 |
| `pty` | boolean | ❌ | false | 分配伪终端（仅 Unix 同步模式） |
| `shell` | string | ❌ | `sh` | Unix 解释器（bash/zsh/...），仅同步模式生效 |
| `env` | object | ❌ | — | 额外环境变量键值对 |

### 工作目录硬控制

`shell.exec` 在 executor 入口调用 `WorkspaceResolver.normalizeWithInfo()` 对 `workingDirectory` 做统一规范化。**以下情况一律回退到用户配置的默认工作目录**并在日志里记 WARN：

| 触发条件 | 回退原因 | 是否带 warning |
|---|---|---|
| null / 空串 / 全空白 | 正常默认场景 | 否（静默） |
| Unix 根：`/` 或 `\` | 传入文件系统根路径 | 是 |
| Windows 盘根：`C:\`、`D:/`、`C:` 等 | 传入文件系统根路径 | 是 |
| 相对路径（如 `./src`、`src/foo`） | 传入值不是绝对路径 | 是 |
| 占位符 `.` 或 `..` | 传入相对占位符 | 是 |
| `Path.of()` 解析异常 | 路径解析失败 | 是 |

当发生回退（上表里"是"那几行），返回数据里会多一个 `workingDirectoryWarning`：

```json
{
  "exitCode": 0,
  "stdout": "...",
  "effectiveWorkingDirectory": "/Users/me/.zhiwei/workspace",
  "workingDirectoryWarning": "你传入的 workingDirectory='C:\\' 无效（传入文件系统根路径），已回退到默认工作目录 '/Users/me/.zhiwei/workspace'。下次建议不传此参数使用默认值"
}
```

**为什么是硬控制**：工作目录属于正确性范畴，只靠 schema description 软引导不可靠。回退后 LLM 能从 warning 里立刻知道"你传的值被替换了"，避免基于错误前提（"我传了 C:\ 所以进程在 C:\ 跑"）继续推理。

### 外部 CLI 自动注入 `CLAUDE_CODE_GIT_BASH_PATH`

Claude Code / Codex 等 CLI 在 Windows 上依赖 Unix bash。知微提供了自动注入逻辑（`mergeExternalCliEnv`）：

**触发条件**：命令开头或以空格/路径分隔的 token 含 `claude` 或 `codex`（大小写不敏感）。典型匹配例：

- `claude --print "..."`
- `codex exec`
- `pnpm exec claude --version`
- `C:\tools\claude.cmd run`

**注入策略**：

1. 命令不匹配 claude/codex → **不注入**，原样执行
2. 用户在设置里没配"外部 CLI Bash 依赖"路径 → **不注入**（让 CLI 自己报错暴露配置缺失）
3. 调用方 `env` 里已经显式带了 `CLAUDE_CODE_GIT_BASH_PATH` → **不覆盖**（显式优先）
4. 否则 → 把设置里的路径合并到 env 里注入

**Skill 作者需要知道**：如果 Skill 里直接调 `claude` / `codex`，不用手动拼 `env`，知微会自动处理。如果确实想强制用某个特定 bash，在 `env` 里显式写 `CLAUDE_CODE_GIT_BASH_PATH` 就能压过默认注入。

用户设置的配置入口：
- 设置项名："外部 CLI Bash 依赖"
- REST：`GET /api/settings/external-cli-bash` / `PUT /api/settings/external-cli-bash`（body: `{ "externalCliBashPath": "..." }`，空串清除）
- 底层字段：`user_settings.external_cli_bash_path`（Flyway V11）

### 三种执行模式

#### 模式一：同步（默认）

不传 `background` 和 `yieldMs` 时，主线程阻塞到命令完成或 `timeoutSeconds` 超时。

**行为特点**：
- 两个虚拟线程分别读 stdout/stderr（`readAllBytes()`）
- 超时后 `destroyForcibly()` 并收集部分输出
- `exitCode != 0` 直接返回 `ToolResult.error`，LLM 能一眼看到失败
- 输出超过 `maxOutputLength`（默认 50000 字符）会截断

**适用场景**：
- 秒级完成的查询类命令（`git status`、`ls`、`cat`）
- 构建 / 测试 / 格式化等"要么成要么败"的命令
- LLM 下一步推理必须依赖本次输出

**不要用**：
- 服务类命令（`npm run dev`、`java -jar server.jar`）—— 会跑满 timeout 被杀，毫无意义
- 超过 2 分钟的长任务（除非显式拉高 timeoutSeconds，但仍不如 background）

#### 模式二：background=true

立即返回 sessionId，完全不阻塞。进程在后台持续运行，输出写入环形缓冲区。

**返回结构**：
```json
{
  "sessionId": "a3f8c1b2",
  "effectiveWorkingDirectory": "/Users/me/.zhiwei/workspace",
  "message": "后台进程已启动，使用 process.output 读取输出，process.kill 终止进程"
}
```

若 workingDirectory 被回退，还会带 `workingDirectoryWarning`。

**⚠️ 关键陷阱**：`background=true` 的 **success 只代表进程成功启动，不代表命令成功执行**。命令本身可能秒退失败（比如语法错），必须后续调用 `shell.process(action=output)` 查看真实状态和 exitCode。

**适用场景**：
- 长跑服务（dev server、watcher、训练任务）
- 并发任务（同时起前端后端、跑多个测试集）
- 需要持续观察输出（日志尾随、性能监控）
- 交互式进程（配合 `action=write` 向 stdin 喂输入）

#### 模式三：yieldMs=N

先按后台进程起，然后同步等待 N 毫秒。进程提前退出立即返回（不浪费等待时间），超时则返回 sessionId 继续运行。

**返回结构动态**：
- **已完成**：返回 `{ exitCode, stdout, stderr, output, effectiveWorkingDirectory }`，与同步模式一致
- **未完成**：返回 `{ sessionId, backgrounded: true, effectiveWorkingDirectory, message }`，与 background 模式一致

两种分支都会附带 `effectiveWorkingDirectory`；回退时同样附带 `workingDirectoryWarning`。

**边界行为**：
- `yieldMs=0` 完全等价于 `background=true`（源码直接短路）
- `yieldMs > 120000` 会被 cap 到 120 秒硬上限
- 即便同步分支完成，也会短暂占用后台进程配额

**适用场景**：
- 部署脚本（大部分时候 2-3 秒，偶尔拉镜像卡 30 秒）
- 数据库迁移（空库秒级，大表几分钟）
- 依赖安装（`npm install` 有缓存 5 秒、无缓存 2 分钟）
- 任何"快就直接拿结果、慢就别阻塞"的探测性命令

### 模式选择决策树

```
需要执行命令
├─ 是永不退出的服务？
│   └─ YES → background=true（必须！）
├─ 耗时确定 < 5s？
│   └─ YES → 同步模式
├─ 耗时确定 > 30s？
│   └─ YES → background=true
└─ 耗时不确定？
    └─ yieldMs=N（N 设为"能接受的同步等待时长"）
```

---

## shell.process — 后台进程管理

`shell.process` 是统一入口，通过 `action` 参数分发。后台进程相关 action 有 4 个：

| action | 风险 | 调度模式 | 必填参数 | 说明 |
|---|---|---|---|---|
| `list` | LOW | PARALLEL_SAFE | — | 列出所有后台进程 |
| `output` | LOW | PARALLEL_SAFE | `sessionId` | 读取增量输出 |
| `write` | MEDIUM | SEQUENTIAL | `sessionId`, `input` | 向 stdin 写入 |
| `kill` | HIGH | SEQUENTIAL | `sessionId` | 强制终止进程 |

### action=list

返回所有当前被管理的后台进程（包括已完成但未清理的）。

```json
{
  "processes": [
    {
      "sessionId": "a3f8c1b2",
      "command": "npm run dev",
      "state": "RUNNING",
      "startTime": "2026-04-18T10:15:00Z",
      "workDir": "/path/to/project"
    }
  ],
  "count": 1
}
```

状态机：`RUNNING → COMPLETED | FAILED | KILLED`

### action=output

**关键**：读取的是**增量**，每次只返回自上次读取以来的新输出。

```json
{
  "sessionId": "a3f8c1b2",
  "output": "Server listening on 3000\n",   // stdout + stderr 合并
  "stdout": "Server listening on 3000\n",
  "stderr": "",
  "state": "RUNNING",
  "exitCode": null
}
```

**底层机制**（RingBuffer）：
- 每个进程的 stdout / stderr 各有一个环形缓冲区，默认 100000 字符
- 缓冲区满后覆盖最早内容
- 读取位置由 `lastReadPos` 游标维护
- 如果增量数据超过缓冲区容量（中间被覆盖），会自动返回 `[部分输出已被覆盖]` 提示

**进程退出后**：`output` 会自动等待 stdout/stderr 读线程排空（最多 5 秒），确保你能拿到完整的末尾输出。

#### 自动抽取 `lastResult`（Claude Code / Codex / Gemini 终态事件）

如果被管理的进程是 Claude Code、Codex、Gemini 这类以 JSON Lines 流式输出的 CLI（stream-json 模式），`action=output` 会自动扫本次增量里的最后一个"终态事件"，把摘要放在 `lastResult` 字段里返回。**LLM 不用扫整个日志就能拿到最终摘要**。

识别的终态事件类型（`TERMINAL_EVENT_TYPES`）：

| 类型 | 来自 |
|---|---|
| `result` | Claude Code |
| `task_complete` | Codex |
| `session_ended` | Codex |

抽取出来的摘要字段（只包含该事件里有的字段）：

```json
{
  "sessionId": "a3f8c1b2",
  "output": "...(大量 stream-json 行)...",
  "state": "COMPLETED",
  "exitCode": 0,
  "lastResult": {
    "type": "result",
    "subtype": "success",
    "result": "任务完成：已修复 3 个编译错误",
    "is_error": false,
    "duration_ms": 123456,
    "total_cost_usd": 0.0842
  }
}
```

**行为要点**：

- 只会处理以 `{` 开头的行，其他行（纯文本日志、进度条）会被静默跳过
- 多条终态事件出现时，保留**最后一条**
- 本次增量没有任何终态事件 → `lastResult` 字段**不存在**（不是 `null`），调用方用 `data.containsKey("lastResult")` 判断
- 解析失败（JSON 损坏等）静默忽略，不影响 `output` 字段本身

### action=write

向进程的 stdin 写入内容，用于交互式命令。

```json
{
  "action": "write",
  "sessionId": "a3f8c1b2",
  "input": "yes\n"
}
```

**注意**：
- 进程已退出时写入会报错（`IllegalStateException`）
- 需要换行触发，记得加 `\n`
- 写入后用 `output` 查看响应

### action=kill

强制终止进程（`destroyForcibly()`），状态转为 `KILLED`。

- **对已完成 / 不存在的进程安全幂等**：不抛异常，正常返回 success。LLM 不需要先查 state 再决定是否 kill
- kill 后环形缓冲区的数据依然可读
- schema description 里也写了"对已完成/不存在的进程安全幂等"，LLM 能直接感知这一保证

### 典型生命周期示例

```
1. shell.exec(command="python train.py", background=true)
   → { sessionId: "a3f8c1b2" }

2. 循环监控：
   shell.process(action=output, sessionId="a3f8c1b2")
   → state="RUNNING", output="Epoch 1/10..."

3. 发现异常，发 SIGINT（用 write 不行，要模拟 Ctrl+C 得用持久会话）：
   shell.process(action=kill, sessionId="a3f8c1b2")

4. 或任务完成后：
   shell.process(action=output, sessionId="a3f8c1b2")
   → state="COMPLETED", exitCode=0
```

### 输出监控：拉 vs 推

除了 LLM 主动调 `action=output`（拉模式），知微还提供 SSE 实时推送（推模式）。

**SSE 订阅端点**：`GET /api/processes/stream`

事件类型（见 `SseEventType` + `ProcessSseController`）：

| 事件 | 触发时机 | payload 关键字段 |
|---|---|---|
| `process-snapshot` | **连接建立时一次性推送**所有当前活跃进程，支持浏览器刷新恢复 | `processes[]`（每项含 sessionId、command、state、exitCode、startTime、workDir） |
| `process-started` | 新进程启动 | sessionId、channel=`state`、content=`started`、state、**command**（启动时专门带的原始命令） |
| `process-state-change` | 进程状态变化（COMPLETED / FAILED / KILLED） | sessionId、channel=`state`、content、state |
| `process-output` | 进程有新输出（stdout/stderr） | sessionId、channel=`stdout`\|`stderr`、content、state |

技术参数：

- **debounce**：200ms 或 4KB 攒批，避免高频推送
- **连接超时**：5 分钟（客户端需自行重连）
- **广播**：所有订阅者都能收到同一进程的事件

**前端直连 REST 端点**（不经 LLM 工具通道）：

前端气泡 UI 的"列表 / 停止"按钮直接走 REST，避免占用工具调用链路、不触发权限审批噪音：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/processes` | 列出所有活跃后台进程 |
| `DELETE` | `/api/processes/{sessionId}` | 前端"停止"按钮走这个接口；进程不存在时返回 404 |

**重要**：这两个 REST 端点**不是给 LLM 用的**。LLM 想列表/终止，必须走 `shell.process(action=list|kill)` 工具通道。

**Skill 里通常只用拉模式**。SSE 和 REST 都是给前端 UI 和外部监控用的。

### 自动清理

- 每分钟扫一次空闲超时的进程（默认 `idleTimeoutMinutes=30`）
- `touch()` 机制：任何 output/write/kill 调用都刷新最后访问时间，避免活跃进程被误杀
- 并发上限由 `maxConcurrent`（默认 5）控制，超了直接拒

---

## shell.process — 持久会话（tmux）

**前提**：持久会话依赖 tmux，**仅在 Unix 环境且 tmux 可执行时可用**。Windows 环境下 sessionManager 为 null，`shell.process` 的 schema 里**不会出现** session-* 这些 action。

### 与后台进程的本质区别

| 维度 | 后台进程（`background=true`） | 持久会话（`session-*`） |
|---|---|---|
| 底层 | 直接 `ProcessBuilder.start()` | tmux 会话内运行 shell |
| 每次命令 | 独立进程，状态互不影响 | 共享 shell 上下文（cwd、env、变量） |
| 终端特性 | 无 PTY（Windows）或可选（Unix） | 天然 PTY，完整终端能力 |
| 信号支持 | 仅 `destroyForcibly` | SIGINT / SIGTERM / SIGHUP / ... |
| 窗口调整 | 不支持 | 支持 `session-resize` |
| 典型用途 | 单一长跑任务 | REPL、多步交互、需要保留 shell 状态 |

### 8 个 session-* action

| action | 风险 | 必填 | 可选 | 说明 |
|---|---|---|---|---|
| `session-create` | HIGH | — | `name`, `workDir` | 创建会话，返回 sessionId |
| `session-exec` | HIGH | `sessionId`, `command` | — | 在会话中执行命令 |
| `session-write` | MEDIUM | `sessionId`, `input` | — | 向会话 stdin 写入（类似按键） |
| `session-read` | LOW | `sessionId` | — | 读取会话当前屏幕内容 |
| `session-signal` | HIGH | `sessionId`, `signal` | — | 发送信号（SIGINT/SIGTERM） |
| `session-resize` | LOW | `sessionId`, `cols`, `rows` | — | 调整终端窗口大小 |
| `session-list` | LOW | — | — | 列出所有持久会话 |
| `session-close` | MEDIUM | `sessionId` | — | 关闭会话 |

### 典型使用：进入 Python REPL

```
1. shell.process(action=session-create, name="pyrepl")
   → { sessionId: "b7c2d4e1" }

2. shell.process(action=session-exec, sessionId="b7c2d4e1", command="python3")
   → { output: ">>> " }

3. shell.process(action=session-write, sessionId="b7c2d4e1", input="import numpy\n")
   shell.process(action=session-read, sessionId="b7c2d4e1")
   → { output: ">>> import numpy\n>>> " }

4. 按 Ctrl+C 中断：
   shell.process(action=session-signal, sessionId="b7c2d4e1", signal="SIGINT")

5. 结束：
   shell.process(action=session-close, sessionId="b7c2d4e1")
```

### 会话恢复

`TmuxSessionManager` 启动时会扫描 `zhiwei-` 前缀的残留 tmux 会话并尝试回收，避免崩溃重启后孤儿会话堆积。

---

## 安全与权限

### 命令黑名单

`ShellExecToolExecutor` 构造时预编译正则黑名单（配置在 `lifepilot.meta.infra.shell.command-blacklist`）。匹配到直接拒绝：

```
命令被安全策略拒绝: 匹配黑名单规则 [<pattern>]
```

黑名单通常包含 `rm -rf /`、`mkfs`、`dd if=/dev/zero` 等毁灭性命令模式。

### 权限审批（Guardrail）

每个 action 都有 `RiskLevel`：

- **HIGH**（如 `shell.exec`、`session-create`、`kill`）：触发用户确认
- **MEDIUM**（如 `write`、`session-close`）：根据策略决定
- **LOW**（如 `list`、`output`、`session-read`）：通常自动放行

Web 端通过 `WebPermissionApprovalService` 呈现审批对话框，通过事件机制等待用户决策。

### 工作目录限制

`workspaceResolver.resolveAndCreate()` 提供默认工作目录。`ToolScopeResolvers.workspacePaths("workingDirectory", "cwd")` 会把实际路径注入权限审批的 scope，便于策略引擎做细粒度判断。

### 超时保护

- 同步模式：`timeoutSeconds` 到期 `destroyForcibly` + 2 秒等待清理
- 输出读取超时：`outputReadTimeoutSeconds`（默认 timeoutSeconds + 5），防止流未关闭导致永久阻塞
- 后台进程：`idleTimeoutMinutes` 自动清理空闲进程

---

## 跨平台差异

### 命令解释器

| 平台 | 默认 | 可覆盖？ |
|---|---|---|
| Windows | PowerShell | ❌ `shell` 参数被忽略 |
| Unix | `sh` | ✅ 用 `shell: "bash"` 切换（仅同步模式） |

后台模式和 yieldMs 模式下 Unix 固定使用 `sh`，**无法指定 bash/zsh**。如果命令依赖 bash 特性，显式写 `bash -c "..."` 作为 command 内容。

### PTY 支持

- Unix：`pty: true` 分配伪终端（仅同步模式），命令会以为自己在真实终端里运行
- Windows：不支持，`pty: true` 会被降级为普通执行并警告

### Windows 路径坑

Tauri 传入的路径可能有 `\\?\` 前缀（Windows 扩展路径语法），Java `ProcessBuilder` 识别不了。知微内部会剥离，但如果你在 Skill 里手工拼路径，注意避免。

---

## 配置参考

所有 shell 相关配置在 `application.yml` 的 `lifepilot.meta.infra` 下：

```yaml
lifepilot:
  meta:
    infra:
      shell:
        timeoutSeconds: 120           # 同步模式默认超时
        maxOutputLength: 50000        # 输出截断阈值
        outputReadTimeoutSeconds: 0   # 0 = 自动（timeoutSeconds + 5）
        transientRetries: 1           # 瞬时故障重试次数
        commandBlacklist:
          - "rm\\s+-rf\\s+/"
          - ...
      process:
        maxConcurrent: 5              # 后台进程并发上限
        maxOutputBufferSize: 100000   # 环形缓冲区容量（字符）
        idleTimeoutMinutes: 30        # 空闲清理阈值
      shellSession:
        maxConcurrentSessions: 5      # 持久会话并发上限
        timeoutSeconds: 30            # session-exec 命令等待超时
```

---

## 常见陷阱与最佳实践

### ⚠️ 陷阱 1：background 的 success ≠ 命令成功

```json
// 这不意味着 python 脚本执行成功了，只说明进程启起来了
{ "sessionId": "a3f8c1b2", "message": "后台进程已启动..." }
```

**对策**：后续必须至少调一次 `action=output` 检查真实 state 和 exitCode。

### ⚠️ 陷阱 2：服务类命令用同步模式

```json
// ❌ 错误：npm run dev 不会退出，会被 120s 超时强杀
{ "command": "npm run dev" }

// ✅ 正确：必须 background
{ "command": "npm run dev", "background": true }
```

### ⚠️ 陷阱 3：忘记 kill 后台进程

LLM 起了 `npm run dev` 后直接结束对话，进程留着占配额直到 `idleTimeoutMinutes`。

**对策**：Skill 里写明"任务完成后必须 kill"，或者在 prompt 里教 LLM 用 try/finally 思维。

### ⚠️ 陷阱 4：write 不能模拟 Ctrl+C

`shell.process(action=write, input="\x03")` 对大部分进程无效（普通 stdin 写入不是信号）。

**对策**：需要信号控制的场景用持久会话 + `session-signal`。

### ⚠️ 陷阱 5：RingBuffer 覆盖导致输出丢失

长跑命令输出超过 `maxOutputBufferSize`（默认 10 万字符）又不及时读，会被覆盖：

```
[部分输出已被覆盖]
<剩余缓冲区内容>
```

**对策**：高频输出的进程要频繁调 `action=output`，或拉高 `maxOutputBufferSize`。

### ⚠️ 陷阱 6：yieldMs 同步完成时的错误语义

yieldMs 同步分支完成时，如果 `processInfo.state != COMPLETED`，会返回 `ToolResult.ERROR`（与同步模式一致）。但转后台分支永远返 success。

**对策**：调用方看到 `backgrounded: true` 时才切换到"后台语义"，否则按同步失败处理。

### ✅ 最佳实践清单

- 优先用 `yieldMs`：大多数"可能慢"的命令都适合
- 后台进程必须有"回收义务"：起了就要有办法 kill
- 输出大的进程要分批读：不要等半天一次读几百 KB
- 持久会话用于真正需要 shell 状态的场景：普通命令用后台进程足够
- 在 Skill prompt 里明确模式选择规则，别让 LLM 自己猜
- **`workingDirectory` 推荐默认不传**，让 `WorkspaceResolver` 使用用户配置的默认工作目录。只在明确要切到其他目录时才传**绝对路径**
- 看到响应里有 `workingDirectoryWarning` 就意味着你传的值被回退了，下一步推理以 `effectiveWorkingDirectory` 为准
- 后端调用 `claude` / `codex` 时不需要手动配 bash 路径；如果确实需要覆盖，用 `env.CLAUDE_CODE_GIT_BASH_PATH` 显式指定
- 监控 Claude Code / Codex 等 stream-json CLI 时，优先看 `output` 返回里的 `lastResult` 摘要，比扫整个 stdout 快

---

## 为 Skill 编写提示词参考

以下段落可以直接复制到 Skill 的 system prompt 或 instruction 里，帮助 LLM 正确选择工具和模式。

### 通用指导段（推荐必加）

```markdown
## Shell 工具使用规则

你有两个 Shell 工具：

1. **shell.exec** — 启动命令，三种模式：
   - 不传 background/yieldMs（同步）：秒级查询命令，如 `git status`、`ls`、构建命令
   - `background=true`：永不退出的服务（如 `npm run dev`），或明确需要并发执行的长任务
   - `yieldMs=3000`：命令快慢不确定时用（典型 3000-10000），快的话直接拿结果，慢的话自动转后台

2. **shell.process** — 管理已启动的后台进程：
   - `action=output, sessionId=xxx`：读取增量输出（多次调用只返回新内容）
   - `action=kill, sessionId=xxx`：终止进程（对已完成/不存在的进程安全幂等）

**关键规则**：
- `background` 模式的 success **只代表进程启成功**，不代表命令成功。必须后续用 `action=output` 检查真实 exitCode
- 服务类命令（`npm run dev`、`java -jar server.jar`）**必须**用 background，否则会被超时强杀
- 后台进程用完必须 kill，否则占配额
- 要模拟 Ctrl+C 需要用持久会话（如果可用）+ `session-signal`，不能用 write
- `workingDirectory` **推荐默认不传**。只在明确需要其他目录时才显式传绝对路径。禁止传 `/`、`C:\`、相对路径——会被强制回退
- 如果返回里看到 `workingDirectoryWarning`，说明你传的工作目录被回退了，后续决策以 `effectiveWorkingDirectory` 为准
- 调用 `claude` / `codex` 等外部 CLI 时不需要手动拼 `CLAUDE_CODE_GIT_BASH_PATH`，知微会根据用户设置自动注入
- `action=output` 返回里如果带 `lastResult`（type=result/task_complete/session_ended），直接用这个摘要即可，不必重扫整个 stdout
```

### 部署 / 构建类 Skill 专用段

```markdown
## 部署命令执行规则

- 使用 `yieldMs=10000` 执行部署脚本：大部分场景几秒完成，个别情况（拉镜像、下载依赖）会慢
- 收到 `backgrounded: true` 时，循环调用 `shell.process(action=output)` 监控进度，每次间隔 5-10 秒
- 发现异常关键字（`ERROR`、`FAILED`、`fatal`）时立即 `action=kill` 并报告给用户
- 部署完成（`exitCode=0`）或失败后清理 sessionId
```

### 服务运维类 Skill 专用段

```markdown
## 服务进程管理规则

启动服务：
- 必须 `background=true`
- 启动后等 2-3 秒，调一次 `shell.process(action=output)` 确认服务就绪（看到端口监听日志等标志）

监控服务：
- 定期 `action=output` 收集日志增量
- 维护 sessionId 到服务名的映射表

停止服务：
- `action=kill` 即可
- 对有 graceful shutdown 需求的，优先用持久会话的 `session-signal` 发 SIGTERM
```

---

## 相关源码

- `src/main/java/com/lifepilot/meta/infra/shell/ShellToolProvider.java` — 工具定义与 schema（含 CLIXML / 盘根禁用 / kill 幂等等 description 关键条款）
- `src/main/java/com/lifepilot/meta/infra/shell/ShellExecToolExecutor.java` — 命令执行主逻辑（含 `normalizeWithInfo` 硬控制 + `mergeExternalCliEnv` 自动注入）
- `src/main/java/com/lifepilot/meta/infra/shell/BackgroundProcessManager.java` — 后台进程生命周期
- `src/main/java/com/lifepilot/meta/infra/shell/ShellProcessDispatchExecutor.java` — shell.process action 路由（含 `extractLastTerminalEvent` 终态事件抽取）
- `src/main/java/com/lifepilot/meta/infra/shell/session/TmuxSessionManager.java` — 持久会话管理
- `src/main/java/com/lifepilot/meta/infra/shell/RingBuffer.java` — 环形输出缓冲
- `src/main/java/com/lifepilot/meta/infra/shell/ProcessOutputEvent.java` — 进程事件（含 `command` 字段，仅启动事件携带）
- `src/main/java/com/lifepilot/interaction/web/sse/SseEventType.java` — SSE 事件类型常量（`PROCESS_STARTED` / `PROCESS_SNAPSHOT` / `PROCESS_STATE_CHANGE` / `PROCESS_OUTPUT`）
- `src/main/java/com/lifepilot/interaction/web/controller/ProcessSseController.java` — SSE 实时推送 + 连接建立时快照
- `src/main/java/com/lifepilot/interaction/web/controller/ProcessRestController.java` — 前端气泡 UI 的 REST 通道（list / kill）
- `src/main/java/com/lifepilot/config/workspace/WorkspaceResolver.java` — 工作目录统一规范化 + 外部 CLI bash 路径读取
- `src/main/java/com/lifepilot/interaction/web/model/UserSettings.java` — 含 `externalCliBashPath` 字段
- `src/main/java/com/lifepilot/interaction/web/controller/SettingsController.java` — `/api/settings/external-cli-bash` 端点
- `src/main/resources/db/migration/V11__add_external_cli_bash_path.sql` — 外部 CLI Bash 路径字段迁移
