# CLI 交互层架构设计

> **文档性质**：深度架构设计文档（Developer-Facing）
> **目标读者**：核心开发者、架构评审者
> **模块归属**：`com.lifepilot.interaction.cli`
> **最后更新**：2026-02
> **从属关系**：本文档从 [ARCHITECTURE.md](../ARCHITECTURE.md) 拆分而来，聚焦 CLI 交互层的完整设计。

---

## 目录

- [1. 设计哲学与原则](#1-设计哲学与原则)
- [2. 整体架构](#2-整体架构)
- [3. CliShell — 交互式主循环](#3-clishell--交互式主循环)
- [4. CommandRouter — 命令路由](#4-commandrouter--命令路由)
- [5. FastPathRunner — 快速路径](#5-fastpathrunner--快速路径)
- [6. ResponseRenderer — 输出渲染](#6-responserenderer--输出渲染)
- [7. CliCompleter — 智能补全](#7-clicompleter--智能补全)
- [8. 快捷命令体系](#8-快捷命令体系)
- [9. 前沿研究与竞品分析](#9-前沿研究与竞品分析)
- [10. 配置参考](#10-配置参考)

---

## 1. 设计哲学与原则

### 1.1 核心命题：AI Agent 的 CLI 不是传统 CLI

传统 CLI 工具（如 `git`、`docker`、`kubectl`）遵循 UNIX 哲学：一个命令做一件事，输入参数、输出结果、退出。交互模式是**单次请求-响应**。

AI Agent 的 CLI 面临根本不同的交互模式：

```
传统 CLI 交互模型：
  用户 → 命令 + 参数 → 程序执行 → 输出结果 → 退出
  延迟：< 100ms，确定性输出

AI Agent CLI 交互模型：
  用户 → 自然语言 → LLM 推理 → 多步工具调用 → 流式输出 → 等待下一轮
  延迟：2s ~ 120s，非确定性输出，多轮对话上下文
```

这个差异直接导出 LifePilot CLI 的核心设计决策：

1. **双模式架构**：交互式对话模式（长连接、多轮）+ 单次命令模式（快进快出）
2. **快速路径优化**：简单命令（`--help`、`--version`）跳过 Spring 上下文初始化，实现毫秒级响应
3. **流式渲染**：LLM 响应逐字输出，而非等待完整响应后一次性显示
4. **CJK 宽字符感知**：中文环境下表格对齐需要正确计算字符显示宽度

### 1.2 前沿 CLI UX 研究基础

LifePilot CLI 的设计参考了以下前沿实践：

| 来源 | 核心洞察 | LifePilot 映射 |
|------|---------|---------------|
| [bettercli.org](https://bettercli.org/) CLI Design Guide | CLI 应提供渐进式发现：先展示最常用命令，高级选项按需探索 | `CliCompleter` 按使用频率排序补全建议 |
| [Lucas F. Costa — UX Patterns for CLI Tools](https://lucasfcosta.com/2022/06/01/ux-patterns-cli-tools.html) | 好的 CLI 应以示例开头而非手册页；错误消息应包含修复建议 | `ResponseRenderer` 错误输出附带建议操作 |
| [JLine 3 官方文档](https://jline.github.io/docs/advanced/library-integration) | JLine + Picocli 集成模式：JLine 负责终端交互，Picocli 负责命令解析 | LifePilot 采用 JLine 交互 + 自定义 `CommandRouter` 路由 |
| [caduh — Make Your CLI a Joy to Use](https://www.caduh.com/blog/make-your-cli-a-joy-to-use) | 一致的子命令结构、智能默认值、TTY 感知的颜色输出 | `QuickCommand` 统一子命令模式，`ResponseRenderer` TTY 感知 |

Content was rephrased for compliance with licensing restrictions.

### 1.3 四条核心设计原则

#### 原则 1：零等待快速路径

简单命令不应为 Spring Boot 启动付出代价。`FastPathRunner` 在 `main()` 方法中拦截 `--help`、`--version` 等命令，直接输出结果并退出，响应时间 < 50ms。

#### 原则 2：对话优先，命令辅助

CLI 的主要交互模式是自然语言对话（`lifepilot chat`），快捷命令（`lifepilot todo list`）是对话的快捷方式。两种模式共享同一个 Agent 引擎和 Skill 系统。

#### 原则 3：CJK 感知的输出渲染

中文字符在终端中占 2 个字符宽度，ASCII 字符占 1 个。表格对齐、进度条、分隔线都必须正确计算显示宽度，否则输出会错位。

#### 原则 4：优雅降级

终端不支持彩色输出时自动降级为纯文本；终端宽度不足时自动换行；LLM 不可用时快捷命令仍可正常工作（直接调用 Repository）。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLI 交互层架构                                │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    入口层（main 方法）                          │  │
│  │                                                               │  │
│  │  java -jar lifepilot.jar [args]                               │  │
│  │       │                                                       │  │
│  │       ├── --help / --version → FastPathRunner（跳过 Spring）   │  │
│  │       │                                                       │  │
│  │       └── 其他命令 → Spring Boot 启动 → CliShell              │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                            │                                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    CliShell（JLine 3 主循环）                   │  │
│  │                                                               │  │
│  │  LineReader.readLine("LifePilot> ")                           │  │
│  │       │                                                       │  │
│  │       ├── 空行 / quit / exit → 退出                           │  │
│  │       │                                                       │  │
│  │       └── 用户输入 → CommandRouter                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                            │                                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    CommandRouter（命令路由）                    │  │
│  │                                                               │  │
│  │  ┌─────────────────┐    ┌──────────────────────────────────┐ │  │
│  │  │ 快捷命令前缀匹配  │    │ 自然语言 → ChatCommand           │ │  │
│  │  │ todo / schedule  │    │ → AgentLoop → LLM → 工具调用     │ │  │
│  │  │ habit / llm      │    │ → 流式输出                       │ │  │
│  │  │ mcp / skill      │    └──────────────────────────────────┘ │  │
│  │  └────────┬────────┘                                          │  │
│  │           │                                                   │  │
│  │     QuickCommand                                              │  │
│  │  （直接调用 Repository）                                       │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                            │                                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    ResponseRenderer（输出渲染）                 │  │
│  │                                                               │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐│  │
│  │  │ 表格渲染  │ │ 流式输出  │ │ Emoji 图标│ │ CJK 宽度计算    ││  │
│  │  │ 对齐填充  │ │ 逐字打印  │ │ 优先级色  │ │ displayWidth()  ││  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘│  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    CliCompleter（智能补全）                     │  │
│  │                                                               │  │
│  │  AggregateCompleter                                           │  │
│  │  ├── todo {list|add|done|delete}                              │  │
│  │  ├── schedule {list|add|today|tomorrow}                       │  │
│  │  ├── habit {list|checkin|status}                              │  │
│  │  ├── llm {list|add|test|remove}                               │  │
│  │  ├── mcp {list|add|remove|test}                               │  │
│  │  ├── skill {list|info|reload}                                 │  │
│  │  └── {chat|help|version|quit|exit}                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. CliShell — 交互式主循环

### 3.1 职责

`CliShell` 是 CLI 层的入口组件，实现 Spring Boot 的 `CommandLineRunner`，在应用启动后进入 JLine 3 交互式循环。

### 3.2 核心设计

```java
/**
 * CLI 交互式主循环。
 *
 * <p>基于 JLine 3 的 LineReader 实现 REPL（Read-Eval-Print Loop）。
 * 启动时显示欢迎横幅，然后进入循环等待用户输入。</p>
 *
 * <p>关键设计决策：
 * <ul>
 *   <li>单次命令模式：命令行参数非空时执行一次后退出</li>
 *   <li>交互式模式：无参数时进入 REPL 循环</li>
 *   <li>优雅退出：quit/exit/Ctrl+D 退出循环</li>
 *   <li>异常隔离：单次命令执行异常不终止循环</li>
 * </ul></p>
 */
```

### 3.3 双模式启动

| 模式 | 触发条件 | 行为 | 示例 |
|------|---------|------|------|
| 交互式 | `java -jar lifepilot.jar` | 显示欢迎横幅，进入 REPL | `LifePilot> 今天有什么安排？` |
| 单次命令 | `java -jar lifepilot.jar todo list` | 执行命令，输出结果，退出 | 直接输出待办列表 |

### 3.4 欢迎横幅

启动时显示 ASCII Art 横幅 + 版本信息 + 快捷提示，帮助用户快速了解可用命令。横幅通过 `ResponseRenderer` 渲染，支持彩色输出。

---

## 4. CommandRouter — 命令路由

### 4.1 路由策略

`CommandRouter` 是 CLI 层的核心路由组件，负责将用户输入分发到正确的处理器。

```
用户输入
  │
  ├── 以快捷命令前缀开头？
  │     ├── todo / schedule / habit → 对应 QuickCommand
  │     ├── llm / mcp / skill      → 对应 QuickCommand
  │     └── help / version          → 内置处理
  │
  └── 其他 → ChatCommand（自然语言对话）
```

### 4.2 快捷命令 vs 对话命令

| 维度 | 快捷命令（QuickCommand） | 对话命令（ChatCommand） |
|------|------------------------|----------------------|
| 路由依据 | 前缀精确匹配 | 默认兜底 |
| 执行路径 | 直接调用 Repository/Service | AgentLoop → LLM → 工具调用 |
| 延迟 | < 100ms | 2s ~ 120s |
| LLM 依赖 | 无 | 必须 |
| 会话上下文 | 无 | 多轮对话 |
| 适用场景 | CRUD 操作、配置管理 | 复杂推理、多步任务 |

### 4.3 设计决策：为什么不全走 Agent？

快捷命令直接调用 Repository 而非通过 AgentLoop，原因：

1. **延迟**：`todo list` 不需要 LLM 推理，直接查数据库 < 50ms
2. **可靠性**：LLM 不可用时快捷命令仍可工作
3. **成本**：简单 CRUD 不消耗 Token
4. **确定性**：快捷命令输出格式固定，不受 LLM 随机性影响

---

## 5. FastPathRunner — 快速路径

### 5.1 问题背景

Spring Boot 应用启动需要 2-5 秒（扫描 Bean、初始化数据源、加载 Flyway 迁移等）。对于 `--help` 和 `--version` 这类命令，用户期望毫秒级响应。

### 5.2 解决方案

`FastPathRunner` 在 `main()` 方法中、Spring 上下文初始化之前拦截特定命令：

```
java -jar lifepilot.jar --help
  │
  ├── FastPathRunner.tryRun(args)
  │     ├── 匹配 --help → 输出帮助信息 → System.exit(0)
  │     ├── 匹配 --version → 输出版本号 → System.exit(0)
  │     └── 不匹配 → 返回 false，继续 Spring 启动
  │
  └── SpringApplication.run(...)
```

### 5.3 性能对比

| 命令 | 无快速路径 | 有快速路径 | 提升 |
|------|-----------|-----------|------|
| `--help` | ~3000ms | ~30ms | 100x |
| `--version` | ~3000ms | ~20ms | 150x |
| `todo list` | ~3000ms | ~3000ms | 无（需要 Spring） |

### 5.4 竞品参考

| 工具 | 快速路径实现 | 说明 |
|------|------------|------|
| Picocli | `@Command` 注解 + 延迟初始化 | 命令解析在 Spring 之前完成 |
| GraalVM native-image | AOT 编译 | 启动时间 < 100ms，但编译复杂 |
| Spring Boot Lazy Init | `spring.main.lazy-initialization=true` | 减少启动时间但不消除 |
| LifePilot FastPathRunner | `main()` 拦截 | 最简单直接，零依赖 |

---

## 6. ResponseRenderer — 输出渲染

### 6.1 职责

`ResponseRenderer` 负责将结构化数据渲染为终端友好的输出格式。

### 6.2 核心能力

#### 6.2.1 CJK 宽字符计算

中文、日文、韩文字符在等宽终端中占 2 个字符宽度。`displayWidth()` 方法正确计算字符串的显示宽度：

```
"Hello"     → displayWidth = 5  （5 个 ASCII 字符）
"你好世界"   → displayWidth = 8  （4 个 CJK 字符 × 2）
"Hello你好"  → displayWidth = 9  （5 + 4）
```

这个计算影响表格列对齐、分隔线长度、文本截断位置。

#### 6.2.2 表格渲染

支持自动列宽计算和 CJK 对齐的表格输出：

```
📋 待办列表（3 项）
┌──────┬──────────────────┬──────┬────────────┐
│ 优先级│ 标题              │ 状态  │ 截止日期    │
├──────┼──────────────────┼──────┼────────────┤
│ 🔴 高 │ 提交Q1报告        │ 待办  │ 2026-02-28 │
│ 🟡 中 │ 回复客户邮件      │ 进行中│ 2026-02-27 │
│ 🟢 低 │ 整理文档          │ 待办  │ 2026-03-01 │
└──────┴──────────────────┴──────┴────────────┘
```

#### 6.2.3 流式输出

LLM 响应通过流式方式逐字输出，提供打字机效果：

```java
// 流式渲染：逐字符输出，遇到换行刷新
public void streamOutput(String text) {
    for (char c : text.toCharArray()) {
        terminal.writer().print(c);
        terminal.writer().flush();
    }
}
```

#### 6.2.4 Emoji 与优先级颜色

| 优先级 | Emoji | 颜色 |
|--------|-------|------|
| HIGH | 🔴 | 红色 |
| MEDIUM | 🟡 | 黄色 |
| LOW | 🟢 | 绿色 |
| 完成 | ✅ | 灰色 |

---

## 7. CliCompleter — 智能补全

### 7.1 实现方式

基于 JLine 3 的 `AggregateCompleter`，组合所有快捷命令的补全路径：

```
Tab 补全树：
├── todo
│   ├── list
│   ├── add
│   ├── done
│   └── delete
├── schedule
│   ├── list
│   ├── add
│   ├── today
│   └── tomorrow
├── habit
│   ├── list
│   ├── checkin
│   └── status
├── llm
│   ├── list
│   ├── add
│   ├── test
│   └── remove
├── mcp
│   ├── list
│   ├── add
│   ├── remove
│   └── test
├── skill
│   ├── list
│   ├── info
│   └── reload
├── chat
├── help
├── version
├── quit
└── exit
```

### 7.2 补全体验

用户输入 `to` 后按 Tab：
- 如果只有一个匹配（`todo`）→ 自动补全
- 如果有多个匹配 → 显示候选列表

---

## 8. 快捷命令体系

### 8.1 命令总览

| 命令 | 子命令 | 说明 | 执行路径 |
|------|--------|------|---------|
| `todo` | `list` / `add` / `done` / `delete` | 待办管理 | TodoRepository |
| `schedule` | `list` / `add` / `today` / `tomorrow` | 日程管理 | ScheduleRepository |
| `habit` | `list` / `checkin` / `status` | 习惯管理 | HabitRepository |
| `llm` | `list` / `add` / `test` / `remove` | LLM 配置 | ProviderRegistry |
| `mcp` | `list` / `add` / `remove` / `test` | MCP 配置 | McpServerRegistry |
| `skill` | `list` / `info` / `reload` | Skill 管理 | SkillRegistry |

### 8.2 QuickCommand 设计模式

每个快捷命令实现统一的 `QuickCommand` 接口，通过 `CommandRouter` 注册：

```java
public interface QuickCommand {
    /** 命令前缀（如 "todo"）。 */
    String prefix();

    /** 执行命令。 */
    void execute(String subCommand, List<String> args, Terminal terminal);
}
```

---

## 9. 前沿研究与竞品分析

### 9.1 AI Agent CLI 交互模式对比

| 产品 | CLI 支持 | 交互模式 | 快速路径 | CJK 支持 | 补全 |
|------|---------|---------|---------|---------|------|
| OpenClaw | ❌ 无 CLI | Web UI only | — | — | — |
| AstrBot | ❌ 无 CLI | 消息平台 only | — | — | — |
| Aider | ✅ 完整 CLI | REPL + 单次 | ❌ | 部分 | Git 文件补全 |
| Claude Code | ✅ 完整 CLI | REPL | ❌ | ✅ | 文件路径补全 |
| LifePilot | ✅ 完整 CLI | REPL + 单次 + 快速路径 | ✅ | ✅ | 命令 + 子命令补全 |

### 9.2 JLine 3 vs 竞品终端库

| 库 | 语言 | 特性 | LifePilot 选择理由 |
|---|------|------|-------------------|
| JLine 3 | Java | 补全、高亮、历史、多行编辑 | Java 生态最成熟的终端库 |
| Picocli | Java | 注解式命令解析、类型转换 | 适合纯命令式 CLI，不适合对话式 |
| Lanterna | Java | TUI 框架（类 ncurses） | 过重，LifePilot 不需要 TUI |
| Bubbletea | Go | 函数式 TUI 框架 | Go 生态，不适用 |
| Ink | Node.js | React 式终端 UI | Node.js 生态，不适用 |

### 9.3 CLI 启动优化研究

AI Agent CLI 的启动时间是关键 UX 指标。用户对 `--help` 的期望响应时间 < 100ms，但 Spring Boot 启动通常需要 2-5 秒。

业界解决方案对比：

| 方案 | 启动时间 | 复杂度 | 适用性 |
|------|---------|--------|--------|
| GraalVM native-image | < 100ms | 高（反射配置、AOT 限制） | 适合发布版本 |
| Spring Boot Lazy Init | ~1.5s | 低 | 减少但不消除 |
| CRaC（Coordinated Restore at Checkpoint） | < 200ms | 中 | JDK 21+ 实验性 |
| FastPathRunner（LifePilot） | < 50ms | 极低 | 仅限无依赖命令 |

LifePilot 当前采用 `FastPathRunner` 作为最小成本方案，未来可考虑 GraalVM native-image 进一步优化。

---

## 10. 配置参考

```yaml
lifepilot:
  cli:
    # 欢迎横幅开关
    show-banner: true
    # 提示符
    prompt: "LifePilot> "
    # 历史记录文件
    history-file: "~/.lifepilot/cli-history"
    # 最大历史记录条数
    max-history-size: 1000
    # 流式输出开关
    stream-output: true
```
