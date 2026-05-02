---
name: log-analyzer
description: 当用户要分析应用日志、排查错误、追踪异常、统计错误频率分布或从大量日志中提取关键信息时使用。
version: 2.1.0
metadata:
  zhiwei:
    tags:
      - log
      - error-tracking
      - debugging
      - pattern
      - stack-trace
    suggested_tools:
      - shell_exec
      - file_read
---

# 日志分析指南

`shell_exec` 用 grep / Select-String 扫日志，`file_read` 按行读关键段。**领域强化：报告引用的日志原文必须实际读到，并且对敏感信息（IP / 用户名 / Token / 邮箱 / 手机号）脱敏后输出。**

## 适用场景

- 排查报错（"刚才报错了 / 看下错误日志"）
- 追踪异常 + 堆栈（"X 这个异常哪来的 / 看完整堆栈"）
- 错误频率统计（"这周 ERROR 多少次 / 最高频是哪个"）
- 提取关键事件（启动 / 关闭 / 崩溃 / 慢查询）
- 时间窗口过滤（"昨天下午 3 点的日志"）
- 多日志关联（一次请求跨多文件）

## 不适用场景

- 系统级资源诊断 → healthcheck
- 代码级 bug 调试 → code-assistant
- 实时监控告警 → cron-scheduler + shell_exec

## 工作流

1. **定位日志**：`file_read` 列 log 目录；常见路径见参考（`/var/log` / 知微 dataDir 下 logs 等）
2. **快速扫描**：按平台用对应工具的 ERROR/Exception 模式匹配抓异常行（具体命令模板见参考）
3. **统计分布**：按错误类型排序，前 20 看高频
4. **大文件分段读**：日志通常很大，`file_read` 带 `startLine` / `endLine` 按行号读，不一次性 `file_read` 整个文件
5. **时间窗口**：日志带时间戳的，先按时间前缀模式缩小范围（`<日期> <时间起>:` 匹配前缀）
6. **堆栈追踪**：异常行后续 `-A 20` 拿完整堆栈
7. **脱敏处理**：按参考里的正则替换 IP / Token / 邮箱 / 手机号 / 用户 ID 后再写入报告
8. **分级报告**：需立即处理（崩溃 / OOM）/ 需关注（高频 ERROR）/ 可忽略（常规 WARN）

## 详细参考

- 日志路径清单 + Windows/Linux 命令模板 + 时间窗口语法 + 报告结构：`{skill_dir}/references/log-commands.md`
