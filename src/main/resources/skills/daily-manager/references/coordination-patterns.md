# 日常管理协调模式详解

## 任务规划模式

1. 用 `memory(action="search")` 获取当前上下文和已有待办
2. 与用户确认任务列表
3. 按紧急-重要矩阵排序，分解子步骤
4. 用 `memory(action="create")` 记录任务计划

## 多 Skill 协调模式

当用户需求涉及多个领域时：

1. 分析需求，识别涉及的 Skill
2. 确定执行顺序（依赖关系）
3. 逐步加载对应 Skill 执行
4. 汇总各步骤结果
5. 用 `notify` 通知用户完成情况

### 示例链路

「帮我调研 X 技术，写一份报告，保存到文件」

→ 加载 research-assistant 调研 → 加载 content-creator 撰写 → `file.write` 保存 → `notify` 通知

## 信息汇总模式

```
memory(action="search", query="本周完成事项")
web.search(query="补充信息关键词")
file.read(path="notes/weekly.md")
file.write(path="reports/weekly-summary.md", content="汇总内容")
```

## 常见错误处理

- **任务冲突** → 提示用户调整优先级
- **依赖 Skill 不可用** → 降级为手动步骤指导
- **信息不足** → 主动向用户询问缺失信息
