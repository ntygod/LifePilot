# 编码代理启动与监控命令速查

## 1. 启动前验证 CLI

```
shell_exec(command="claude --version")
shell_exec(command="claude /status")
```

- 未安装 → 提示用户 `npm install -g @anthropic-ai/claude-code`,**不代装**
- 未登录 → 提示用户手动 `claude login`,**不代走 OAuth**
- Windows 报 `CLAUDE_CODE_GIT_BASH_PATH` 找不到 → 让用户去设置页填 Bash 路径

## 2. 启动单 Agent

```
shell_exec(
  command="claude -p '<任务描述>' --output-format stream-json --permission-mode acceptEdits",
  workingDirectory="<worktree 绝对路径>",
  background=true
) → { sessionId: "<sessionId>" }
```

启动后**立即**调一次 `output` 验启动:

```
shell_process(action="output", sessionId="<sessionId>")
```

| 看到什么 | 含义 |
|---|---|
| `{"type":"system","subtype":"init"}` 或 Codex `{"type":"session_configured"}` | 启动成功,进入轮询 |
| `state=FAILED` 或 `exitCode≠0` | 启动失败,读 stderr 报告用户,不要进轮询 |
| 10 秒内无输出 | 模型预热延迟,继续等,不要 2 秒就 kill |

## 3. 监控轮询(间隔 5-15 秒)

```
shell_process(action="output", sessionId="<sessionId>")
```

每轮检查:
- `lastResult` 字段 → 有值即结束(后端已解析 stream-json 的 `type=result` 事件)
- `state` → `RUNNING` 继续 / `COMPLETED` / `FAILED` 终止
- 原始 stream-json `type=assistant` → `message.content[].type=tool_use` 的 `name` 即当前动作(Read / Write / Bash...)
- 关键信号:`ERROR` / `fatal` / `permission denied` / 待用户确认的提问

汇报节奏:启动一条 / 里程碑 / 异常各报一次,**不每轮汇报**。

## 4. 完成 / 中止

| 场景 | 动作 |
|---|---|
| `exitCode=0` | 读 `lastResult` + `git diff --stat` 给汇报 |
| `exitCode≠0` | 读完整 output 与 stderr 排查 |
| 连续 2-3 轮无输出增量 | 必要时 `shell_process(action="kill", sessionId=...)` 重启 |
| 已完成的进程 | 不需要主动 kill(后端 30 分钟自动清理) |
| 想看全部在跑的 | `shell_process(action="list")`(无需 sessionId) |

## 5. 编排三种模式

| 模式 | 命令骨架 | 关键约束 |
|---|---|---|
| 串行 | A 完成且 `exitCode=0` 后再启 B | 失败即 skip,不传完整 stream-json,后序自己读 worktree |
| 并行 | 同一轮启多个 sessionId | 各自独立 worktree(同一文件并行写必冲突);受 `maxConcurrent=5` 限制,看到"后台进程数已达上限"先 `action=list` 清空闲 |
| 反馈环 | 审查→修→再审查 | 硬上限 ≤2 轮,超限停下问用户;只传摘要,不传完整审查报告 |
