# 代码执行沙箱

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应 Phase 4 模块 16。

> 📋 详细架构设计参见 [architecture/sandbox.md](../architecture/sandbox.md)。

## 1. 概述

ZhiWei 代码执行沙箱让 AI Agent 能够安全地运行用户提供的代码片段。支持 Python、JavaScript 和 Shell 三种语言，适用于数据处理、计算、脚本自动化等场景。

核心能力：
- 三层安全防护：代码预检 → 用户确认 → 沙箱隔离执行
- 两种隔离方案：ProcessBuilder 轻量沙箱（默认）和 Docker 容器强隔离（可选）
- 会话级沙箱复用，避免冷启动开销
- 全链路审计日志，执行可追溯
- 与工具系统深度集成，Agent 透明调用

## 2. 快速开始

### 2.1 默认配置即可使用

代码执行沙箱默认启用，使用 ProcessBuilder 轻量方案，无需额外安装。
只需确保系统中安装了对应语言的运行时：

- Python：`python3` 命令可用
- JavaScript：`node` 命令可用
- Shell：`bash` 命令可用

### 2.2 Agent 自动调用

当用户请求涉及代码执行时，Agent 会自动调用 `code.execute` 工具：

```
用户：帮我用 Python 计算 1 到 100 的质数之和

Agent 调用 code.execute：
  language: python
  code: |
    primes = [n for n in range(2, 101) if all(n % i != 0 for i in range(2, int(n**0.5)+1))]
    print(f"质数列表: {primes}")
    print(f"质数之和: {sum(primes)}")

执行结果：
  质数列表: [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97]
  质数之和: 1060
```

由于代码执行是 CRITICAL 风险操作，执行前会请求用户确认。

## 3. 安全模型

### 3.1 三层防御

代码执行经过三道安全关卡，任何一层拦截即终止执行：

**第一层：代码预检（CodeValidator）**
- 静态扫描代码中的危险模式（如 `rm -rf /`、`os.system()`、`eval()`）
- CRITICAL 级违规直接拒绝，不进入后续流程
- 检测规则可配置，支持自定义

**第二层：护栏审批（GuardrailPolicy）**
- 代码执行工具标记为 CRITICAL 风险等级
- 需要用户明确确认执行意图
- 需要二次验证（防止误操作）

**第三层：沙箱隔离（SandboxBooter）**
- 代码在隔离环境中执行，不直接访问宿主系统
- 超时自动终止，防止无限循环
- 输出截断，防止内存溢出

### 3.2 代码预检示例

```
用户代码：
  import os
  os.system("rm -rf /")

预检结果：
  ❌ CRITICAL 违规：检测到 os.system() 系统命令调用
  ❌ CRITICAL 违规：检测到 rm -rf 危险删除操作
  → 执行被拒绝
```

```
用户代码：
  import requests
  data = requests.get("https://api.example.com/data").json()
  print(data)

预检结果：
  ⚠️ MEDIUM 警告：检测到 requests 网络请求库
  → 记录日志，继续执行（需用户确认）
```

## 4. 沙箱类型

### 4.1 ProcessBuilder 轻量沙箱（默认）

基于 Java ProcessBuilder，零外部依赖，适用于大多数场景。

**特点**：
- 无需安装任何额外软件
- 启动速度极快（<10ms）
- 每次执行在独立临时目录中运行
- 超时自动终止进程
- 环境变量清洗，不暴露敏感信息

**局限**：
- 无法限制 CPU / 内存使用
- 无法限制网络访问
- 隔离强度依赖操作系统权限

**适用场景**：开发调试、可信代码执行、简单计算和数据处理。

### 4.2 Docker 容器沙箱（可选）

基于 Docker 容器，提供完整的资源隔离，需要安装 Docker Engine。

**特点**：
- 完整的文件系统隔离（容器内独立文件系统）
- CPU / 内存 / 磁盘资源限制
- 默认断网，防止数据外泄
- 只读根文件系统，仅工作目录可写
- 容器退出后自动清理

**配置方式**：

```yaml
lifepilot:
  sandbox:
    booter: docker
    docker:
      memory-limit-mb: 256
      cpu-limit: 1.0
      network-enabled: false
```

**适用场景**：生产环境、不可信代码执行、需要严格资源控制的场景。

### 4.3 Docker 不可用时的降级

如果配置了 Docker 沙箱但 Docker Engine 不可用：
- `booter: docker` 时：拒绝执行，提示用户安装 Docker 或切换到 process 模式
- `booter: process` 时：正常使用 ProcessBuilder 方案

