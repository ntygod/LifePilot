# 系统诊断参考

> 系统命令（top/free/df/netstat/ps）直接用 shell_exec。本文档仅含诊断策略与平台差异。

## 平台差异

| 场景 | Linux | Windows |
|------|-------|---------|
| 系统信息 | `uname -a && uptime` | `systeminfo` |
| 进程列表 | `ps aux` / `pgrep` | `Get-Process` |
| 磁盘 | `df -h` / `du` | `Get-PSDrive` |
| 端口 | `ss -tlnp`（优先）/ `netstat` | `netstat -ano` |
| 服务 | `systemctl status <name>` | `Get-Service <name>` |
| 日志扫错 | `tail -200 \| grep ERROR` | `Get-Content -Tail 100 \| Select-String` |

`netstat` 不存在时改用 `ss`；`systemctl` 不存在时用 `service <name> status`。

## 诊断优先级

1. **严重**（磁盘 >95% / 内存 >90% / 关键服务 down）→ 先恢复再排查
2. **警告**（CPU 持续 >80% / 异常端口监听）→ 定位进程，判断是否正常
3. **正常** → 报告关键指标即可

耗时 >30 秒的命令加 `background=true`，用 shell_process(action=output) 拿结果。

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

## 可用脚本

- `{skill_scripts_dir}/diagnose.py [--json]` — 一键收集 CPU/内存/磁盘/端口/运行时长，跨平台（Windows/Linux）。`--json` 输出 JSON 供 LLM 解析
