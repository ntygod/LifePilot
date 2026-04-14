# 记忆提取质量评估数据集

通过边界对话场景验证实体提取、经验提取、巩固管线的判断能力。

## 使用方法

### 实体提取 / 经验提取测试

1. 启动系统：`mvn spring-boot:run`
2. 打开 Web UI（http://localhost:5173）
3. 按场景文件创建新会话
4. 依次发送 `conversation` 中的用户消息
5. 等待 5-10 秒（异步提取完成）
6. 检查提取结果：
   - 实体：`GET /api/memories/entities?type={TYPE}`
   - 经验：`GET /api/memories/entities?type=EXPERIENCE`
7. 对照 `expected` / `should_not_extract` 在文件末尾填写 `actual_result`

### 巩固管线测试

1. 先跑完 `prerequisite_sessions` 列出的前置场景
2. 触发巩固：`POST /api/memories/consolidate`
3. 触发去重：`POST /api/memories/deduplicate`
4. 检查结果变化（importance 变化、实体合并、偏好同步等）
5. 对照 `expected_after_trigger` 填写结果

### 结果标注格式

在场景文件末尾追加：

```yaml
actual_result:
  date: 2026-04-XX
  extracted:
    - type: PREFERENCE
      name: "实际提取的名称"
      confidence: 0.85
      verdict: CORRECT       # CORRECT | WRONG_TYPE | MISSING | UNEXPECTED
  missed: []
  unexpected: []
  notes: "补充说明"
```

## 场景目录

| 类别 | 场景数 | 测试焦点 |
|------|--------|---------|
| entity-extraction/boundary-1 | 3 | 隐含 vs 显式表达 |
| entity-extraction/boundary-2 | 3 | 临时状态 vs 持久事实 |
| entity-extraction/boundary-3 | 3 | 转述他人 vs 自我陈述 |
| entity-extraction/boundary-4 | 3 | 矛盾更新与假设性表述 |
| entity-extraction/boundary-5 | 2 | 多实体纠缠与角色区分 |
| entity-extraction/boundary-6 | 3 | 领域知识 vs 个人记忆边界 |
| experience-extraction | 6 | 经验归因、去重、门控 |
| consolidation | 6 | 合并、晋升、衰减、空间隔离 |
