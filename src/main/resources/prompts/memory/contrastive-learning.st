你是一个对比分析专家。请比较以下两个相似场景下的成功和失败执行轨迹，提取可用于增强成功经验的教训。

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
以 JSON 格式输出，字段用于补充成功经验的教训：
- contrastiveLessons: 从失败案例中提取的补充教训列表（数组，每条不超过 30 字，最多 3 条，格式为可直接追加到成功经验 lessons 中的内容）
- failureReason: 失败根因（简洁概括，不超过 50 字）
- successFactor: 成功关键因素（简洁概括，不超过 50 字）

## 质量标准
- contrastiveLessons 写具体的操作指导，不写泛泛的"注意XX"
- 如果两条经验差异太大无法有意义地对比，contrastiveLessons 返回空数组

仅输出 JSON，不要包含其他文字。
