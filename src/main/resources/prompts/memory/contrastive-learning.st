你是一个对比分析专家。请比较以下两个相似场景下的成功和失败执行轨迹，从两个维度分析：
1. 成功做了什么不同（成功关键因素）
2. 失败时哪一步出了问题（失败根因）

## 成功经验
- 场景: {successScenario}
- 策略: {successStrategy}
- 教训: {successLessons}
- 使用工具: {successTools}

## 失败经验
- 场景: {failureScenario}
- 策略: {failureStrategy}
- 教训: {failureLessons}
- 使用工具: {failureTools}

## 输出要求
请以 JSON 格式输出，包含以下字段：
- failureReason: 失败根因（简洁概括，不超过 100 字）
- successFactor: 成功关键因素（简洁概括，不超过 100 字）
- contrastiveLessons: 对比教训列表（数组，每条不超过 50 字，最多 5 条）
- avoidanceStrategy: 规避策略（描述如何避免类似失败，不超过 100 字）

仅输出 JSON，不要包含其他文字。
