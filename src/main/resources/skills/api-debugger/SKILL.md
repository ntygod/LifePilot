---
name: api-debugger
description: 当用户要测试或调试 REST / GraphQL 接口、验证响应格式、对比接口文档与实际行为、生成 Mock 数据时使用。关键词：测试接口、调试 API、HTTP 请求、curl、Postman、接口返回、GraphQL、响应格式。浏览器自动化测试用 browser-automation，代码级单元测试用 code-assistant，单次 curl 直接用 shell.exec。
version: 2.0.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - api
      - rest
      - graphql
      - curl
      - http
      - testing
    suggested_tools:
      - shell.exec
      - code.execute
      - file.read
      - file.write
---

# API 调试指南

测试、分析和调试 REST / GraphQL 接口。

## 适用场景

- REST API 请求测试（GET / POST / PUT / DELETE）
- GraphQL 查询和变更测试
- 响应格式验证和错误分析
- 接口文档与实际行为对比
- Mock 数据生成

## 不适用场景

- 浏览器自动化测试 → 用 browser-automation
- 代码级单元测试 → 用 code-assistant
- 简单的单次 curl 命令 → 直接用 `shell.exec`

## 工作流

1. **单端点发请求**：curl 模板按 GET / POST / 认证 / GraphQL 选取，详见参考
2. **响应分析**：同时看状态码和响应体，不只看 body
3. **格式化查看**：`curl | python -m json.tool`
4. **敏感信息**：Token / API Key 不明文显示，用变量替代
5. **批量测试**：先测一个端点确认格式，再批量执行；结果写 `api-test-report.md`
6. **开发期跳 SSL**：`-k` 仅限测试环境，需注明

## 详细参考

- curl 模板 + 响应分析 + 批量测试 + Mock 生成 + 常见错误：`{skill_dir}/references/curl-recipes.md`
</content>
</invoke>