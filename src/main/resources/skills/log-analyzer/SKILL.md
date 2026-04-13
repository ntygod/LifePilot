---
id: log-analyzer
name: "日志分析"
description: "应用日志分析、错误追踪与模式识别。用户说「分析日志」「查看日志」「报错了」「查错误日志」「日志统计」「排查问题」时使用。不适用于系统级健康检查（用 healthcheck）或代码调试（用 code-assistant）。"
version: "2.0.0"
suggested-tools:
  - shell.exec
  - file.read
  - file.list
---

# 日志分析指南

分析应用日志文件，追踪错误和识别模式。

## 适用场景

- 排查应用错误和异常
- 分析日志中的模式和趋势
- 统计错误频率和分布
- 从大量日志中提取关键信息

## 不适用场景

- 系统级健康检查（CPU/内存/磁盘） → 用 healthcheck
- 实时监控告警 → 用 cron-scheduler 配合 shell.exec
- 代码级 bug 调试 → 用 code-assistant

## 常用日志路径

- 知微应用日志：`~/.zhiwei/logs/lifepilot.log`
- Java 应用：`./logs/` 或 `./target/logs/`
- Nginx：`/var/log/nginx/error.log`
- Linux 系统日志：`/var/log/syslog`
- Windows 事件日志：通过 `powershell -c "Get-EventLog -LogName Application -Newest 50"` 查看

## 工作流

### 1. 定位日志文件

```
file.list(action="list", path="~/.zhiwei/logs", pattern="*.log")
```

### 2. 快速扫描错误

**Windows：**
```bash
shell.exec(command="powershell -c \"Get-Content ~/.zhiwei/logs/lifepilot.log -Tail 500 | Select-String 'ERROR|Exception'\"")
```

**Linux：**
```bash
shell.exec(command="grep -rn -E 'ERROR|FATAL|Exception' /path/to/logs/ -C 3")
```

### 3. 统计错误分布

**Windows：**
```bash
shell.exec(command="powershell -c \"Get-Content app.log | Select-String 'ERROR' | Group-Object { ($_ -split '\\s+')[-1] } | Sort-Object Count -Descending | Select-Object -First 20 Count,Name\"")
```

**Linux：**
```bash
shell.exec(command="grep 'ERROR' app.log | awk '{print $NF}' | sort | uniq -c | sort -rn | head -20")
```

### 4. 生成分析报告

按以下结构输出：

```
## 概览
- 日志时间范围：
- 总行数：
- 错误数：

## 关键错误（需立即处理）
1. 错误描述（出现 N 次）
   - 首次出现：时间
   - 堆栈摘要：...

## 错误趋势
- 频率变化描述

## 重复模式
- 反复出现的错误模式

## 修复建议
1. 建议 1
2. 建议 2
```

## 规则

- 分析结果中引用的日志内容必须是实际读取到的，不编造日志行
- 大日志文件用 `file.read` 的 `startLine`/`endLine` 读取指定范围，不一次性全量加载
- 输出中的 IP、用户名等敏感信息脱敏处理
- 二进制日志文件跳过，只处理文本日志
- 错误严重程度分级：需立即处理 / 需关注 / 可忽略

## 常见错误处理

- **日志文件过大** → 先读取尾部最近的 500-1000 行，再按需扩展
- **编码错误** → 尝试不同编码读取
- **日志格式不规范** → 先采样几行确认分隔符和时间格式
