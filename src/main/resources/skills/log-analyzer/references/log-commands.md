# 日志分析命令速查

占位符 `<log>` 替换具体日志路径，`<pattern>` 替换关键词，`<N>` 替换行数。

## 常用日志路径

- 知微：`<dataDir>/logs/` 下（lifepilot.log / agent.log / error.log），dataDir 默认在用户主目录的 .zhiwei 子目录，可在设置页查看实际路径
- Java 应用：`./logs/`、`./target/logs/`
- Spring Boot：`./logs/spring.log`、`./logs/application.log`
- Nginx：`/var/log/nginx/error.log`、`/var/log/nginx/access.log`
- Linux 系统：`/var/log/syslog`、`/var/log/messages`、`/var/log/dmesg`
- Windows 事件：`Get-EventLog -LogName Application -Newest <N>`

## 定位日志文件

```
file_read(action="list", path="<log-dir>", pattern="*.log", maxDepth=2)
```

## 快速扫错

### Windows PowerShell

```bash
# 尾部最近异常
shell_exec(command="powershell -c \"Get-Content <log> -Tail 500 | Select-String 'ERROR|FATAL|Exception'\"")
# 全文扫描（小文件 < 100MB）
shell_exec(command="powershell -c \"Select-String -Path <log> -Pattern '<pattern>' -Context 0,5\"")
```

### Linux bash

```bash
# 全文扫描带前后 5 行上下文
shell_exec(command="grep -nE 'ERROR|FATAL|Exception' <log> -A 5 -B 1 | head -200")
# 多文件一次扫
shell_exec(command="grep -rnE 'ERROR|FATAL' <log-dir>/ -C 3")
```

## 大文件按段读

```
file_read(path="<log>", startLine=10000, endLine=10500)
file_read(path="<log>", maxChars=30000)
```

不要一次性读整个大日志文件，先 `wc -l <log>` 看行数再分段。

## 时间窗口过滤

日志含 ISO 8601 时间前缀（如 `2026-04-25 14:`），先按时间缩小再扫错：

### Linux

```bash
# 只看 4-25 14 点
shell_exec(command="grep '^2026-04-25 14:' <log> | grep -E 'ERROR|Exception'")
# 一段时间窗口
shell_exec(command="awk '/^2026-04-25 14:00/,/^2026-04-25 16:00/' <log>")
```

### Windows

```bash
shell_exec(command="powershell -c \"Get-Content <log> | Select-String '^2026-04-25 14:' | Select-String 'ERROR'\"")
```

## 错误频率统计（Top N）

### Linux

```bash
# 提取错误关键字段排序
shell_exec(command="grep 'ERROR' <log> | awk -F'ERROR' '{print $2}' | awk '{print $1,$2,$3}' | sort | uniq -c | sort -rn | head -20")
# 异常类名 Top
shell_exec(command="grep -oE '[A-Z][a-zA-Z]+Exception' <log> | sort | uniq -c | sort -rn | head -20")
```

### Windows

```bash
shell_exec(command="powershell -c \"Get-Content <log> | Select-String 'ERROR' | ForEach-Object { ($_.Line -split 'ERROR')[1].Substring(0,[Math]::Min(80,$_.Line.Length-($_.Line.IndexOf('ERROR')+5))) } | Group-Object | Sort-Object Count -Descending | Select-Object -First 20 Count,Name\"")
```

## 完整堆栈追踪

抓异常行后续 N 行（通常 20-40 行够用）：

```bash
# Linux
shell_exec(command="grep -A 30 'Caused by' <log> | head -100")
# Windows
shell_exec(command="powershell -c \"Select-String -Path <log> -Pattern 'Caused by' -Context 0,30\"")
```

## 脱敏正则（输出前必须处理）

| 类型 | 正则 | 替换 |
|------|------|------|
| IPv4 | `(\d{1,3}\.){3}\d{1,3}` | `xxx.xxx.xxx.***` |
| 邮箱 | `[\w.+-]+@[\w-]+\.[\w.-]+` | `***@<domain>` |
| 手机号 | `1[3-9]\d{9}` | `1xx****<后 4 位>` |
| Token | `(token|key|secret)["':=\s]+[A-Za-z0-9+/=]{16,}` | `<key>=***` |
| 用户 ID | `(user_id|uid)["':=\s]+\d+` | `user_***` |

报告中引用日志原文时，**先 sed/正则替换敏感字段再输出**。

## 分析报告结构

```
## 概览
- 日志范围：<起止时间>
- 总行数：<count>
- ERROR 行数：<count>

## 关键错误（需立即处理）
1. <错误类型>（<次数>）
   - 首次：<时间>
   - 堆栈摘要：<3-5 行核心>
   - 影响：<推断>

## 错误趋势
- <时间分布或频率变化>

## 重复模式
- <反复出现的模式>

## 修复建议
1. <可执行动作>
```

分级阈值：崩溃 / OOM / 业务关键链路报错 → 立即；高频 ERROR / 慢查询 → 关注；常规 WARN / 偶发重试成功 → 可忽略。

## 错误处理

| 现象 | 应对 |
|------|------|
| 文件 > 1GB | `tail -10000` 取尾部，再按时间窗口缩 |
| 编码错误（GBK / GB2312） | `file_read(encoding="GBK")` 或 `iconv -f gbk -t utf-8` |
| 时间格式不规范 | 先 `head -20` 采样确认前缀 |
| 跨多文件分析（rotation） | `file_read` 先列文件，再按修改时间倒序选 |
| 实时滚动需求 | 不在本 Skill 范围，用 cron-scheduler + shell_exec 起定时任务 |
