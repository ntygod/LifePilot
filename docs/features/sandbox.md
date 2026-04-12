# 代码执行沙箱 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.sandbox`
> **最后更新**：2026-03

## 1. 功能概述

沙箱模块让 Agent 能够在安全隔离的环境中执行用户请求的代码。支持 Python、JavaScript、Shell 三种语言，提供两种隔离策略（进程隔离和 Docker 容器隔离），执行前自动进行危险操作预检，执行过程全程审计记录。作为 CRITICAL 风险级别工具，每次执行都需要经过护栏系统确认。

## 2. 核心特性

### 2.1 多语言支持

支持三种编程语言的代码执行：
- Python（python3）：数据分析、脚本计算
- JavaScript（node）：前端逻辑验证、JSON 处理
- Shell（bash）：系统命令、文件操作

语言运行时路径可通过配置自定义。

### 2.2 双重隔离策略

ProcessBooter（默认）：基于 ProcessBuilder 的轻量级进程隔离，零外部依赖。在统一工作目录下的 `sandbox/` 子目录中创建隔离会话目录（默认 `~/.zhiwei/workspace/sandbox/`），清洗环境变量仅保留 PATH，超时强制终止进程。

DockerBooter：基于 Docker 容器的强隔离，提供内存/CPU/磁盘资源限制和网络隔离（默认禁用网络）。需要宿主机安装 Docker，适合对安全性要求更高的场景。

### 2.3 危险操作预检

CodeValidator 在代码执行前进行正则模式匹配预检，按语言维护危险模式列表：
- Python：检测 `os.system`、`subprocess`、`eval`、`exec`、文件删除等
- JavaScript：检测 `child_process`、`eval`、`fs.unlink` 等
- Shell：检测 `rm -rf`、`dd`、`mkfs`、网络命令等

违规分为 CRITICAL（拒绝执行）、HIGH、MEDIUM 三个级别。

### 2.4 会话级实例复用

同一会话的多次代码执行复用同一个沙箱实例，避免频繁创建/销毁。会话支持 TTL 自动续期（每次访问刷新过期时间），过期会话自动清理并释放资源。

### 2.5 执行审计

每次代码执行（含预检拒绝）都持久化审计记录到 SQLite，记录语言、代码哈希、执行状态、耗时、输出大小等信息。审计写入失败降级跳过，不影响主流程。

### 2.6 护栏集成

CodeExecuteTool 注册为 CRITICAL 风险级别的 BuiltinTool，执行前必须经过护栏系统的用户确认和二次验证，确保用户知晓并授权代码执行。

## 3. 使用场景

用户请求 Agent 进行数据计算时，Agent 生成 Python 代码并通过沙箱执行，返回计算结果。沙箱在执行前自动预检代码安全性，在隔离环境中运行，超时自动终止。

用户请求 Agent 验证一段 JavaScript 逻辑时，Agent 在沙箱中执行代码并返回输出。同一对话中的多次执行复用同一个沙箱会话，工作目录中的文件在会话期间持久保留。

用户请求 Agent 执行系统命令查看文件信息时，Agent 生成 Shell 脚本在沙箱中执行。CodeValidator 预检会拦截危险命令（如 `rm -rf /`），拒绝执行并告知用户。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `zhiwei.workspace-dir` | `${zhiwei.data-dir}/workspace` | 统一工作目录，沙箱会话目录创建在 `{workspace}/sandbox/` 下 |
| `lifepilot.sandbox.enabled` | `true` | 沙箱总开关 |
| `lifepilot.sandbox.booter` | `process` | 隔离策略（process / docker） |
| `lifepilot.sandbox.supported-languages` | `[python, javascript, shell]` | 支持的语言 |
| `lifepilot.sandbox.execution-timeout-seconds` | `30` | 执行超时（秒） |
| `lifepilot.sandbox.max-output-bytes` | `65536` | 输出最大字节数（64KB） |
| `lifepilot.sandbox.session.ttl-seconds` | `600` | 会话 TTL（10 分钟） |
| `lifepilot.sandbox.session.max-active-sessions` | `5` | 最大活跃会话数 |
| `lifepilot.sandbox.validator.enabled` | `true` | 预检开关 |
| `lifepilot.sandbox.validator.reject-critical` | `true` | 是否拒绝 CRITICAL 违规 |
| `lifepilot.sandbox.docker.memory-limit-mb` | `256` | Docker 内存限制 |
| `lifepilot.sandbox.docker.cpu-limit` | `1.0` | Docker CPU 限制 |
| `lifepilot.sandbox.docker.network-enabled` | `false` | Docker 网络开关 |

## 5. 限制与未来方向

当前限制：
- 仅支持 Python、JavaScript、Shell 三种语言
- ProcessBooter 的隔离强度有限，依赖操作系统进程隔离
- 危险操作预检基于正则匹配，可能存在误报或漏报
- 不支持代码执行的实时输出流（仅返回最终结果）

未来方向：
- 支持更多语言（Java、Go、Rust）
- 基于 AST 的深度代码安全分析
- 实时输出流（SSE 推送执行过程）
- 沙箱资源使用统计和配额管理
