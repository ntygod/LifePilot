---
name: healthcheck
description: 当用户要执行系统资源监控、服务状态检查、端口占用诊断、磁盘/内存使用排查或生成诊断报告时使用。关键词：系统检查、健康检查、系统慢了、诊断一下、内存不够、磁盘满了、端口占用、性能。应用层 bug 调试用 code-assistant，日志分析用 log-analyzer，知微运行时信息（Skill/工具数量）用 introspection。
version: 2.0.0
metadata:
  zhiwei:
    category: infrastructure
    priority: normal
    tags:
      - health
      - monitor
      - system-check
      - cpu
      - memory
      - disk
      - diagnostics
    suggested_tools:
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

1. **按顺序全量检查**：系统概览 → 资源使用 → 服务状态 → 日志异常扫描
2. **分平台选命令**：Windows 用 PowerShell，Linux 用 bash，命令模板见参考
3. **数据来自实际输出**：报告中的数值必须来自实际命令输出，不编造
4. **给具体修复命令**：不只说"请修复"，给可执行命令
5. **生成分级诊断报告**：严重 / 警告 / 正常 三档

## 详细参考

- Windows / Linux 命令速查 + 诊断报告结构 + 错误处理：`{skill_dir}/references/diagnose-commands.md`
</content>
</invoke>