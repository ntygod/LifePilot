---
name: healthcheck
description: 当用户要执行系统资源监控、服务状态检查、端口占用诊断、磁盘/内存使用排查或生成诊断报告时使用。关键词：系统检查、健康检查、系统慢了、诊断一下、内存不够、磁盘满了、端口占用、性能、查看系统状态、服务挂了、卡了。应用层 bug 调试用 code-assistant，日志分析用 log-analyzer，知微自身运行时信息（Skill/工具数量）直接调 system.status 工具。
version: 2.1.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - health
      - monitor
      - system-check
      - cpu
      - memory
      - disk
      - port
      - diagnostics
    suggested_tools:
      - shell.exec
      - file.read
---

# 系统健康检查指南

通过 `shell.exec` 跑系统命令做资源监控、服务状态、端口诊断。**领域强化：报告中的数值（CPU%、内存 GB、磁盘占比、端口号）必须出自实际命令输出，不凭印象。**

## 适用场景

- 系统运行缓慢排查（CPU / 内存 / 磁盘 / IO）
- 服务状态检查（systemd / 进程 / 端口）
- 端口占用诊断（端口被谁占了）
- 磁盘空间排查（哪些目录大）
- 部署前 / 后健康验证
- 定期巡检报告

## 不适用场景

- 应用层 bug 调试 → code-assistant
- 日志内容分析 → log-analyzer
- 知微自身运行时信息 → 直接调 `system.status` 工具

## 工作流（按用户表达分流）

| 用户表达 | 路径 |
|---|---|
| "系统慢了 / 卡了" | CPU + 内存 + 磁盘 IO 一次性看 |
| "磁盘满了" | 按目录大小排序 → 给清单 |
| "端口被占了 / X 端口冲突" | netstat / lsof 查占用进程 |
| "X 服务挂了 / 没响应" | 进程 + 端口 + 日志 |
| "做个全面巡检" | CPU / 内存 / 磁盘 / 网络 / 关键服务一遍 |

各路径要点：

- **按目标平台选命令**：跑前确认是 Windows 还是 Linux/macOS，命令选对应平台标准命令；具体速查见参考
- **给具体修复命令**：不只说"内存不足"，给"杀进程 X / 清缓存 / 加 swap"等具体动作
- **分级诊断报告**：
  - 严重（立即处理）：服务 down / 磁盘 > 95% / 内存 > 90%
  - 警告（关注）：CPU 持续高 / 端口异常占用
  - 正常（仅信息）：常规指标正常范围

## 详细参考

- Windows / Linux 命令速查 + 诊断报告结构 + 错误处理：`{skill_dir}/references/diagnose-commands.md`
