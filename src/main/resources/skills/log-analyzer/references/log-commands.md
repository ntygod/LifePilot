# 日志分析命令速查

## 常用日志路径

- 知微应用日志：`~/.zhiwei/logs/lifepilot.log`
- Java 应用：`./logs/` 或 `./target/logs/`
- Nginx：`/var/log/nginx/error.log`
- Linux 系统日志：`/var/log/syslog`
- Windows 事件日志：通过 `powershell -c "Get-EventLog -LogName Application -Newest 50"` 查看

## 定位日志文件

```
file.list(action="list", path="~/.zhiwei/logs", pattern="*.log")
```

## 快速扫描错误

### Windows

```bash
shell.exec(command="powershell -c \"Get-Content ~/.zhiwei/logs/lifepilot.log -Tail 500 | Select-String 'ERROR|Exception'\"")
```

### Linux

```bash
shell.exec(command="grep -rn -E 'ERROR|FATAL|Exception' /path/to/logs/ -C 3")
```

## 统计错误分布

### Windows

```bash
shell.exec(command="powershell -c \"Get-Content app.log | Select-String 'ERROR' | Group-Object { ($_ -split '\s+')[-1] } | Sort-Object Count -Descending | Select-Object -First 20 Count,Name\"")
```

### Linux

```bash
shell.exec(command="grep 'ERROR' app.log | awk '{print $NF}' | sort | uniq -c | sort -rn | head -20")
```

## 分析报告结构

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

## 常见错误处理

- **日志文件过大** → 先读取尾部最近的 500-1000 行，再按需扩展
- **编码错误** → 尝试不同编码读取
- **日志格式不规范** → 先采样几行确认分隔符和时间格式
