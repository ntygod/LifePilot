---
name: api-debugger
description: 当用户要测试或调试 REST / GraphQL 接口、验证响应格式、对比接口文档与实际行为、生成 Mock 数据时使用。
version: 2.1.2
metadata:
  zhiwei:
    tags:
      - api
      - rest
      - graphql
      - curl
      - http
      - testing
      - mock
    suggested_tools:
      - shell.exec
      - code
      - file.read
      - file.write
    outputs:
      - text
      - file
---
# API 调试指南

`curl` / Python `requests` 测试 REST / GraphQL 接口，验证响应格式、对比文档与实际行为。

## 触发判断
- REST API 请求测试（GET / POST / PUT / DELETE / PATCH）
- GraphQL 查询和变更测试
- 响应格式与状态码分析
- 接口文档对比实际返回（找出文档过期 / 行为异常）
- Mock 数据生成（造测试请求体）
- 批量接口测试（一组端点跑一遍）

不要触发：

- 浏览器端交互测试 → browser-automation
- 代码级单元测试 → code-assistant
- 简单单次 curl → 直接 `shell.exec`

## 决策路径

| 场景 | 路径 |
|---|---|
| 单端点测一次 | curl 模板（GET/POST/认证/GraphQL） |
| 验响应格式 | curl + jq / `python -m json.tool` 看结构 |
| 对比文档 vs 实际 | 调一次 → 跟文档逐字段对，输出差异表 |
| 批量测一组 | 先验单个 → 再 shell 循环或 python `requests` |
| Mock 数据 | 按 schema 造请求体（用户给字段约束就照填，没给就给典型值） |

各路径要点：

- **同时看状态码 + 响应体**：单看 200 不够，body 可能是错误结构（"success":false）
- **敏感信息走变量**：Token / API Key 用 `$TOKEN` / `${API_KEY}`，不写明文；写入文件时不带 token
- **批量先试单个**：批量执行前先跑一个验证格式，再 shell 循环或 python 批跑
- **结果落盘**：批量测试结果 `file.write` 写 `api-test-report.md`，含端点 / 状态 / 响应摘要 / 差异
- **`-k` 仅限测试环境**：跳 SSL 证书校验只在 dev / staging 用，给用户结果时注明


## 输出标准

- 单接口调试输出状态码、关键响应字段、请求摘要和异常判断。
- 批量测试生成报告文件时列出路径、端点、状态、差异和复现命令。
- 涉及认证时只展示变量名，不输出明文 token。


## 失败策略

- 缺少 base URL、认证方式或请求体时先追问最小必要字段。
- 网络/证书/超时失败时区分环境问题、认证问题和接口行为问题。
- 不确定接口是否可写时只做只读请求或生成待确认命令。

## 详细参考
- curl 模板 / 响应分析 / 批量测试 / Mock 生成 / 常见错误：`{skill_dir}/references/curl-recipes.md`
