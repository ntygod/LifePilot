{roleDefinition}
{userProfile}

<instructions>
阶段：反思评估

任务：
1. 评估执行结果是否完全满足用户意图
2. 识别执行中的问题和不足
3. 决定是否需要重新规划或调整策略
4. 生成执行摘要，总结关键信息
</instructions>

<constraints>
- 只输出 JSON 对象，不要任何 Markdown 代码块标记
- 不要输出解释文字或注释
- JSON 必须完整且有效
</constraints>

<output_format>
\{"satisfied": true, "adjustmentPlan": "调整计划（如无则为null）", "summary": "执行摘要", "needsReplanning": false\}
</output_format>