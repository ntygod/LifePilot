# 日志分析参考

> 日志检索/过滤用 shell_exec + grep/awk。本文档仅含日志特有领域知识。

## 常用日志路径

- 知微：`<dataDir>/logs/` 下（lifepilot.log / agent.log / error.log）
- Java 应用：`./logs/`、Spring Boot：`./logs/spring.log`
- Nginx：`/var/log/nginx/error.log`、`/var/log/nginx/access.log`
- Linux 系统：`/var/log/syslog`、`/var/log/messages`
- Windows 事件：`Get-EventLog -LogName Application -Newest <N>`

## 分析策略

1. 大文件（>1GB）先 tail -10000 取尾部，再按时间窗口缩小
2. 时间格式不规范时先 head -20 采样确认前缀
3. 跨多文件（rotation）先 file_read(list) 列文件，按修改时间倒序选
4. 编码错误（GBK）时 file_read(encoding="GBK") 或 iconv
5. 实时滚动需求走 cron + shell_exec 定时任务

## 脱敏正则（输出前必须处理）

| 类型 | 正则 | 替换为 |
|------|------|--------|
| IPv4 | `(\d{1,3}\.){3}\d{1,3}` | `xxx.xxx.xxx.***` |
| 邮箱 | `[\w.+-]+@[\w-]+\.[\w.-]+` | `***@<domain>` |
| 手机号 | `1[3-9]\d{9}` | `1xx****<后4位>` |
| Token/Key | `(token\|key\|secret)["':=\s]+[A-Za-z0-9+/=]{16,}` | `<key>=***` |

报告中引用日志原文时先 sed/正则替换敏感字段再输出。

## 分析报告结构

```
## 概览
- 日志范围：<起止时间>，总行数：<count>，ERROR 行数：<count>

## 关键错误（需立即处理）
1. <错误类型>（<次数>）— 首次：<时间>，堆栈摘要：<3-5 行核心>

## 错误趋势
- <时间分布或频率变化>

## 修复建议
1. <可执行动作>
```

分级：崩溃/OOM/业务关键链路报错 → 立即；高频 ERROR → 关注；常规 WARN → 可忽略。

## 可用脚本

- `{skill_scripts_dir}/log-redact.py` — 从 stdin 读日志，正则脱敏后输出。用法：`shell_exec(command="cat app.log | python {skill_scripts_dir}/log-redact.py")`
