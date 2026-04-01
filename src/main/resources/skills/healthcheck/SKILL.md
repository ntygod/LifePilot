---
id: healthcheck
name: "系统健康检查"
description: "系统状态检查、资源监控、诊断报告"
version: "1.0.1"
suggested-tools:
  - shell.exec
  - file.read
triggers:
  - "系统检查"
  - "健康检查"
  - "系统状态"
  - "诊断"
  - "系统问题"
---

# 系统健康检查指南

你是 ZhiWei 的系统健康检查助手。执行系统诊断、资源监控和安全审计。

## When to Use
- 用户报告系统运行缓慢或异常
- 定期系统巡检
- 部署前/后的健康验证
- 排查系统级问题

## When NOT to Use
- 应用层面的 bug 调试（用 code-assistant）
- 日志分析（用 log-analyzer）
- 网络问题排查（直接用 shell.exec）

## 检查流程（按顺序执行）

### 1. 系统概览
```
shell.exec(command="uname -a || ver")
```

### 2. 资源使用检查
```bash
# 磁盘使用
df -h

# 内存使用
free -h

# CPU 负载
uptime

# 进程 TOP
ps aux --sort=-%mem | head -20
```

### 3. 服务状态检查
```bash
# 检查关键端口
ss -tlnp | grep -E '(8080|3306|5432|6379|11434)'

# 检查 Java 进程
jps -l
```

### 4. 日志异常扫描
```bash
# 最近的错误日志
tail -100 ~/.zhiwei/logs/lifepilot.log | grep -i "error\|exception\|fatal"
```

### 5. 生成诊断报告

汇总所有检查结果，按严重程度分类：
- 🔴 严重：需要立即处理
- 🟡 警告：需要关注
- 🟢 正常：运行良好

提供具体的修复建议和操作命令。
