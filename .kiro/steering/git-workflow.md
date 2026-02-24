---
inclusion: always
---

# LifePilot Git 工作流规范

本文档定义 Kiro 在管理 LifePilot 代码仓库时必须遵循的 Git 工作流规范。

---

## 1. 分支模型

### 1.1 长期分支

| 分支 | 用途 | 保护规则 |
|------|------|---------|
| `main` | 发布就绪代码 | 只接受来自 `develop` 的合并，禁止直接提交 |
| `develop` | 集成分支，日常开发汇入点 | 只接受来自 feature / bugfix 分支的合并 |

### 1.2 短期分支

| 分支模式 | 来源 | 合并目标 | 命名示例 |
|---------|------|---------|---------|
| `feature/{name}` | `develop` | `develop` | `feature/agent-loop` |
| `bugfix/{name}` | `develop` | `develop` | `bugfix/budget-overflow` |

### 1.3 分支命名规则

- 使用英文 kebab-case：`feature/llm-router`，不用 `feature/LLM_Router`
- 名称应与 spec 的 feature_name 一致（如 spec 目录为 `.kiro/specs/llm-router/`，分支为 `feature/llm-router`）
- 一个 spec 对应一个 feature 分支

---

## 2. 提交消息规范

### 2.1 格式

```
<type>(<scope>): <中文描述>
```

### 2.2 类型（type）

| 类型 | 用途 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat(agent): 实现 AgentLoop 控制循环` |
| `fix` | 修复缺陷 | `fix(memory): 修复记忆检索空指针异常` |
| `refactor` | 重构（不改变行为） | `refactor(llm): 提取 ChatClient 构建逻辑` |
| `test` | 测试相关 | `test(agent): 添加 StateReducer 属性测试` |
| `docs` | 文档变更 | `docs(skill): 更新 Skill YAML 定义示例` |
| `chore` | 构建、配置、依赖 | `chore: 升级 Spring Boot 至 3.5.3` |

### 2.3 范围（scope）

`agent` / `memory` / `llm` / `skill` / `mcp` / `interaction` / `observability` / `knowledge` / `workflow` / `sync` / `gateway` / `tool`

跨模块变更可省略 scope：`chore: 统一日志格式`

### 2.4 描述规则

- 使用中文
- 祈使语气（"实现"而非"实现了"）
- 不超过 50 个字符
- 不以句号结尾

---

## 3. 提交粒度

### 3.1 原则

- 一个提交做一件事：一个功能点、一个 bug 修复、一次重构
- spec task 的每个子任务完成后提交一次
- 测试代码和实现代码可以在同一个提交中（如果它们属于同一个功能点）

### 3.2 Spec 任务与提交的对应关系

```
spec task 1: 实现 AgentState record
  └─ commit: feat(agent): 实现 AgentState 不可变状态快照

spec task 2: 实现 StateReducer
  ├─ sub-task 2.1: 定义 AgentAction sealed interface
  │   └─ commit: feat(agent): 定义 AgentAction 动作类型层次
  ├─ sub-task 2.2: 实现 reduce 核心逻辑
  │   └─ commit: feat(agent): 实现 StateReducer 纯函数状态转换
  └─ sub-task 2.3: 添加属性测试
      └─ commit: test(agent): 添加 StateReducer 不变量属性测试
```

### 3.3 禁止的提交模式

- 禁止"大杂烩"提交（一个 commit 包含多个不相关变更）
- 禁止空提交消息或无意义消息（如 "update", "fix", "wip"）
- 禁止提交包含 API 密钥、密码等敏感信息的文件

---

## 4. 分支工作流

### 4.1 开始新 spec 开发

```bash
# 从 develop 创建 feature 分支
git checkout develop
git checkout -b feature/{spec-name}
```

### 4.2 开发过程中

- 在 feature 分支上按 task 粒度提交
- 保持提交历史清晰线性

### 4.3 spec 完成后合并

```bash
# 切回 develop，合并 feature 分支
git checkout develop
git merge --no-ff feature/{spec-name}
# --no-ff 保留分支历史，合并提交消息：
# feat({scope}): 完成 {spec-name} 特性开发
```

### 4.4 合并后清理

```bash
git branch -d feature/{spec-name}
```

---

## 5. Kiro 自动化行为约定

### 5.1 何时提交

Kiro 在以下时机执行 `git add` + `git commit`：

| 时机 | 提交内容 | 示例 |
|------|---------|------|
| spec task 的每个子任务完成 | 该子任务涉及的文件 | `feat(agent): 实现 AgentState record` |
| spec task 完成（无子任务时） | 该任务涉及的文件 | `feat(llm): 实现 LLM 路由策略` |
| spec 文档创建/更新 | spec 文件 | `docs(agent): 创建 agent-loop spec 需求文档` |
| 配置文件变更 | 配置文件 | `chore: 添加 Maven 依赖配置` |

### 5.2 何时不提交

- 代码编译不通过时不提交
- 测试未通过时不提交（除非提交的目的就是记录失败的探索性测试）
- 临时调试代码不提交

### 5.3 分支管理

- 开始执行 spec 任务前，确认当前在正确的 feature 分支上
- 如果 feature 分支不存在，自动创建
- 不在 `main` 或 `develop` 上直接提交代码（文档变更除外）

---

## 6. .gitignore 维护

新增构建产物、IDE 配置、运行时生成文件时，及时更新 `.gitignore`。当前已忽略：

- IDE：`.vscode/`, `.idea/`, `*.iml`
- Java/Maven：`target/`, `*.class`, `*.jar`
- Node/Frontend：`node_modules/`, `dist/`
- SQLite：`*.db`, `*.db-journal`, `*.db-wal`
- 环境变量：`.env`, `.env.local`
