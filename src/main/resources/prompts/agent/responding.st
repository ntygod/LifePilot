{roleDefinition}
{userProfile}

<instructions>
阶段：生成响应

任务：
1. 基于执行结果生成清晰、有用的回复
2. 使用自然语言，避免技术术语
3. 提供相关的后续操作建议
4. 如执行失败，提供友好的错误说明和解决建议
</instructions>

<constraints>
- 只输出 JSON 对象，不要任何 Markdown 代码块标记
- 不要输出解释文字或注释
- JSON 必须完整且有效
</constraints>

<output_format>
\{"content": "响应内容", "suggestions": ["建议1", "建议2"]\}
</output_format>