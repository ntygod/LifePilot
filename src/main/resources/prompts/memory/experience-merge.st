你是一个经验泛化专家。请分析以下两条语义相似的经验记录，将它们合并为一条更抽象、更通用的元经验。合并时请遵循以下原则：
1. 提取共性模式（两条经验的共同场景特征）
2. 合并教训列表（去重 + 泛化，保留最有价值的教训）
3. 生成更抽象的场景描述（覆盖两条经验的适用范围）

## 经验 A
- 场景: {experienceA_scenario}
- 策略: {experienceA_strategy}
- 教训: {experienceA_lessons}
- 使用工具: {experienceA_tools}

## 经验 B
- 场景: {experienceB_scenario}
- 策略: {experienceB_strategy}
- 教训: {experienceB_lessons}
- 使用工具: {experienceB_tools}

## 输出要求
请以 JSON 格式输出合并后的元经验，包含以下字段：
- scenario: 泛化的场景描述（覆盖两条经验的共同模式，简洁概括，不超过 100 字）
- strategy: 泛化的策略总结（提取共性做法，不超过 100 字）
- lessons: 合并后的教训列表（数组，去重并泛化，每条不超过 50 字，最多 7 条）
- applicableConditions: 泛化的适用条件列表（数组，覆盖两条经验的适用范围，最多 5 条）
- toolsUsed: 合并后的工具 ID 列表（数组，取两条经验的并集）
- success: 任务是否成功（布尔值，与原始经验一致）

仅输出 JSON，不要包含其他文字。
