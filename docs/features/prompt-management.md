# 提示词管理特性说明

## 1. 功能概述

提示词管理特性将项目中 14 处硬编码 LLM 提示词统一外部化到 classpath 资源文件（`.st` 模板），通过 Spring AI PromptTemplate 实现模板化管理，同时对提示词内容进行结构化优化。

用户价值：
- 提示词独立于代码迭代，修改后无需重新编译
- 统一的模板引擎消除变量插值方式不一致问题
- 结构化提示词提升 LLM 响应质量和一致性

## 2. 核心特性

### 2.1 PromptRegistry 提示词注册中心

- 启动时自动扫描 `classpath:prompts/` 目录，注册所有 `.st` 模板文件
- 提供 `render(key, variables)` 统一渲染接口
- ConcurrentHashMap 缓存已加载模板，避免重复 I/O
- 模板键与文件路径映射：`agent/understanding` → `prompts/agent/understanding.st`

### 2.2 提示词模板外部化

- 所有 LLM 提示词从 Java 源码迁移到 `src/main/resources/prompts/` 目录
- 使用 Spring AI PromptTemplate（StringTemplate 4 引擎）
- 变量占位符：`{variable}` 语法
- 支持条件渲染和列表迭代

### 2.3 提示词内容结构化优化

- 采用 XML 标签分区：`<role>` / `<context>` / `<instructions>` / `<constraints>` / `<output_format>`
- 遵循 Context Engineering 范式优化信息密度
- 为关键提示词补充 few-shot 示例
- 统一输出格式约束

## 3. 使用场景

### 场景 1：修改 Agent 阶段提示词
编辑 `src/main/resources/prompts/agent/understanding.st`，重启应用即可生效，无需修改 Java 代码。

### 场景 2：新增 Skill 提示词
在 `src/main/resources/prompts/skill/` 下创建新的 `.st` 文件，PromptRegistry 自动发现并注册。

### 场景 3：动态变量注入
```java
var prompt = promptRegistry.render("proactive/evaluation", Map.of(
    "maxContentLength", config.getMaxContentLength(),
    "type", candidate.type(),
    "urgency", candidate.urgency(),
    "reason", candidate.reason()
));
```

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| lifepilot.prompt.base-path | classpath:prompts/ | 提示词模板根目录 |

## 5. 限制与未来扩展

### 当前限制
- 模板仅支持 classpath 加载，不支持运行时热更新
- 无提示词版本管理和 A/B 测试能力
- 无提示词效果评估指标

### 未来扩展方向
- 集成 Promptfoo 进行提示词回归测试
- 支持文件系统监听实现热更新
- 提示词版本化 + 效果对比评估