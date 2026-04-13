---
id: healthcheck
name: "系统健康检查"
description: "系统资源监控、服务状态检查与诊断报告。用户说「系统检查」「健康检查」「系统慢了」「诊断一下」「内存不够了」「磁盘满了」「端口占用」时使用。不适用于应用层 bug 调试（用 code-assistant）或日志分析（用 log-analyzer）。"
version: "2.0.0"
suggested-tools:
  - shell.exec
  - file.read
---

# 系统健康检查指南

执行系统诊断、资源监控和服务状态检查。

## 适用场景

- 系统运行缓慢排查
- 定期系统巡检
- 部署前/后健康验证
- 资源使用监控（CPU / 内存 / 磁盘）

## 不适用场景

- 应用层 bug 调试 → 用 code-assistant
- 日志分析 → 用 log-analyzer
- 知微运行时信息（Skill/工具/工作流数量）→ 用 introspection

## 工作流

按顺序执行以下检查：

### 1. 系统概览

**Windows：**
```bash
shell.exec(command="systeminfo | findstr /B /C:\"OS\" /C:\"System\" /C:\"Total Physical\"")
```

**Linux：**
```bash
shell.exec(command="uname -a")
```

### 2. 资源使用

**Windows：**
```bash
shell.exec(command="powershell -c \"Get-PSDrive -PSProvider FileSystem | Format-Table Name,Used,Free -AutoSize\"")
shell.exec(command="powershell -c \"Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 20 Name,Id,@{N='MemMB';E={[math]::Round($_.WorkingSet64/1MB)}} | Format-Table -AutoSize\"")
```

**Linux：**
```bash
shell.exec(command="df -h && free -h && uptime")
shell.exec(command="ps aux --sort=-%mem | head -20")
```

### 3. 服务状态

```bash
shell.exec(command="netstat -ano | findstr /R \"8080 3306 5432 6379 11434\"")
shell.exec(command="jps -l")
```

### 4. 日志异常扫描

```bash
shell.exec(command="powershell -c \"Get-Content ~/.zhiwei/logs/lifepilot.log -Tail 100 | Select-String 'ERROR|Exception'\"")
```

### 5. 生成诊断报告

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

## 规则

- 按顺序执行全部检查项，不跳过
- 报告中的数据必须来自实际命令输出，不编造数值
- 给出具体的修复命令，不只说"请修复"
- 区分 Windows 和 Linux 命令，根据当前平台选择

## 常见错误处理

- **命令不存在** → 提供替代命令（如 `netstat` 不可用时用 `ss`）
- **权限不足** → 提示用户以管理员身份运行
- **服务未启动** → 给出启动命令
