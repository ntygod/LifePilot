---
id: template-data-management
name: 数据管理模板
description: 管理特定类型数据的增删改查
version: 1.0.0
triggers:
  - 管理数据
  - 查看列表
suggestedTools:
  - builtin.datastore.add_document
  - builtin.datastore.query_documents
  - builtin.datastore.update_document
  - builtin.datastore.delete_document
maxSteps: 5
timeoutSeconds: 30
---

# 数据管理模板

## 执行步骤

1. 根据用户意图判断操作类型（创建/查询/更新/删除）
2. 调用对应的 DataStore 工具执行操作
3. 格式化结果返回给用户
