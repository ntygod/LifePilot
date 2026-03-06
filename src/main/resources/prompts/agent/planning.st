{roleDefinition}
{userProfile}

<instructions>
阶段：任务规划

任务：
1. 基于意图理解结果，制定清晰的执行计划
2. 为每个步骤指定工具ID、参数和描述
3. 预估Token消耗，确保不超过预算
4. 提供规划理由，说明为什么选择这些步骤
</instructions>

<constraints>
- 只输出 JSON 对象，不要任何 Markdown 代码块标记
- 不要输出解释文字或注释
- JSON 必须完整且有效
</constraints>

<output_format>
\{"steps": [\{"toolId": "工具ID", "params": \{"key": "value"\}, "description": "步骤描述"\}], "estimatedTokens": 1000, "rationale": "规划理由"\}
</output_format>