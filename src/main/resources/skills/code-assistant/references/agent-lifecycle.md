# 编码代理启动与监控

## 启动前准备

用同步模式快速验证 CLI：

```
shell.exec(command="claude --version")
shell.exec(command="claude /status")
```

**未装** → 提示用户 `npm install -g @anthropic-ai/claude-code`，**不自行安装**。
**未登录** → 提示用户手动 `claude login`，**不代替用户走 OAuth**。

Windows 上 claude 还需要 bash，知微会自动注入 `CLAUDE_CODE_GIT_BASH_PATH`（在设置页配置一次）。报错提示用户去设置页填。

**破坏性任务必须在 git worktree 或临时目录**，不要让 Agent 在用户主工作目录自由改动。

## 启动单个 Agent（推荐模板）

```
shell.exec(
  command="claude -p '<任务描述>' --output-format stream-json --permission-mode acceptEdits",
  workingDirectory="<worktree 绝对路径>",
  background=true
) → { sessionId: "<sessionId>" }
```

启动后**立即**调 `shell.process(action=output, sessionId="<sessionId>")` 验证：

- 看到 `{"type":"system","subtype":"init"}` 或 Codex 的 `{"type":"session_configured"}` → 启动成功，进入轮询
- state=FAILED 或 exitCode≠0 → 启动失败，读 stderr 报告用户，**不要进入轮询循环**
- 容忍 10 秒无输出（模型预热延迟），**不要 2 秒没输出就 kill**

## 监控（轮询间隔 5-15 秒）

每轮 `shell.process(action=output, sessionId=xxx)` 后：

1. 看 `lastResult` 字段（后端已解析 stream-json 最后一个 `type=result` 事件）。有值 → 结束；无值 → 仍在运行
2. 从原始 stream-json 提**当前动作**：`type=assistant` → `message.content[].type=tool_use` 对应 `name`（Read/Write/Bash...）
3. 识别关键信号：里程碑、错误（ERROR/fatal/permission denied）、需要确认的发问
4. 检查 state → RUNNING 继续、COMPLETED/FAILED 结束

**向用户汇报节奏**：启动一条消息、里程碑更新、异常立即报。**不要每轮轮询都汇报**。

## 完成

- COMPLETED 且 exitCode=0 → 读最终 result 事件 + `git diff` 看改动
- 失败 / 连续 2-3 轮无输出增量 → 读完整 output 排查，必要时 kill 重启
- 已完成的进程不需要主动 kill（30 分钟后自动清理）

## 编排三种模式

| 模式 | 场景 | 关键规则 |
|---|---|---|
| 串行 | 前后依赖（实现→审查） | 前序 `exitCode=0` 才启动后序；失败就 skip |
| 并行 | 互相独立（多 issue / 多方案） | 改同一文件必须独立 worktree；每轮 `list` 看全貌 |
| 反馈环 | 迭代改进（审查→修→再审查） | **必须硬上限 2-3 次**；传摘要不传完整报告；超限停下问用户 |
