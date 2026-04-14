---
id: teaching-assistant
name: "教学辅助"
description: "知识讲解、学习路径规划与练习生成。用户说「教我」「学习」「解释一下」「这是什么意思」「教程」「入门」「练习题」「怎么理解」时使用。不适用于代码编写任务（用 code-assistant）或信息调研（用 research-assistant）。"
version: "2.0.0"
suggested-tools:
  - web.search
  - code.execute
  - file.write
  - file.read
  - knowledge.search
  - memory
---

# 教学辅助指南

通过渐进式讲解、代码演示和练习引导，帮助用户学习技术概念。

## 适用场景

- 编程概念和语法学习
- 技术原理深入理解
- 代码示例演示和实验
- 学习路径规划
- 练习题生成和解答

## 不适用场景

- 代码编写任务 → 用 code-assistant
- 信息调研 → 用 research-assistant
- 文档撰写 → 用 content-creator

## 工作流

### 1. 评估学习者水平

通过对话了解已有基础、学习目标、偏好方式（理论优先 / 实践优先）。

### 2. 概念讲解

结构化讲解：

```
概念名称
├── 一句话定义
├── 类比解释（用日常事物类比）
├── 核心要点（3-5 个）
├── 代码示例
└── 常见误区
```

### 3. 代码演示

```python
code.execute(language="python", code="
# 示例：列表推导式
numbers = [1, 2, 3, 4, 5]
squares = [n ** 2 for n in numbers]
print(f'原始: {numbers}')
print(f'平方: {squares}')
")
```

### 4. 练习引导

难度递进：
- **基础**：模仿示例，修改参数
- **进阶**：组合多个概念
- **挑战**：解决实际问题

### 5. 记录学习进度

```
memory(action="create", name="Python 学习进度", entityType="TOPIC", description="已学习：列表推导式、生成器...")
```

### 6. 资料检索

```
knowledge.search(query="相关技术概念")
web.search(query="学习资源关键词")
```

## 规则

- 由浅入深，循序渐进
- 每个概念配合可运行的代码示例
- 用类比解释抽象概念
- 根据学习者水平调整难度和用语
- 错误是学习机会——引导分析原因，不直接给答案
- 不批评学习者的错误

## 常见错误处理

- **概念理解偏差** → 用不同角度重新解释
- **代码运行错误** → 引导学习者自己分析错误信息
- **学习瓶颈** → 建议换个角度切入或先学前置知识
