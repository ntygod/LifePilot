---
title: 代码执行运行时 — 捆绑 Python + ProcessBooter 主路径
status: design
owner: zsg
date: 2026-04-26
scope: 把 ProcessBooter 从"依赖用户系统 Python"重构为"知微自带 Python 运行时"，让"装完即用"的产品体验落地。Docker 沙箱模式延后作为高级隔离 opt-in，本 spec 不涉及。
---

# 代码执行运行时 — 捆绑 Python + ProcessBooter 主路径

## 0. 一句话说明

知微作为本地 AI 助手，参考 Claude Code 路线（OS-level 运行时 + 用户本机执行），但**自带 Python 运行时**让用户零依赖、装完即用。Docker 沙箱模式不再是主路径，仅作为高级隔离场景的 opt-in（独立 PR）。

## 1. 背景与动机

### 1.1 当前现状

ProcessBooter / DockerBooter 双 booter 抽象已成型（`sealed interface SandboxBooter`），但：

1. **DockerBooter 是 stub**：`zhiwei/sandbox-python` 镜像从未发布，没有 lazy pull / 镜像存在性检查
2. **ProcessBooter 依赖用户系统 Python**：`runtimePaths.get("python")` 默认 `"python"`，用户没装直接报错
3. **能力分裂**：`doc-processor` SKILL 已重写为 `code.execute` + Python 库路径（pandas/python-docx/openpyxl），但**没有镜像也没有捆绑 Python，永远跑不通**

### 1.2 业界路线对比

四份调研（Anthropic Code Execution / Hermes Agent / OpenClaw / 业界 SaaS 对比）揭示两条本质不同的路线：

| | 路线 X：自家镜像 + 预装栈 | 路线 Y：本机环境 + OS 沙箱 |
|---|---|---|
| 代表 | Anthropic Code Execution（云）/ Hermes / E2B / Daytona | Claude Code Bash / OpenAI Codex CLI |
| 环境 | 容器内预装 | 假设用户已装 |
| 安装 | 几 MB → 首次拉镜像 ~700MB | 几 MB，零下载 |
| 隔离 | Docker 容器 | bubblewrap (Linux) / Seatbelt (macOS) |

知微定位是**本地 AI 助手**，不是云服务，应该走路线 Y。但纯路线 Y 假设"用户已装 Python"，对零经验用户体验差。

### 1.3 解决路线：Y' = 捆绑 Python

