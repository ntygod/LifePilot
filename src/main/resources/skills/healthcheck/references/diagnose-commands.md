# 系统健康检查命令速查

按平台分两套；占位符 `<port>` `<process>` `<path>` 替换成具体值。

## 系统概览

### Windows PowerShell

```bash
# 系统信息 + 内存
shell.exec(command="systeminfo | findstr /B /C:\"OS Name\" /C:\"OS Version\" /C:\"Total Physical\"")
# 启动时长
shell.exec(command="powershell -c \"(Get-CimInstance Win32_OperatingSystem).LastBootUpTime\"")
```

### Linux bash

```bash
shell.exec(command="uname -a && uptime && cat /etc/os-release | head -5")
```

## CPU 与内存

### Windows

```bash
# CPU 实时负载（采 5 秒平均）
shell.exec(command="powershell -c \"(Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 1 -MaxSamples 5).CounterSamples.CookedValue | Measure-Object -Average | Select-Object Average\"", timeoutSeconds=15)
# 内存 Top 20 进程
shell.exec(command="powershell -c \"Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 20 Name,Id,@{N='MemMB';E={[math]::Round($_.WorkingSet64/1MB)}} | Format-Table -AutoSize\"")
# 物理内存使用
shell.exec(command="powershell -c \"Get-CimInstance Win32_OperatingSystem | Select-Object @{N='TotalGB';E={[math]::Round($_.TotalVisibleMemorySize/1MB,2)}},@{N='FreeGB';E={[math]::Round($_.FreePhysicalMemory/1MB,2)}}\"")
```

### Linux

```bash
# CPU + 负载 + 内存一次出
shell.exec(command="top -bn1 | head -5 && free -h")
# 内存 Top 20
shell.exec(command="ps aux --sort=-%mem | head -20")
# CPU Top 20
shell.exec(command="ps aux --sort=-%cpu | head -20")
```

## 磁盘空间

### Windows

```bash
# 各盘符使用情况
shell.exec(command="powershell -c \"Get-PSDrive -PSProvider FileSystem | Where-Object {$_.Used} | Select-Object Name,@{N='UsedGB';E={[math]::Round($_.Used/1GB,2)}},@{N='FreeGB';E={[math]::Round($_.Free/1GB,2)}} | Format-Table -AutoSize\"")
# 大目录排查（替换 <path>）
shell.exec(command="powershell -c \"Get-ChildItem '<path>' -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum | Select-Object @{N='SizeGB';E={[math]::Round($_.Sum/1GB,2)}}\"")
```

### Linux

```bash
shell.exec(command="df -h")
# 排前 20 大目录
shell.exec(command="du -h --max-depth=1 <path> 2>/dev/null | sort -rh | head -20")
# 大文件
shell.exec(command="find <path> -type f -size +100M -exec ls -lh {} \\; 2>/dev/null | head -20")
```

## 端口占用

### Windows

```bash
# 单端口（替换 <port>）
shell.exec(command="netstat -ano | findstr :<port>")
# 多端口（知微默认 8080，常见 3306/5432/6379/11434）
shell.exec(command="netstat -ano | findstr /R \":8080 :3306 :5432 :6379 :11434\"")
# 由 PID 查进程名
shell.exec(command="powershell -c \"Get-Process -Id <pid> | Select-Object Name,Path,StartTime\"")
```

### Linux

```bash
# 优先 ss（更快），netstat 兜底
shell.exec(command="ss -tlnp | grep :<port>  || netstat -tlnp | grep :<port>")
# 由 PID 查进程
shell.exec(command="ps -fp <pid>")
```

## 服务状态

### Windows

```bash
# Java 进程
shell.exec(command="jps -l")
# 命名服务
shell.exec(command="powershell -c \"Get-Service -Name '<service-name>' | Select-Object Name,Status,StartType\"")
```

### Linux

```bash
# systemd 服务
shell.exec(command="systemctl status <service-name> --no-pager")
# 进程是否在跑
shell.exec(command="pgrep -fl <process>")
```

## 日志快速扫错（细节走 log-analyzer）

```bash
# Windows
shell.exec(command="powershell -c \"Get-Content ~/.zhiwei/logs/lifepilot.log -Tail 100 | Select-String 'ERROR|Exception'\"")
# Linux
shell.exec(command="tail -200 ~/.zhiwei/logs/lifepilot.log | grep -E 'ERROR|FATAL|Exception'")
```

## 诊断报告结构

```
## 诊断报告

### 严重（需立即处理）
- 现象：<指标 + 实际数值>
- 修复：<具体命令>

### 警告（需关注）
- 现象 + 建议

### 正常
- 关键指标实际值
```

阈值参考：磁盘使用 > 95% / 内存使用 > 90% / 关键服务 down → 严重；CPU 持续 > 80% / 异常端口监听 → 警告。

## 错误处理

| 现象 | 应对 |
|------|------|
| `netstat` 不存在（精简 Linux） | 改用 `ss` |
| `ps aux` 权限不足 | 加 `sudo` 或提示用户用管理员/root 重试 |
| `systemctl` 不存在 | 老系统用 `service <name> status` |
| PowerShell 输出 CLIXML | stderr 走 PowerShell 远程序列化，不是命令真实输出，参考 stdout |
| 命令耗时 > 30 秒 | shell.exec 加 `background=true`，再用 shell.process(action=output) 拿结果 |
| 服务未启动 | 先给启动命令（`systemctl start <name>` / `Start-Service <name>`），再重测 |
