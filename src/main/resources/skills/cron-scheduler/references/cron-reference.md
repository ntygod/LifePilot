# Cron 调度参考

`cron(action=...)` 路由到具体操作。所有命令用占位符 `<...>`，按需替换。

## 各 action 命令模板

### 创建（create）

```
cron(action="create",
     name="<任务名>",
     schedule="<6 位 cron>",
     instruction="<触发后让 Agent 做什么的 prompt>",
     skillIds="<可选；当前已加载 Skill 的 id 逗号分隔>")
```

`taskId` 可省略，由系统生成。

### 查询（list）

```
cron(action="list")                          # 全部
cron(action="list", status="active")         # 只看运行中
cron(action="list", status="paused")         # 只看暂停
```

### 更新（update）

未传字段保持不变，按需传。

```
cron(action="update", taskId="<id>", schedule="<新 cron>")     # 改时间
cron(action="update", taskId="<id>", instruction="<新指令>")   # 改内容
cron(action="update", taskId="<id>", status="paused")          # 暂停
cron(action="update", taskId="<id>", status="active")          # 恢复
```

### 删除（remove）

```
cron(action="remove", taskId="<id>")
```

会同时清执行日志，告诉用户后再调。

## Cron 表达式速查（Spring 6 位 = 秒 分 时 日 月 周）

`* = 每个`、`*/n = 每 n 个`、`a-b = 范围`、`a,b,c = 列举`、`?` 用于日/周二选一。**不是 Linux 5 位**。

| 表达式 | 含义 |
|---|---|
| `0 0 8 * * *` | 每天 8:00 |
| `0 30 9 * * MON-FRI` | 工作日 9:30 |
| `0 0 9,18 * * *` | 每天 9 点和 18 点 |
| `0 0 */2 * * *` | 每 2 小时整点 |
| `0 */15 * * * *` | 每 15 分钟 |
| `0 0 8 * * MON` | 每周一 8:00 |
| `0 0 10 * * SAT,SUN` | 每周末 10:00 |
| `0 0 8 1 * *` | 每月 1 号 8:00 |
| `0 0 8 L * *` | 每月最后一天 8:00 |
| `0 0 0 * * *` | 每天 0:00（午夜） |

**用户表达 → cron**：

- "每天早上 8 点" → `0 0 8 * * *`
- "工作日下午 6 点" → `0 0 18 * * MON-FRI`
- "每隔 30 分钟" → `0 */30 * * * *`
- "每周三晚上 9 点" → `0 0 21 * * WED`

## 静默协议示例

```
TASK_SILENT  # 例行检查无异常，回复任意位置出现即可

# 或者说明性结尾：
监控正常，无新增告警。TASK_SILENT
```

## 错误处理

| 现象 | 处理 |
|---|---|
| `Cron 表达式无效` | 检查是 6 位不是 5 位；秒/分/时顺序；周用大写 `MON-SUN` |
| 任务不触发 | `cron(action="list")` 看 `status` 是否 `active`；schedule 是否被改成未来时间 |
| 任务执行失败 | 让用户看执行日志页面；多半是 `instruction` 太模糊或依赖 Skill 不可用 |
| `taskId` 找不到 | 先 `list` 拿真实 id，不要凭记忆传 |