参考 sandbox roadmap 记忆里的 B2 选项（"portable Python 捆绑"），**升格为主路径**：知微首次启动下载内置 Python 运行时（[python-build-standalone](https://github.com/astral-sh/python-build-standalone)）+ 预装数据科学栈，**ProcessBooter 永远不用系统 Python**。

### 1.4 目标

- **装完即用**：用户安装知微 + 完成 onboarding → 所有需要 code.execute 的 SKILL 可用，无 pip install 烦恼
- **零系统依赖**：不假设用户机器有 Python，不向用户索要 Docker
- **用户主动权**：onboarding 询问"是否安装代码执行环境"，用户拒绝则只用基础对话能力
- **预装栈对齐 Anthropic 事实标准**：pandas / numpy / scipy / sklearn / matplotlib / openpyxl / python-docx / pypdf / pdfplumber 等
- **安全可控**：HARDLINE + DANGEROUS 两层命令阻断（参考 Hermes `tools/approval.py` 范式）

### 1.5 不做的事（明确边界）

- ❌ **DockerBooter 增强**（lazy pull / Dockerfile 构建 / CI 推 GHCR / config-hash）—— 留待后续独立 PR，定位"高级隔离 opt-in"
- ❌ **SKILL frontmatter `required_runtime` 字段**与 26 SKILL 盘点 —— process 部分完成后再审查
- ❌ **Jupyter-like 持久内核增强** —— 现有 `PersistentKernelManager` 不动
- ❌ **OS-level 沙箱**（bubblewrap / Seatbelt）—— 一期不做，看用户反馈再引入
- ❌ **多语言运行时捆绑**（Node.js / Rust 等）—— 仅捆绑 Python，JavaScript 工具走"LLM 用 Python 等价实现"或要求用户系统已装 Node
- ❌ **对话内审批 + 自动续做** —— 复杂度过高，改用"设置页开关"硬控制
- ❌ **不引入双轨 mode 调用级路由**（`code.execute(mode=local|sandbox)`）—— 4 个调研对象无一这么做，全局配置足够

## 2. 架构总览

### 2.1 运行时拓扑

```
~/.zhiwei/
├─ python/                              # 捆绑 Python 运行时（onboarding 安装）
│  ├─ bin/python(.exe)                  # python-build-standalone 解压
│  ├─ lib/python3.12/site-packages/     # 预装数据科学栈
│  └─ VERSION                           # 当前 Python 版本号（升级判断）
├─ sandbox/                             # ProcessBooter 工作目录（每 session 一子目录）
│  └─ session-<uuid>/
└─ logs/
```

### 2.2 ProcessBooter 改造前后

| | 改造前 | 改造后 |
|---|---|---|
| Python 路径 | `runtimePaths.get("python")` → 用户系统 PATH | 永远只用 `~/.zhiwei/python/bin/python` |
| 用户没装 Python | 启动失败 / 报 `python: command not found` | 由 `PythonRuntimeManager` 检查 → ProcessBooter 进入 `NOT_READY` 状态，code.execute 优雅报错引导设置页 |
| 第三方库 | 用户自行 `pip install` | 知微预装，开箱即用 |

### 2.3 状态机

```
                         ┌─────────────────────┐
[NOT_INSTALLED] ─────────│ onboarding "立即安装"│──────► [INSTALLING] ──► [READY]
                         └─────────────────────┘             │
                                                              ▼ 失败
                                                       [INSTALL_FAILED]
                                                              │ 重试
                                                              ▼
                                                         [INSTALLING]

[NOT_INSTALLED] ── onboarding "跳过" ──► [DISABLED]
[DISABLED]      ── 设置页"启用"        ──► [INSTALLING] ──► [READY]
[READY]         ── 设置页"禁用"        ──► [DISABLED]（保留文件）
[READY]         ── 设置页"卸载"        ──► [NOT_INSTALLED]（删除文件）
```

## 3. 组件设计

### 3.1 PythonRuntimeManager（新增）

负责捆绑 Python 的生命周期与状态查询。

`com.lifepilot.sandbox.runtime.PythonRuntimeManager`

```java
public sealed interface RuntimeStatus permits NotInstalled, Disabled, Installing, Ready, InstallFailed {
    record NotInstalled() implements RuntimeStatus {}
    record Disabled() implements RuntimeStatus {}
    record Installing(int percent, long bytesDownloaded, long totalBytes) implements RuntimeStatus {}
    record Ready(String version, long diskBytes) implements RuntimeStatus {}
    record InstallFailed(String reason) implements RuntimeStatus {}
}

public class PythonRuntimeManager {
    RuntimeStatus checkStatus();
    Path getPythonExecutable();        // 返回 ~/.zhiwei/python/bin/python(.exe)
    CompletableFuture<Void> install(); // 走 PythonRuntimeDownloader + 解压 + 验证
    void disable();                     // 状态 → DISABLED，文件保留
    void enable();                      // DISABLED → INSTALLING（重新校验或重装）
    void uninstall();                   // 删除 ~/.zhiwei/python/，状态 → NOT_INSTALLED
}
```

状态判断逻辑：
1. `~/.zhiwei/python/VERSION` 不存在 → `NotInstalled`
2. 配置 `lifepilot.sandbox.runtime.python.disabled=true` → `Disabled`
3. 进行中 install → `Installing`
4. 文件存在 + VERSION 匹配 + python 可执行 → `Ready`
5. 否则 → `InstallFailed("xxx")`

### 3.2 PythonRuntimeDownloader（新增）

负责从知微项目自家 GitHub Releases 下载 python-build-standalone + 预装库 tarball。

`com.lifepilot.sandbox.runtime.PythonRuntimeDownloader`

知微自家发布的 tarball 结构：
```
zhiwei-python-runtime-<version>-<platform>-<arch>.tar.zst
└─ python/
   ├─ bin/, lib/, include/
   ├─ VERSION
   └─ MANIFEST.txt   # 包含 hash 信息和库清单
```

发布渠道：知微项目 GitHub Releases（与知微版本对齐，避免依赖第三方镜像源）

下载流程：
1. 解析配置中的 `download-url-template` + 当前平台
2. HTTP GET 流式下载到临时文件 `~/.zhiwei/python.tar.zst.partial`
3. SHA-256 校验
4. 解压到 `~/.zhiwei/python/`
5. 重命名 `python.tar.zst.partial` → 删除
6. 写 `VERSION` 文件

### 3.3 ProcessBooter 改造

`com.lifepilot.sandbox.booter.ProcessBooter` 增加构造参数 `PythonRuntimeManager runtimeManager`：

- `boot()` 时调用 `runtimeManager.checkStatus()`
  - `Ready` → 正常启动
  - 其他 → 抛 `IllegalStateException("Python 运行时未就绪: " + status)`
- `buildCommand(language, scriptFile)` 改造：
  - **python** → `[runtimeManager.getPythonExecutable(), scriptFile]`
  - **shell** → 系统 bash（macOS/Linux）/ cmd（Windows）—— 不变
  - **javascript** → 检测系统 `node`，没有则 `IllegalStateException("JavaScript 执行需要系统 Node.js，请自行安装或改用 Python")`

### 3.4 CommandGuard（新增）

参考 Hermes `tools/approval.py` 的 HARDLINE + DANGEROUS 两层模型。

`com.lifepilot.sandbox.guard.CommandGuard`

```java
public record GuardResult(Decision decision, String matchedRule, String severity) {
    public enum Decision { APPROVED, BLOCKED_HARDLINE, BLOCKED_DANGEROUS }
}

public class CommandGuard {
    GuardResult check(Language language, String code, String booterType);
}
```

#### 容器后端 bypass

`booterType.equals("docker")` 时直接返回 `APPROVED`（HARDLINE 也跳过）。本 spec 范围内只有 `process` booter，但保留 bypass 逻辑给后续 DockerBooter PR 复用。

#### 归一化（防绕过）

```
strip ANSI escape sequences → NFKC unicode normalize → strip null bytes → lowercase（仅 shell）
```

#### 3.4.1 HARDLINE 列表（11 条无条件硬阻断）

| # | 模式 | 说明 |
|---|---|---|
| 1 | `rm -rf /` 及变体（含 `/home`/`/etc`/`/usr`/`/var`/`/boot`/`/bin`/`/sbin`/`/lib`/`~`/`$HOME`） | 删根/系统目录/家目录 |
| 2 | `mkfs.*` | 格式化文件系统 |
| 3 | `dd if=.* of=/dev/(sd|nvme|hd|vd).*` | 写裸块设备 |
| 4 | `:(){ :\|:& };:` 及等价 fork bomb | 进程炸弹 |
| 5 | `kill -1` / `kill -9 -1` | 杀全部进程 |
| 6 | `shutdown` / `reboot` / `halt` / `poweroff` | 关机重启 |
| 7 | `init 0` / `init 6` | 关机重启 |
| 8 | `systemctl (poweroff\|reboot\|halt\|kexec)` | systemd 关机 |
| 9 | `telinit (0\|6)` | 关机重启 |
| 10 | `chmod -R 000 /` | 锁死系统 |
| 11 | 写 `/dev/(sda\|nvme0n1)` 直接 redirect | 块设备直写 |

匹配采用正则 + `_CMDPOS` 命令位置锚定（避免 `echo reboot` 误伤），通过预设安全 fast-path bins（`echo`/`cat`/`head`/`tail`/`grep`/`awk`/`sed`/`tr`/`wc` 等）放行。

#### 3.4.2 DANGEROUS 列表（约 30 条 yolo 可过）

涵盖：
- `rm -rf` 子树（非系统目录）
- `chmod -R 777`
- `git reset --hard` / `git push --force` / `git clean -fdx`
- `curl ... | sh` / `wget ... | bash`
- heredoc 跑代码（`cat <<EOF | bash`）
- SQL `DROP TABLE` / `DELETE` 不带 `WHERE`
- 写 `/etc/`
- 改自身进程文件 `~/.zhiwei/`
- `rm -rf $HOME/Documents`、`rm -rf $HOME/Desktop` 等用户数据目录
- `tar` / `zip` 解压到根目录
- 任何 `sudo` / `su` 子命令

完整列表在实施时根据 Hermes `approval.py:175-238` 翻译落地，spec 阶段只列举类别。

#### 3.4.3 yolo 模式

`lifepilot.sandbox.runtime.command-guard.yolo-mode=true` 时 DANGEROUS 自动放行（HARDLINE 不放）。默认 `false`。

### 3.5 SandboxConfigProperties 扩展

```yaml
lifepilot:
  sandbox:
    booter: process                        # 现有
    runtime:                               # 新增
      python:
        bundled-version: "3.12.4"          # 期望版本
        download-url-template: "https://github.com/zsg-cs/zhiwei/releases/download/runtime-{version}/zhiwei-python-runtime-{version}-{platform}-{arch}.tar.zst"
        sha256-url-template: "${download-url-template}.sha256"
        install-path: "${user.home}/.zhiwei/python"
        expected-libraries:
          - pandas
          - numpy
          - scipy
          - scikit-learn
          - matplotlib
          - seaborn
          - openpyxl
          - pillow
          - python-pptx
          - python-docx
          - pypdf
          - pdfplumber
          - sympy
          - requests
          - httpx
          - beautifulsoup4
        disabled: false                    # true 时强制 DISABLED 状态
      command-guard:
        enabled: true
        yolo-mode: false                   # true 时 DANGEROUS 自动放行
```

## 4. Onboarding 流程

### 4.1 启动检测时序

```
Tauri 主进程启动
  │
  ├─ 启动 Java 后端进程（已有 JavaManager 流程）
  │
  └─ 等 Java 后端 health check 通过
        │
        └─ 前端 Vue 进入路由
              │
              └─ App.vue mounted → GET /api/runtime/python/status
                    │
                    ├─ Ready    → 直接进主界面
                    ├─ Disabled → 直接进主界面（功能未启用）
                    └─ NotInstalled / InstallFailed → 跳转 onboarding 路由
```

### 4.2 Onboarding 页面（Vue 组件）

集成到现有 `zhiwei-web/src/components/desktop/SetupWizard.vue` 流程，新增一步「代码执行环境」介于"模型配置"和"完成"之间。**不新建独立路由**，复用现有 `/setup` 引导链路与 `localStorage zhiwei_onboarding_completed` flag。

UI 文案/布局占位（实际措辞由实施时决定，spec 不约束话术）：
- 全屏遮罩 + 居中卡片（Reka UI Card）
- 标题：欢迎使用知微
- 列出"装完即可用"的能力 + "需要安装代码执行环境后可用"的能力（数据分析 / 文档生成 / ML / 加密）
- 安装包大小说明：约 250MB（一次性）
- 两个按钮：[立即安装] / [跳过，以后再说]

选"立即安装"：
- UI 切到进度卡片（百分比 + 速度 + 已下载/总大小）
- 失败有 [重试] / [取消跳过] 两个按钮

### 4.3 SSE 进度推送

`com.lifepilot.sandbox.runtime.RuntimeInstallProgressEmitter` 通过 Spring `SseEmitter` 推送：

```json
{
  "phase": "downloading | extracting | verifying | done | failed",
  "bytesDownloaded": 12345678,
  "totalBytes": 250000000,
  "speedBytesPerSec": 1234567,
  "errorMessage": null
}
```

前端订阅 `GET /api/runtime/install/progress` SSE 流。

## 5. 设置页

`zhiwei-web/src/views/SettingsCodeExecution.vue` —— 路径放在「设置 → 能力 → 代码执行环境」

显示：
- 状态徽章：`NOT_INSTALLED` / `READY` / `DISABLED` / `INSTALL_FAILED` / `INSTALLING`
- 已安装 Python 版本（READY 时）
- 磁盘占用（READY 时）
- 预装库列表展开
- 操作按钮（按状态变化）：
  - `NOT_INSTALLED` / `DISABLED` → [启用并下载]
  - `READY` → [禁用] / [卸载] / [重新安装]
  - `INSTALL_FAILED` → [重试] / [取消]
  - `INSTALLING` → 进度条 + [取消]

## 6. REST API

`com.lifepilot.interaction.web.controller.RuntimeController`

| Method | Path | Body | Response | 说明 |
|---|---|---|---|---|
| GET  | `/api/runtime/python/status`        | — | `{ status, version?, diskBytes?, expectedLibraries }` | 查询状态 |
| POST | `/api/runtime/python/install`       | — | `{ ok: true }` | 触发异步安装；状态/进度通过 status 端点和 SSE 流单独获取 |
| POST | `/api/runtime/python/uninstall`     | — | `{ ok: true }` | 卸载（删除文件） |
| POST | `/api/runtime/python/disable`       | — | `{ ok: true }` | DISABLED 状态（保留文件） |
| POST | `/api/runtime/python/enable`        | — | `{ ok: true }` | 重启用（如文件已存在则直接 Ready；否则触发 install） |
| GET  | `/api/runtime/install/progress`     | — | SSE 流 | 安装进度推送（全局通道，同时只有一个 install 进行） |

所有响应走现有 `ApiResponse<T>` 包装格式。

并发约束：同时只允许一个 install 任务进行中。重复 POST `install` 时返回 409 + 当前 status。

## 7. 错误处理

### 7.1 安装阶段

| 故障 | 用户感知 | 内部处理 |
|---|---|---|
| 网络错误 | "下载失败：网络连接异常" + 重试按钮 | INSTALL_FAILED，partial 文件保留供断点续传（v2 再做） |
| SHA-256 校验失败 | "下载文件损坏，请重试" | 删除 partial 文件，状态 INSTALL_FAILED |
| 磁盘空间不足 | "磁盘空间不足，需要 1GB 可用空间" | 预检阶段提前拒绝，不开始下载 |
| 解压权限不足 | "无写权限，请检查 ~/.zhiwei/ 目录" | INSTALL_FAILED，错误信息含 errno |

### 7.2 运行时使用阶段

| 故障 | 用户感知 | 内部处理 |
|---|---|---|
| code.execute 时 Runtime NOT_READY | ToolResult.error("代码执行环境未启用，请在设置页启用") | ProcessBooter 抛 IllegalStateException，CodeExecuteToolExecutor catch 后转 ToolResult |
| HARDLINE 命中 | ToolResult.error("此命令被永久阻断（不可恢复操作）：[规则名]") | 不写沙箱审计（不算执行），写 `command_guard_blocks` 表 |
| DANGEROUS 命中（yolo=false） | ToolResult.error("此命令被拒绝执行（危险操作）：[规则名]") | 同上 |
| Python 进程崩溃 | 现有 ProcessBooter 错误处理路径（exitCode 非零） | 不变 |

## 8. 数据流

```
[启动] Tauri 主进程
  ├─ JavaManager 启动 Java 后端
  └─ Java 后端 PythonRuntimeManager bean 初始化（不阻塞，懒加载状态）

[前端进入] App.vue mounted
  └─ GET /api/runtime/python/status
       ├─ Ready    → router.push("/")
       ├─ Disabled → router.push("/")（主界面，code.execute 不可用）
       ├─ NotInstalled / InstallFailed → router.push("/onboarding/python-runtime")
       └─ Installing → router.push("/onboarding/python-runtime")（进度页）

[Onboarding "立即安装"]
  POST /api/runtime/python/install
    └─ PythonRuntimeManager.install()
         ├─ 状态 → Installing
         ├─ PythonRuntimeDownloader.download() (异步，Virtual Thread)
         │    └─ 推送 SSE 进度
         ├─ SHA-256 校验
         ├─ 解压到 ~/.zhiwei/python/
         ├─ 写 VERSION
         └─ 状态 → Ready
  前端订阅 SSE → done 时 router.push("/")

[执行 code.execute]
  CodeExecuteToolExecutor.execute()
    ├─ runtimeManager.checkStatus()
    │    ├─ Ready → 继续
    │    └─ 其他 → ToolResult.error("代码执行环境未启用，请在设置页启用")
    ├─ CommandGuard.check(language, code, "process")
    │    ├─ APPROVED → ProcessBooter.execute()
    │    │    └─ ProcessBuilder([~/.zhiwei/python/bin/python, scriptFile])
    │    └─ BLOCKED_* → ToolResult.error
    └─ 返回 ToolResult
```

## 9. 测试策略

### 9.1 单元测试

| 测试类 | 覆盖 |
|---|---|
| `PythonRuntimeManager_状态机测试` | 状态迁移 5×N 矩阵、文件存在性判断 |
| `PythonRuntimeDownloader_下载校验测试` | Mock HTTP 服务器，断网/损坏 hash/解压失败 |
| `CommandGuard_HARDLINE 测试` | 11 条规则各 5 个变体（含归一化绕过：全角字符、ANSI、null bytes） |
| `CommandGuard_DANGEROUS 测试` | 30 条规则代表性用例 + yolo 模式开关 |
| `ProcessBooter_捆绑 Python 测试` | mock PythonRuntimeManager 注入不同状态，验证启动行为 |
| `RuntimeController_API 契约测试` | MockMvc 6 个端点的请求/响应 |

### 9.2 集成测试

- `RuntimeInstallEndToEndTest` — 启动 mock Python tarball HTTP 服务器 → 走完整 install 流程 → 验证文件落地、状态 Ready
- `CodeExecuteWithBundledPythonTest` — 使用真实 python-build-standalone（CI 缓存）→ 跑通 pandas 读 csv、python-docx 写文档、openpyxl 操作 xlsx 各一例

### 9.3 跨平台冒烟

- Windows / macOS / Linux 三平台 GitHub Actions runner 各跑一遍 install + uninstall + execute

### 9.4 测试规范遵循

- 测试类名中文：`PythonRuntimeManager_状态机测试.java`
- 测试方法名中文：`void 状态_NotInstalled时调用install应迁移到Installing()`
- Mock 用 `@ExtendWith(MockitoExtension.class)`
- AutoConfiguration 测试用 `ApplicationContextRunner`

## 10. Migration 与发布

### 10.1 数据库迁移

- **V31（next，当前最新 V30）**：新增 `runtime_install_history` 表，记录 install / uninstall / 失败原因
  ```sql
  CREATE TABLE runtime_install_history (
    id           TEXT PRIMARY KEY,
    runtime_kind TEXT NOT NULL,             -- 'python'（未来扩展 node 等）
    version      TEXT NOT NULL,
    action       TEXT NOT NULL,             -- 'install' / 'uninstall' / 'enable' / 'disable'
    status       TEXT NOT NULL,             -- 'success' / 'failed'
    error_msg    TEXT,
    duration_ms  INTEGER,
    created_at   TEXT NOT NULL
  );
  ```

### 10.2 知微版本与 Python 版本绑定

- `application.yml` 中 `bundled-version` 是当前知微版本期望的 Python 版本
- 升级知微 → 启动检测 `installed_version != expected_version` → 设置页「代码执行环境」分区头部出现"有新版本可用 X.Y.Z → A.B.C"横幅 + [立即升级] 按钮
- 用户主动点击 [立即升级] 才触发流程（走与首次安装相同的下载/解压链路，老版本目录原地替换）
- 不弹 toast / 全屏 onboarding，避免打断正在使用的用户

### 10.3 Tauri 安装包变化

- 当前 Tauri MSI / dmg / deb 不变（**不内嵌 Python tarball**）
- 体积保持 ~150MB 量级
- 首次启动按需下载 Python tarball（~250MB）
- 不依赖 `tauri.conf.json` 的 `externalBin` / sidecar 机制（Python 不打包，按需下载到运行时）

### 10.4 自家 Python 运行时 tarball 的发布流程

新增 GitHub Actions workflow：
- 触发：tag `runtime-3.12.4` 推送
- 步骤：
  1. 下载对应 python-build-standalone（三平台 × x86_64/arm64）
  2. 用 `pip install` 把预装库装到运行时的 site-packages
  3. 打包为 `zhiwei-python-runtime-{version}-{platform}-{arch}.tar.zst`
  4. 算 SHA-256，写 .sha256 文件
  5. 上传到 GitHub Releases

CI 文件位置：`.github/workflows/build-python-runtime.yml`

## 11. 验收门槛

- [ ] 全新环境（无 python / 无 docker）安装知微 → 首次启动 onboarding → 选"立即安装" → **从点击按钮到状态转为 Ready 端到端 ≤ 5 分钟**（千兆带宽，包含下载 + SHA-256 校验 + 解压 + VERSION 写入）
- [ ] 装完即跑通 `doc-processor` SKILL（生成一个 .docx 文档）
- [ ] 装完即跑通 `data-analyst` SKILL（用 pandas 处理一个 CSV）
- [ ] 装完即跑通 `code.execute(language=python, code="import pandas; print(pandas.__version__)")`
- [ ] HARDLINE 命中（如 `rm -rf /`）→ 拒绝，不执行
- [ ] DANGEROUS 命中 + yolo=false → 拒绝
- [ ] DANGEROUS 命中 + yolo=true → 放行
- [ ] 用户选"跳过 onboarding" → 主界面正常使用基础对话能力，code.execute 报错引导设置页
- [ ] 设置页"卸载" → 文件清理，状态回 NOT_INSTALLED
- [ ] 单元测试 + 集成测试全绿
- [ ] 三平台冒烟通过

## 12. 未决问题

1. **断网安装**：用户离线场景能否本地导入 tarball？后续考虑提供 offline-installer 变体（先不在范围）
2. **运行时升级时已有 site-packages 处理**：升级 Python 时如何处理用户后续可能 pip install 的额外库？默认策略：升级时备份用户额外安装的库列表，新版本运行时安装后提示用户重装这些库
3. **Anthropic sandbox-runtime 借鉴时机**：bubblewrap / Seatbelt OS-level 沙箱在何时引入？设为 Phase 2，看用户反馈与安全事件
4. **预装库版本固化**：是否在 `requirements.txt` 中锁定具体版本？建议锁定 minor 版本（如 `pandas>=2.2,<2.3`），跟随知微版本迭代

## 13. 关联记忆

- `project_sandbox_docker_roadmap.md` — 沙箱 Docker 模式架构计划（本 spec 落地后该记忆需更新："已选择捆绑 Python 路线，Docker 模式延后为高级隔离 opt-in"）
- `feedback_local_first_download.md` — 本地定位下的文件产物体验（onboarding "立即安装" 是这个理念的延伸）
- `feedback_no_compat.md` — 新项目无需兼容（DockerBooter 改造时不保留旧"系统 Python"逻辑）
- `project_zhiwei_v1_positioning.md` — 知微 v1 产品定位（"通用 Agent"，装完即用对齐"懂你 + 帮你做事"中"帮你做事"半句）
