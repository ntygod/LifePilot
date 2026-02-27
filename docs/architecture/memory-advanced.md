> 本文档为模块 23「记忆系统进阶」的架构设计文档。
> 基于 `docs/architecture/memory-system.md` 已有的四层认知记忆架构，扩展 L4 程序记忆、记忆巩固管线、MaRS 认知遗忘策略，并对两个待评估点（Agentic GraphRAG、Idle-Driven 记忆巩固）给出深度分析和决策。

# 记忆系统进阶架构设计

---

## 1. 模块定位与职责边界

### 1.1 模块定位

模块 23 是记忆系统的「演化层」——在 Phase 2 已完成的 L1-L3 记忆存储与检索基础上，补全记忆的**生命周期管理**能力：

- **L4 程序记忆**：从重复的成功执行模式中自动提炼操作模板和用户偏好规则
- **记忆巩固管线**：实现情景记忆→语义记忆、情景记忆→程序记忆的双向巩固流程
- **MaRS 认知遗忘**：基于 MaRS 论文的 Hybrid 遗忘策略框架，实现预算约束下的智能遗忘

### 1.2 职责边界

| 属于本模块 | 不属于本模块 |
|-----------|------------|
| L4 程序记忆的数据模型、存储、检索 | L1-L3 记忆层的核心实现（Phase 2 已完成） |
| 情景→语义巩固管线 | 知识提取管线 KnowledgeExtractionPipeline（Phase 2 已完成） |
| 情景→程序巩固管线 | ContextAssembler 完整版（Phase 2 已完成） |
| Hybrid 遗忘策略引擎 | 对话压缩 CompressionService（Phase 2 已完成） |
| 巩固/遗忘的调度触发机制 | HybridRetriever 三路检索（Phase 2 已完成，本模块仅扩展 L4 检索路径） |

### 1.3 前置依赖

| 依赖模块 | 依赖内容 |
|---------|---------|
| 模块 5 记忆系统基础 ✅ | L1 WorkingMemory、L2 EpisodicMemory、SqliteVecStore |
| 模块 6 语义记忆 ✅ | L3 SemanticMemory、TemporalEntity、HybridRetriever、GraphTraverser |
| 模块 7 ContextAssembler ✅ | Token 预算分配、记忆检索槽位填充 |
| 模块 1 LLM Router ✅ | 巩固管线需要 LLM 调用进行知识提炼 |

---

## 2. 前沿研究基础

### 2.1 核心参考研究

