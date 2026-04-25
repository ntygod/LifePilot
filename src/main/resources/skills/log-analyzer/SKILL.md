---
name: log-analyzer
description: 当用户要分析应用日志、排查错误、追踪异常、统计错误频率分布或从大量日志中提取关键信息时使用。关键词：分析日志、查看日志、报错了、查错误日志、日志统计、排查问题、堆栈追踪、ERROR、Exception。系统级健康检查用 healthcheck，代码级 bug 调试用 code-assistant，实时监控告警用 cron-scheduler + shell.exec。
version: 2.0.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - log
      - error-tracking
      - debugging
      - pattern
      - stack-trace
    suggested_tools:
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

## 工作流

1. **定位日志**：`file.list` 列出 log 文件；内置路径见参考
2. **快速扫描**：Windows `Select-String`、Linux `grep -E`，抓 ERROR/Exception
3. **统计分布**：按错误类型排序，前 20 条看高频问题
4. **大文件分段读**：`file.read` 带 `startLine`/`endLine`，不一次性全量加载
5. **引用真实日志**：报告中的日志内容必须实际读到的，不编造
6. **敏感信息脱敏**：IP、用户名、Token 不原样输出
7. **分级报告**：需立即处理 / 需关注 / 可忽略

## 详细参考

- 日志路径清单 + Windows/Linux 命令模板 + 报告结构 + 错误处理：`{skill_dir}/references/log-commands.md`
</content>
</invoke>