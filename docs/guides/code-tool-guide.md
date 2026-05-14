# 知微 Code 工具使用指南

> 本文档面向知微（ZhiWei）的 Skill 作者和工具集成开发者，介绍 `code` 内置工具的完整用法、设计意图和最佳实践。
> 如果你正在为 LLM 编写需要执行代码（数据分析、计算、脚本）的 Skill，本文档是必读。

---

## 目录

- [工具概览](#工具概览)
- [快速入门](#快速入门)
- [action=exec — 代码执行](#actionexec--代码执行)
- [持久内核（kernelId）](#持久内核kernelid)
- [内核管理 action](#内核管理-action)
- [安全与权限](#安全与权限)
- [沙箱后端](#沙箱后端)
- [配置参考](#配置参考)
- [code vs shell.exec 选择指南](#code-vs-shellexec-选择指南)
- [常见陷阱与最佳实践](#常见陷阱与最佳实践)
- [为 Skill 编写提示词参考](#为-skill-编写提示词参考)

---

## 工具概览

`code` 是知微的代码执行工具，通过沙箱环境安全地运行 Python / JavaScript / Shell 代码。

| 特性 | 说明 |
|------|------|
| 支持语言 | Python（默认）、JavaScript、Shell |
| 执行模式 | 一次性沙箱（默认）、持久内核（传 kernelId） |
| 沙箱后端 | Process（本机进程）、Docker（容器隔离） |
| 安全层 | CodeValidator 预检 + CommandGuard 护栏 + 审计持久化 |
| 预装库（Python） | pandas、numpy、scipy、scikit-learn、matplotlib、seaborn、openpyxl、python-docx、python-pptx、pypdf、pillow、requests、httpx、beautifulsoup4 |

设计上的定位：
- `code` 面向**计算和数据处理**：数据分析、图表生成、文件格式转换、数学计算
- `shell.exec` 面向**系统操作**：运行 CLI 工具、管理进程、操作文件系统
- 两者共用同一套 CommandGuard 安全规则，不存在"走 code 绕过 shell 护栏"的漏洞

---

## 快速入门

### 场景一：一次性执行 Python 代码

```json
{
  "tool": "code",
  "args": {
    "action": "exec",
    "code": "import pandas as pd\ndf = pd.DataFrame({'x': [1,2,3], 'y': [4,5,6]})\nprint(df.describe())"
  }
}
```

返回结构（成功）：
```json
{
  "exitCode": 0,
  "stdout": "         x    y\ncount  3.0  3.0\nmean   2.0  5.0\n...",
  "stderr": "",
  "durationMs": 1234,
  "state": "COMPLETED",
  "workingDirectory": "/Users/me/.zhiwei/sandbox"
}
```

### 场景二：持久内核 — 跨调用保持变量

```json
// 第一次：加载数据
{
  "tool": "code",
  "args": {
    "action": "exec",
    "kernelId": "analysis-1",
    "code": "import pandas as pd\ndf = pd.read_csv('/path/to/data.csv')\nprint(f'行数: {len(df)}')"
  }
}

// 第二次：基于已有 df 继续分析（变量保持）
{
  "tool": "code",
  "args": {
    "action": "exec",
    "kernelId": "analysis-1",
    "code": "print(df.groupby('category').mean())"
  }
}
```

### 场景三：执行 JavaScript

```json
{
  "tool": "code",
  "args": {
    "action": "exec",
    "language": "javascript",
    "code": "const result = Array.from({length: 10}, (_, i) => i * i);\nconsole.log(JSON.stringify(result));"
  }
}
```

---

## action=exec — 代码执行

### 参数一览

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `action` | string | ❌ | `"exec"` | 操作类型，默认就是 exec |
| `code` | string | ✅（exec 时） | — | 要执行的代码 |
| `language` | string | ❌ | `"python"` | 编程语言：python / javascript / shell |
| `timeoutSeconds` | integer | ❌ | 30 | 执行超时（秒） |
| `kernelId` | string | ❌ | — | 持久内核 ID，传入则跨调用保持变量 |

### 返回结构

**成功（exitCode=0）**：
```json
{
  "exitCode": 0,
  "stdout": "计算结果...",
  "stderr": "",
  "durationMs": 456,
  "state": "COMPLETED",
  "workingDirectory": "/path/to/sandbox"
}
```

**失败（exitCode≠0）**：
```json
{
  "exitCode": 1,
  "stdout": "",
  "stderr": "Traceback (most recent call last):\n  File ...\nNameError: name 'x' is not defined",
  "durationMs": 89,
  "state": "COMPLETED",
  "workingDirectory": "/path/to/sandbox"
}
```

注意：`state=COMPLETED` 表示进程正常退出（不管 exitCode 是什么），`state=TIMEOUT` 表示超时被杀。

**持久内核模式的返回**多一个字段：
```json
{
  "kernelId": "analysis-1",
  "stdout": "...",
  "stderr": "",
  "durationMs": 200,
  "persistent": true
}
```

### 执行流程

```
参数提取
  ↓
运行时状态检查（PythonRuntimeManager）
  ↓ 未就绪 → 直接返回错误"代码执行环境未启用"
  ↓
kernelId 存在？
  ├─ YES → 持久内核路径
  │         ├─ CodeValidator 预检
  │         ├─ CommandGuard 检查
  │         └─ kernel.execute(code, timeout)
  └─ NO → 沙箱路径
            ├─ SandboxSessionManager.getOrCreate(sessionId)
            ├─ CodeValidator 预检
            ├─ CommandGuard 检查（仅 process 后端）
            └─ booter.execute(request)
  ↓
审计持久化（SandboxRepository）
  ↓
返回结果
```

---

## 持久内核（kernelId）

### 核心概念

持久内核类似 Jupyter Notebook 的 kernel：每个 kernelId 对应一个独立的运行时进程，变量、导入、函数定义在多次 `exec` 调用之间保持。

### 支持的语言

| 语言 | 内核实现 | 底层 |
|------|---------|------|
| Python | `PythonKernel` | 捆绑 Python 运行时（`~/.zhiwei/python/`） |
| JavaScript | `JavaScriptKernel` | Node.js 进程 |
| Shell | `ShellKernel` | tmux 会话（仅 Unix） |

### 生命周期

```
getOrCreate(kernelId, language)
  ├─ 已存在且 IDLE/BUSY → 复用
  ├─ 已存在且 ERROR → 尝试自动重启
  ├─ 已存在且 CLOSED → 移除后重新创建
  └─ 不存在 → 创建新内核

execute(code, timeout)
  → 内核状态 IDLE → BUSY → IDLE

reset(kernelId)
  → 清空变量和导入，进程不关闭

close(kernelId)
  → 关闭进程，释放资源

自动清理
  → 空闲超过 ttlMinutes 的内核被自动关闭
```

### 内核状态

| 状态 | 含义 |
|------|------|
| `IDLE` | 空闲，可接受新执行 |
| `BUSY` | 正在执行代码 |
| `ERROR` | 进程异常（会尝试自动重启） |
| `CLOSED` | 已关闭 |

### 典型使用模式

**多步数据分析**：
```
1. exec(kernelId="data-1", code="import pandas as pd; df = pd.read_csv('sales.csv')")
2. exec(kernelId="data-1", code="monthly = df.groupby('month').sum()")
3. exec(kernelId="data-1", code="print(monthly.to_markdown())")
4. exec(kernelId="data-1", code="monthly.plot(kind='bar'); plt.savefig('chart.png')")
```

**交互式调试**：
```
1. exec(kernelId="debug-1", code="<用户的代码>")  → 报错
2. exec(kernelId="debug-1", code="print(locals())")  → 查看变量
3. kernel_inspect(kernelId="debug-1")  → 查看完整状态
4. exec(kernelId="debug-1", code="<修正后的代码>")
```

**何时用 kernelId vs 一次性沙箱**：

| 场景 | 选择 | 理由 |
|------|------|------|
| 单步计算（1+1、格式转换） | 一次性 | 无需保持状态 |
| 多步数据分析 | kernelId | 数据加载一次，后续复用 |
| 生成图表（需要先加载数据） | kernelId | matplotlib 需要 df 在内存 |
| 验证代码片段 | 一次性 | 隔离性更好 |
| 教学/演示（逐步构建） | kernelId | 学生能看到状态累积 |

---

## 内核管理 action

当 `PersistentKernelManager` 可用时，`code` 工具额外支持三个管理 action：

### action=kernel_list

列出所有活跃内核。

```json
{ "tool": "code", "args": { "action": "kernel_list" } }
```

返回：
```json
{
  "kernels": [
    { "kernelId": "analysis-1", "state": "IDLE", "idleSeconds": 120 },
    { "kernelId": "debug-2", "state": "BUSY", "idleSeconds": 0 }
  ],
  "total": 2
}
```

### action=kernel_inspect

查看指定内核的变量与状态。

```json
{ "tool": "code", "args": { "action": "kernel_inspect", "kernelId": "analysis-1" } }
```

返回：
```json
{
  "kernelId": "analysis-1",
  "variables": {
    "df": "DataFrame(1000 rows × 5 cols)",
    "monthly": "DataFrame(12 rows × 3 cols)",
    "pd": "<module 'pandas'>"
  }
}
```

### action=kernel_reset

清空指定内核的变量和导入，进程不关闭。

```json
{ "tool": "code", "args": { "action": "kernel_reset", "kernelId": "analysis-1" } }
```

返回：
```json
{ "kernelId": "analysis-1", "message": "内核已重置，所有变量和导入已清空" }
```

---

## 安全与权限

### CodeValidator 预检

在代码执行前，`CodeValidator` 对代码做静态分析，检测危险操作：

- 文件系统破坏（`os.remove`、`shutil.rmtree` 等）
- 网络外连（`socket.connect`、`urllib.request` 等，视配置）
- 进程操作（`os.system`、`subprocess.Popen` 等）
- 资源耗尽（无限循环模式、大内存分配）

预检未通过时返回：
```json
{
  "error": "代码预检未通过: [CRITICAL] 第3行: 检测到文件系统删除操作; [WARNING] 第7行: 检测到网络外连"
}
```

### CommandGuard 护栏

与 `shell.exec` 共用同一套规则集：

| 决策 | 行为 | 可配置？ |
|------|------|---------|
| BLOCKED_HARDLINE | 永久阻断，不可恢复 | ❌ |
| BLOCKED_DANGEROUS | 默认拒绝 | ✅（yolo 模式可放行） |
| APPROVED | 放行 | — |

**Docker 后端例外**：容器本身就是隔离边界，CommandGuard 对 docker 后端 bypass 全部规则。

### 审计持久化

每次执行（无论成功、失败还是被拒绝）都会写入 `SandboxRepository`：

| 字段 | 说明 |
|------|------|
| `codeHash` | 代码 SHA-256（不存原文，隐私保护） |
| `codeLength` | 代码长度 |
| `language` | 语言 |
| `booterType` | 后端类型（process / docker / kernel） |
| `validationPassed` | 预检是否通过 |
| `exitCode` | 退出码 |
| `durationMs` | 执行耗时 |
| `state` | COMPLETED / FAILED / REJECTED / TIMEOUT |

### 风险等级

| action | RiskLevel | 说明 |
|--------|-----------|------|
| `exec` | HIGH | 执行任意代码，触发用户确认 |
| `kernel_list` | LOW | 只读操作 |
| `kernel_inspect` | LOW | 只读操作 |
| `kernel_reset` | LOW | 清空状态但不执行代码 |

---

## 沙箱后端

### Process 后端（默认）

- 直接在本机启动子进程执行
- 依赖 `PythonRuntimeManager` 管理的捆绑 Python（`~/.zhiwei/python/`）
- CommandGuard 全规则集生效
- 适合开发环境和单用户部署

### Docker 后端

- 在容器内执行，天然隔离
- CommandGuard bypass（容器即边界）
- 适合多用户 / 不信任代码场景
- 需要 Docker 环境可用

### 后端选择

由 `SandboxSessionManager` 根据配置自动选择，Skill 作者无需关心。如果 Docker 不可用会自动降级到 Process。

---

## 配置参考

```yaml
lifepilot:
  meta:
    infra:
      code-execute:
        defaultLanguage: python        # 默认语言
        enabled: true                  # 是否启用
      kernel:
        maxConcurrentKernels: 3        # 持久内核并发上限
        ttlMinutes: 30                 # 空闲内核自动关闭时间
        cleanupIntervalSeconds: 60     # 清理扫描间隔
        maxOutputChars: 50000          # 内核输出截断阈值
        nodeRuntime: "node"            # JavaScript 内核的 Node 路径
  sandbox:
    booter:
      type: process                    # process / docker
    validator:
      enabled: true                    # CodeValidator 是否启用
```

---

## code vs shell.exec 选择指南

这是 Skill 作者最常纠结的问题。核心原则：**code 用于计算，shell.exec 用于操作**。

| 场景 | 选 code | 选 shell.exec | 理由 |
|------|---------|--------------|------|
| 数据分析（pandas/numpy） | ✅ | ❌ | 预装库、有预检、审计完整 |
| 生成图表（matplotlib） | ✅ | ❌ | 需要 Python 环境 + 预装库 |
| 数学计算 | ✅ | ❌ | 纯计算场景 |
| 文件格式转换（docx→pdf） | ✅ | ❌ | 预装 python-docx/pypdf |
| 多步探索（保持变量） | ✅（kernelId） | ❌ | 持久内核的核心价值 |
| 运行 git/npm/docker CLI | ❌ | ✅ | 系统工具不在沙箱里 |
| 启动服务（dev server） | ❌ | ✅（background） | 需要后台进程管理 |
| 操作文件系统（创建/删除） | ❌ | ✅ 或 file_manage | code 的预检会拦截 |
| 安装系统包 | ❌ | ✅ | 沙箱环境受限 |
| 一次性 Python 脚本（无预装库需求） | 都行 | 都行 | code 有审计优势 |
| 需要特定工作目录 | ❌ | ✅ | code 固定在 sandbox 目录 |
| 需要环境变量注入 | ❌ | ✅ | code 不支持 env 参数 |
| 需要超过 30s 的长计算 | ✅（调高 timeout） | ✅（yieldMs） | 看是否需要后台化 |

### 灰色地带的判断

**"用 Python 跑个脚本"**：
- 脚本需要 pandas/numpy/matplotlib → `code`
- 脚本需要 pip install 额外包 → `shell.exec`（沙箱里装不了）
- 脚本需要读写用户指定路径的文件 → `shell.exec`（沙箱工作目录固定）
- 脚本是纯计算 + print 结果 → `code`（有审计、有预检）

**"执行一段 shell 命令"**：
- `language: "shell"` 的 code 和 `shell.exec` 有什么区别？
  - `code(language=shell)` 走沙箱路径，有 CodeValidator 预检 + 审计
  - `shell.exec` 直接在用户环境执行，有黑名单 + CommandGuard + 工作目录控制
  - 实际效果类似，但 `shell.exec` 更灵活（支持 background/yieldMs/env/pty）
  - **建议**：需要系统操作用 `shell.exec`，需要审计记录用 `code(language=shell)`

---

## 常见陷阱与最佳实践

### ⚠️ 陷阱 1："代码执行环境未启用"不可重试

```json
{ "error": "代码执行环境未启用，请在设置页启用" }
```

这意味着 `PythonRuntimeManager` 检测到捆绑 Python 未就绪。**不要重试**，引导用户去设置页启用。

### ⚠️ 陷阱 2：一次性沙箱没有持久状态

```json
// ❌ 第二次调用拿不到 df，因为没传 kernelId
{ "action": "exec", "code": "df = pd.read_csv('data.csv')" }
{ "action": "exec", "code": "print(df.head())" }  // NameError!

// ✅ 传 kernelId 保持状态
{ "action": "exec", "kernelId": "my-analysis", "code": "df = pd.read_csv('data.csv')" }
{ "action": "exec", "kernelId": "my-analysis", "code": "print(df.head())" }  // OK
```

### ⚠️ 陷阱 3：内核并发上限

默认最多 3 个持久内核。超了会报错：
```json
{ "error": "持久内核数已达上限: 3" }
```

**对策**：用完的内核及时 `kernel_reset`（如果还要复用）或让它自然超时关闭。不要无限制地创建新 kernelId。

### ⚠️ 陷阱 4：沙箱里装不了新包

```python
# ❌ 沙箱环境不支持 pip install
import subprocess
subprocess.run(["pip", "install", "some-package"])  # 会被 CodeValidator 拦截
```

**对策**：只用预装库。如果确实需要额外包，用 `shell.exec` 在用户环境安装后再用 `code` 执行。

### ⚠️ 陷阱 5：文件路径问题

code 工具的工作目录是沙箱目录（`~/.zhiwei/sandbox/` 或容器内路径），不是用户的项目目录。

```python
# ❌ 用户说"分析当前目录的 data.csv"，但沙箱里没有这个文件
df = pd.read_csv('data.csv')  # FileNotFoundError

# ✅ 用绝对路径
df = pd.read_csv('/Users/me/project/data.csv')
```

**对策**：Skill 里要把用户提到的相对路径转成绝对路径再传给 code。

### ⚠️ 陷阱 6：超时默认只有 30 秒

大数据集处理、模型训练等可能超时：
```json
{ "error": "代码执行失败: exitCode=137" }  // 被 SIGKILL
```

**对策**：预估耗时长的任务显式传 `timeoutSeconds`（上限看配置）。

### ✅ 最佳实践清单

- 多步分析必须用 kernelId，避免重复加载数据
- kernelId 命名有意义（`"sales-analysis"`、`"debug-issue-42"`），方便 kernel_list 时识别
- 大数据先 `print(df.shape)` 确认规模，再决定是否需要拉高 timeout
- 生成图表时用 `plt.savefig()` 保存到已知路径，然后告诉用户文件位置
- 预检报错时不要尝试绕过（换写法避开检测），而是换用 `shell.exec` 或 `file_manage`
- 内核出 ERROR 态时先试 `kernel_reset`，不行再创建新 kernelId
- 用 `kernel_inspect` 帮用户查看当前变量状态，比重新 print 所有变量高效

---

## 为 Skill 编写提示词参考

### 通用指导段（推荐必加）

```markdown
## 代码执行规则

你有一个 `code` 工具，用于执行 Python / JavaScript / Shell 代码。

**基本用法**：
- `action=exec, code="..."` — 执行代码，默认 Python
- `action=exec, kernelId="xxx", code="..."` — 持久内核，变量跨调用保持

**关键规则**：
- 多步分析**必须**传 kernelId，否则每次执行都是全新环境
- 预装库：pandas/numpy/scipy/scikit-learn/matplotlib/seaborn/openpyxl/python-docx/python-pptx/pypdf/pillow/requests/httpx/beautifulsoup4
- 需要额外包时**不能** pip install，改用 shell.exec 在用户环境操作
- 文件路径必须用**绝对路径**（沙箱工作目录不是用户项目目录）
- 默认超时 30 秒，大数据处理显式传 `timeoutSeconds`
- "代码执行环境未启用"错误**不可重试**，引导用户去设置页
- 删除文件走 file_manage(action=delete)，不要在 code 里用 os.remove
```

### 数据分析类 Skill 专用段

```markdown
## 数据分析执行规则

1. 首次加载数据时创建 kernelId（命名含任务语义，如 "sales-q4-analysis"）
2. 加载后立即 `print(df.shape, df.dtypes)` 确认数据规模和类型
3. 大数据集（>100MB）先 `df.head()` 预览，再决定操作
4. 生成图表：
   - `plt.figure(figsize=(10, 6))` 设置合理尺寸
   - `plt.savefig('/absolute/path/chart.png', dpi=150, bbox_inches='tight')`
   - 告诉用户图表保存位置
5. 分析完成后不需要主动关闭内核（会自动超时清理）
6. 如果内核报 ERROR，先 `kernel_inspect` 查看状态，再决定 reset 还是重建
```

### 文件处理类 Skill 专用段

```markdown
## 文件处理执行规则

支持的格式转换（预装库覆盖）：
- Excel 读写：openpyxl（.xlsx）
- Word 读写：python-docx（.docx）
- PPT 读写：python-pptx（.pptx）
- PDF 读取：pypdf
- 图片处理：pillow
- CSV/JSON：pandas

执行模式：
- 单文件转换 → 一次性沙箱（不传 kernelId）
- 批量处理 → kernelId（避免重复 import）
- 输出文件路径必须用绝对路径，处理完告诉用户文件位置
```

---

## 相关源码

- `src/main/java/com/lifepilot/meta/infra/code/CodeToolProvider.java` — 工具定义与 schema
- `src/main/java/com/lifepilot/meta/infra/code/CodeExecuteToolExecutor.java` — 执行主逻辑
- `src/main/java/com/lifepilot/meta/infra/code/kernel/PersistentKernelManager.java` — 内核生命周期管理
- `src/main/java/com/lifepilot/meta/infra/code/kernel/PersistentKernel.java` — 内核接口定义
- `src/main/java/com/lifepilot/meta/infra/code/kernel/PythonKernel.java` — Python 内核实现
- `src/main/java/com/lifepilot/meta/infra/code/kernel/JavaScriptKernel.java` — JavaScript 内核实现
- `src/main/java/com/lifepilot/meta/infra/code/kernel/ShellKernel.java` — Shell 内核实现
- `src/main/java/com/lifepilot/sandbox/booter/SandboxBooter.java` — 沙箱后端接口
- `src/main/java/com/lifepilot/sandbox/booter/ProcessBooter.java` — Process 后端
- `src/main/java/com/lifepilot/sandbox/booter/DockerBooter.java` — Docker 后端
- `src/main/java/com/lifepilot/sandbox/validator/CodeValidator.java` — 代码预检
- `src/main/java/com/lifepilot/sandbox/guard/CommandGuard.java` — 命令护栏
- `src/main/java/com/lifepilot/sandbox/runtime/PythonRuntimeManager.java` — Python 运行时管理
