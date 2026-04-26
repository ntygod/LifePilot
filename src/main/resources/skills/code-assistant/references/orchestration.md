# 编码 CLI 速查与汇总模板

## CLI 命令速查

| CLI | 非交互模式 | 结构化输出 | 备注 |
|---|---|---|---|
| Claude Code | `claude -p "<task>"` | `--output-format stream-json` | 后端按 `type=result` 自动解 `lastResult` |
| Codex | `codex exec "<task>"` | `--json` | 命中 quota 输出 `hit your usage limit`,需停止编排 |
| Gemini | `gemini -p "<task>"` | `--output-format json` | |

**持久交互模式**:仅 Unix + tmux 可用(走 `shell.process(action="session-*")`);Windows 做不到,告诉用户换 `-p` 多次调用或 WSL。

## 模型档位切换

成本敏感时给 CLI 加便宜模型参数,按任务复杂度选:

```
claude -p "<task>" --model claude-haiku-4-5      # 简单改动 / 审查打分
claude -p "<task>"                                # 默认,适合多文件实现
```

## 汇总模板

整个编排链路完成后给用户结构化汇报。**阶段数与阶段名按实际填**(单阶段就别套两阶段模板)。

```markdown
## 任务完成摘要

**<阶段 1 名称>**(<用的 CLI>, ~<X> 分钟)
- 改动文件(`git diff --stat`)
- 核心改动(从 `lastResult` 提取)

**<阶段 2 名称>**(<用的 CLI>, ~<Y> 分钟)
- 关键产出 / 发现问题 / 建议

**建议行动**
- [ ] merge / 修改 / 重跑 / ...

worktree: `<路径>`(保留,用户决定是否清理)
```

## 错误处理表

| 症状 | 对策 |
|---|---|
| 启动秒退 `FAILED` | 读 stderr:API key 过期 / 参数错 / CLI 版本过旧 |
| Windows 报 `CLAUDE_CODE_GIT_BASH_PATH` 找不到 | 让用户去设置页填 Bash 路径(知微会自动注入 env) |
| Codex `hit your usage limit` | 配额耗尽,停止编排,建议换 claude 或等配额重置 |
| 长时间 `RUNNING` 无输出 | 连续 2-3 轮确认后 kill 重试 |
| `[部分输出已被覆盖]` | 轮询太慢,缩短间隔或 `--output-file` 落盘再读 |
| `后台进程数已达上限` | `shell.process(action="list")` 找空闲 sessionId 清理 |
| 反馈环超 2 轮 | 停下来问用户(改不动八成是方向错了) |

## 不要做

- 不要让 Agent 在 prompt 里"装新依赖"或"调外部 API",除非任务真的需要——必要时显式约束:`prompt += "\n约束:不安装新依赖,不发起网络请求"`
- 不要在 kill / output 响应里回显 `env` 内容(API key 泄漏风险)
- 不要把上一个 Agent 的几十 KB stream-json 塞进下一个 Agent 的 prompt(token 爆炸,密度低)