## 5. 支持的语言

| 语言 | 运行时命令 | 文件扩展名 | 典型场景 |
|------|-----------|-----------|---------|
| Python | `python3` | `.py` | 数据处理、科学计算、API 调用、文件批处理 |
| JavaScript | `node` | `.js` | JSON 处理、文本转换、Web 数据解析 |
| Shell | `bash` | `.sh` | 系统管理、文件操作、命令行自动化 |

运行时路径可通过配置自定义：

```yaml
lifepilot:
  sandbox:
    runtime-paths:
      python: "/usr/local/bin/python3.12"
      javascript: "/usr/local/bin/node"
      shell: "/bin/bash"
```

## 6. 会话级沙箱复用

同一 Agent 会话内的多次代码执行复用同一个沙箱实例，好处是：

- 前一步安装的依赖（如 `pip install pandas`）在后续步骤中可用
- 前一步生成的文件在后续步骤中可读取
- 避免每次执行的冷启动开销

### 6.1 会话生命周期

```
创建会话沙箱 → 执行代码 → [TTL 重置] → 执行代码 → [TTL 重置] → ... → 空闲超时 → 自动销毁
```

- 默认 TTL：10 分钟（每次执行自动续期）
- 最大同时活跃会话：5 个
- 超出限制时拒绝创建新会话，提示用户等待

### 6.2 多步骤执行示例

```
步骤 1：安装依赖
  language: python
  code: |
    import subprocess
    subprocess.run(["pip", "install", "pandas"], check=True)
    print("pandas 安装成功")

步骤 2：处理数据（复用同一沙箱，pandas 已可用）
  language: python
  code: |
    import pandas as pd
    df = pd.DataFrame({"name": ["Alice", "Bob"], "score": [95, 87]})
    df.to_csv("/workspace/result.csv", index=False)
    print(df.describe())

步骤 3：读取结果（复用同一沙箱，文件已存在）
  language: python
  code: |
    with open("/workspace/result.csv") as f:
        print(f.read())
```

## 7. 审计日志

每次代码执行都会记录审计日志，包含：
- 执行时间、会话 ID
- 语言类型、代码哈希（SHA-256，不存储原始代码）
- 沙箱类型（process / docker）
- 预检结果（通过/违规数量）
- 执行结果（退出码、输出长度、耗时）
- 执行状态（COMPLETED / TIMEOUT / FAILED / REJECTED）

审计日志存储在 SQLite 中，可通过 API 查询。

## 8. 配置

```yaml
lifepilot:
  sandbox:
    enabled: true                          # 是否启用代码执行沙箱
    booter: process                        # 沙箱类型：process / docker
    supported-languages:                   # 支持的语言
      - python
      - javascript
      - shell
    execution-timeout-seconds: 30          # 执行超时
    max-output-bytes: 65536                # 输出最大字节数（64KB）
    session:
      ttl-seconds: 600                     # 会话 TTL（10 分钟）
      max-active-sessions: 5              # 最大同时活跃会话
      cleanup-interval-seconds: 60         # 清理扫描间隔
    validator:
      enabled: true                        # 是否启用代码预检
      reject-critical: true                # CRITICAL 违规是否直接拒绝
    docker:                                # Docker 沙箱配置
      memory-limit-mb: 256                 # 内存限制
      cpu-limit: 1.0                       # CPU 限制
      disk-limit-mb: 512                   # 磁盘限制
      network-enabled: false               # 是否允许网络
      image-prefix: "lifepilot/sandbox-"   # 镜像前缀
    runtime-paths:                         # 语言运行时路径
      python: "python3"
      javascript: "node"
      shell: "bash"
```

## 9. 使用场景

### 9.1 数据处理

```
用户：帮我分析这组销售数据，计算每月平均销售额

Agent 生成 Python 代码，调用 pandas 进行数据分析，
输出统计结果和趋势图描述。
```

### 9.2 文件批处理

```
用户：把 ~/downloads 下所有 .csv 文件合并成一个

Agent 生成 Shell 脚本，遍历目录合并文件。
（注意：ProcessBooter 下需要用户确认文件路径访问权限）
```

### 9.3 计算与验证

```
用户：验证一下这个正则表达式能不能匹配所有邮箱格式

Agent 生成 Python 测试脚本，用多组测试用例验证正则表达式。
```

### 9.4 API 数据获取

```
用户：帮我查一下今天的汇率

Agent 生成 Python 代码调用公开 API。
（注意：需要 Docker 沙箱允许网络访问，或 ProcessBooter 默认允许网络）
```
