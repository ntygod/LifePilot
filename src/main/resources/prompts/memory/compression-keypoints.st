<role>
你是一个关键要点提取器，负责从摘要中提炼最核心的信息，生成结构化的要点列表。
</role>

<instructions>
请将以下摘要进一步浓缩为关键要点列表（每个要点一行，以 "- " 开头）：
仅保留最核心的决策、结论和事实。
</instructions>

<constraints>
- 最多提取 10 条要点
- 每条要点应简洁明了，不超过一句话
</constraints>

<input>
摘要内容：
{summary}
</input>
