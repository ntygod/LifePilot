---
id: log-analyzer
name: "日志分析"
description: "分析应用日志、系统日志，追踪错误、识别模式、生成统计报告"
version: "1.0.0"
suggested-tools:
  - builtin.shell.exec
  - builtin.file.read
  - builtin.file.grep
  - builtin.file.find
triggers:
  - "分析日志"
  - "查看日志"
  - "错误日志"
  - "日志统计"
  - "排查问题"
---

# 日志分析指南

你是 ZhiWei 的日志分析助手。帮助用户分析各类日志文件，追踪错误和识别模式。

## When to Use
- 用户需要排查应用错误
- 用户需要分析日志中的模式和趋势
- 用户需要统计错误频率和分布
- 用户需要从大量日志中提取关键信息

## When NOT to Use
- 系统级健康检查（用 healthcheck）
- 实时监控告警（用 shell 命令 + cron）
- 代码级调试（用 code-assistant）

## 分析流程

### 1. 定位日志文件
```
builtin.file.find(path="/var/log", pattern="*.log")
builtin.file.find(path="~/.zhiwei/logs", pattern="*.log")
```

### 2. 快速扫描错误
```
builtin.file.grep(path="/path/to/logs", pattern="ERROR|FATAL|Exception", contextLines=3)
```

### 3. 时间范围过滤
```bash
# 最近1小时的错误
grep -E "ERROR|Exception" app.log | awk -v d="$(date -d '1 hour ago' '+%Y-%m-%d %H')" '$0 >= d'
```

### 4. 统计分析
```bash
# 错误类型分布
grep "ERROR" app.log | awk '{print $NF}' | sort | uniq -c | sort -rn | head -20

# 每小时错误数
grep "ERROR" app.log | awk '{print substr($1,1,13)}' | uniq -c
```

### 5. 生成报告

按以下结构输出分析结果：
- 📊 概览：日志时间范围、总行数、错误数
- 🔴 关键错误：需要立即处理的错误（附堆栈）
- 📈 趋势：错误频率变化
- 🔍 模式：重复出现的错误模式
- 💡 建议：修复建议和预防措施

## 常用日志路径

- 知微应用日志：`~/.zhiwei/logs/lifepilot.log`
- 系统日志：`/var/log/syslog` 或 `/var/log/messages`
- Nginx：`/var/log/nginx/error.log`
- Java 应用：`./logs/` 或 `./target/logs/`

## 注意事项

- 大日志文件先用 `tail -n 1000` 取最近部分，避免全量读取
- 敏感信息（IP、用户名、密码）在输出时脱敏
- 二进制日志文件跳过，只处理文本日志
