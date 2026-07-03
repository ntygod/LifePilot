# 编码 Agent 生命周期

## 启动前

- 验证 CLI：`claude --version`；未安装提示 `npm install -g @anthropic-ai/claude-code`，不代装
- 未登录提示手动 `claude login`，不代走 OAuth
- Windows 报 `CLAUDE_CODE_GIT_BASH_PATH` → 让用户去设置页填路径

## 启动

```bash
shell.exec(command="claude -p '<任务>' --output-format stream-json --permission-mode acceptEdits",
           workingDirectory="<worktree>", background=true)
```

启动后立即调 shell.process(output) 验启动：看到 init/init 即成功，FAILED 读 stderr，10 秒无输出是预热。

## 监控轮询（间隔 5-15 秒）

每轮检查 shell.process(output)：lastResult 有值即结束，state=RUNNING 继续/COMPLETED/FAILED 终止。
汇报节奏：启动/里程碑/异常各报一次，不每轮汇报。

## 完成/中止

| 场景 | 动作 |
|------|------|
| exitCode=0 | 读 lastResult + git diff --stat 汇报 |
| exitCode≠0 | 读 output + stderr 排查 |
| 连续 2-3 轮无输出 | shell.process(kill) 重启 |
| 后台进程数达上限 | shell.process(list) 清空闲 |

## 编排模式

| 模式 | 约束 |
|------|------|
| 串行 | A 完成 exitCode=0 后启 B，失败 skip |
| 并行 | 同一轮启多个 sessionId，各自独立 worktree |
| 反馈环 | 审查→修→审查，硬上限 ≤2 轮 |

## 不要做

- 不让 Agent 装新依赖/调外部 API（除非任务真需要）
- 不把上一 Agent 的 stream-json 塞进下一 Agent 的 prompt
- 不在 kill/output 响应里回显 env 内容（API key 泄漏）
