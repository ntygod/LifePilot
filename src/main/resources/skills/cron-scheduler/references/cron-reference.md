# Cron 任务管理命令速查

## 创建定时任务

```
cron(action="create", name="每日AI资讯", schedule="0 0 8 * * *", instruction="搜索最新AI资讯并发送摘要")
```

- `name`：任务名称（中文）
- `schedule`：Spring 6 位 Cron（秒 分 时 日 月 周），**不是 Linux 5 位**
- `instruction`：Agent 执行时的 prompt 指令

## 常用 Cron 表达式

| 表达式 | 含义 |
|--------|------|
| `0 0 8 * * *` | 每天早上 8 点 |
| `0 30 9 * * MON-FRI` | 工作日 9:30 |
| `0 0 */2 * * *` | 每 2 小时 |
| `0 0 8 * * MON` | 每周一早上 8 点 |
| `0 0 8 1 * *` | 每月 1 号早上 8 点 |

## 查看任务

```
cron(action="list")
cron(action="list", status="active")
```

## 修改任务

```
cron(action="update", taskId="task-xxx", schedule="0 0 9 * * *")
cron(action="update", taskId="task-xxx", status="paused")
```

## 删除任务

```
cron(action="remove", taskId="task-xxx")
```

删除任务会同时删除执行日志，执行前确认。

## 静默协议

任务执行后如果没有需要汇报的内容（例行检查一切正常），回复 `TASK_SILENT`（必须出现在回复的开头或结尾）。

## 常见错误处理

- **Cron 表达式错误** → 确认是 6 位格式，检查秒/分/时顺序
- **任务不触发** → 确认 status 是 active，检查 schedule 是否正确
- **任务执行失败** → 查看执行日志，检查 instruction 是否清晰
