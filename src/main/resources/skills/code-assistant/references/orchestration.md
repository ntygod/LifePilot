# 编码代理编排详细参考

## 支持的 CLI

必须用**非交互模式**（background 下无 PTY，交互模式会卡死），推荐用**结构化输出格式**便于解析事件。

**持久交互模式**：仅 Unix + tmux 可用（走 `shell.process(action=session-*)`）；Windows 下做不到，直接告诉用户换 `-p` 多次调用或 WSL。

| CLI | 非交互命令 | 结构化输出参数 |
|---|---|---|
| Claude Code | `claude -p "任务"` | `--output-format stream-json` |
| Codex | `codex exec "任务"` | `--json` |
| Gemini | `gemini -p "任务"` | `--output-format json` |

## Agent 选择原则

- 任一阶段的 CLI 都从"支持的 CLI"表里选，用户没指定就用可用的里最熟悉的
- 审查阶段推荐和前序用不同 CLI（多一重视角、减少同一模型盲区）；成本敏感时也可以同 CLI 不同 prompt
- 小任务用便宜模型：如 `--model claude-haiku-4-5` 切 haiku
- 并发约束：受 `maxConcurrent=5` 限制，看到 `后台进程数已达上限` 时先清理

## 上下文传递

Agent 间的"交接"遵循两条原则：

1. **优先共享 worktree**：后续 Agent 和前序在同一目录，自己读文件/跑 `git diff`，无需在 prompt 里传改动
2. **必须传摘要时提炼再传**：别把上一个 Agent 的完整 stream-json（几十 KB）塞进下一个 Agent 的 prompt，token 爆炸且信息密度低

## 汇总模板

整个编排链路完成后给用户结构化汇报。**每个阶段的标题和 Agent 按实际使用情况填**。

```markdown
## 任务完成摘要

**<阶段 1 名称>**（<用的 CLI>, ~X 分钟）
- 改动文件（git diff --stat）
- 核心改动（从 lastResult 提取）

**<阶段 2 名称>**（<用的 CLI>, ~Y 分钟）
- 关键产出 / 发现问题 / 建议

**建议行动**
- [ ] merge / 修改 / 重跑 / ...

worktree: <路径>（保留，用户决定是否清理）
```

举例：如果只是"实现一个功能"，单阶段就够（不用套两阶段模板）；"实现 + 审查 + 修复"则三阶段。

## 安全红线

### `--permission-mode bypassPermissions` / `--full-auto` 的边界

绕过所有权限确认。**三条件同时满足才允许**：
1. 工作目录是 **git worktree** 或临时目录（`/tmp/xxx`、`~/.zhiwei/sandbox/xxx`）
2. 目录不含未提交改动 / 敏感凭据 / `.env`
3. 用户明确授权

**禁止**用于用户主工作目录。审查类任务用 `--permission-mode acceptEdits` 或对应只读模式。

### API Key 泄漏

- 只通过 `env` 参数注入，不写入文件
- kill/output 响应不要回显 env 内容

### 未授权网络操作

如果任务不该装依赖 / 调外部 API，在 prompt 里**显式约束**："不要安装新依赖"、"不要发起网络请求"。

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
