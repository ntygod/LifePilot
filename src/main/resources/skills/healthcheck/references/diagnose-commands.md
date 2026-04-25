# 系统健康检查命令速查（按平台）

## 系统概览

### Windows

```bash
shell.exec(command="systeminfo | findstr /B /C:\"OS\" /C:\"System\" /C:\"Total Physical\"")
```

### Linux

```bash
shell.exec(command="uname -a")
```

## 资源使用

### Windows

```bash
shell.exec(command="powershell -c \"Get-PSDrive -PSProvider FileSystem | Format-Table Name,Used,Free -AutoSize\"")
shell.exec(command="powershell -c \"Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 20 Name,Id,@{N='MemMB';E={[math]::Round($_.WorkingSet64/1MB)}} | Format-Table -AutoSize\"")
```

### Linux

```bash
shell.exec(command="df -h && free -h && uptime")
shell.exec(command="ps aux --sort=-%mem | head -20")
```

## 服务状态

```bash
shell.exec(command="netstat -ano | findstr /R \"8080 3306 5432 6379 11434\"")
shell.exec(command="jps -l")
```

## 日志异常扫描

```bash
shell.exec(command="powershell -c \"Get-Content ~/.zhiwei/logs/lifepilot.log -Tail 100 | Select-String 'ERROR|Exception'\"")
```

## 诊断报告结构

按严重程度分类输出：

```
## 诊断报告

### 严重（需立即处理）
- 问题描述 + 修复命令

### 警告（需关注）
- 问题描述 + 建议

### 正常
- 各项指标正常值
```

## 常见错误处理

- **命令不存在** → 提供替代命令（如 `netstat` 不可用时用 `ss`）
- **权限不足** → 提示用户以管理员身份运行
- **服务未启动** → 给出启动命令
