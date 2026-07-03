# 编码 CLI 参考

## CLI 命令速查

| CLI | 非交互模式 | 结构化输出 |
|-----|-----------|-----------|
| Claude Code | `claude -p "<task>"` | `--output-format stream-json` |
| Codex | `codex exec "<task>"` | `--json` |
| Gemini | `gemini -p "<task>"` | `--output-format json` |

持久交互模式仅 Unix+tmux 可用（shell.process(session-*)），Windows 换 `-p` 多次调用。

## 模型档位

成本敏感时按任务复杂度选：`claude -p "<task>" --model claude-haiku-4-5`（简单改动），默认适合多文件实现。

## 汇总模板

```
## 任务完成摘要

**<阶段名>**(<CLI>, ~<X> 分钟)
- 改动文件（git diff --stat）
- 核心改动（从 lastResult 提取）

**建议行动**
- [ ] merge / 修改 / 重跑

worktree: <路径>（保留，用户决定是否清理）
```

## 常见错误

| 症状 | 对策 |
|------|------|
| 启动秒退 FAILED | 读 stderr：API key 过期/参数错/CLI 过旧 |
| Codex quota 耗尽 | 停止编排，换 claude 或等配额重置 |
| 长时间 RUNNING 无输出 | 连续 2-3 轮确认后 kill 重试 |
| `[部分输出已被覆盖]` | 缩短轮询间隔 |
| 后台进程达上限 | shell.process(list) 清空闲 |
| 反馈环超 2 轮 | 停下来问用户 |
