---
name: api-debugger
description: 当用户要测试或调试 REST / GraphQL 接口、验证响应格式、对比接口文档与实际行为、生成 Mock 数据时使用。关键词：测试接口、调试 API、HTTP 请求、curl、Postman、接口返回、GraphQL、响应格式。浏览器自动化测试用 browser-automation，代码级单元测试用 code-assistant，单次 curl 直接用 shell.exec。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
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

### 发送请求

**GET：**
```bash
shell.exec(command="curl -s -w '\\nHTTP_CODE:%{http_code}' 'https://api.example.com/users'")
```

**POST（JSON）：**
```bash
shell.exec(command="curl -s -X POST -H 'Content-Type: application/json' -d '{\"name\":\"test\"}' 'https://api.example.com/users'")
```

**带认证：**
```bash
shell.exec(command="curl -s -H 'Authorization: Bearer TOKEN' 'https://api.example.com/protected'")
```

**GraphQL：**
```bash
shell.exec(command="curl -s -X POST -H 'Content-Type: application/json' -d '{\"query\":\"{ users { id name } }\"}' 'https://api.example.com/graphql'")
```

### 分析响应

检查要点：
- HTTP 状态码是否符合预期
- 响应体结构是否与文档一致
- 错误响应是否包含有用信息
- Content-Type 是否正确

格式化输出：
```bash
shell.exec(command="curl -s 'URL' | python -m json.tool")
```

### 批量测试

读取接口文档或 OpenAPI spec：
```
file.read(path="openapi.yaml")
```

逐个端点测试，记录结果：
```
file.write(path="api-test-report.md", content="测试报告")
```

### Mock 数据生成

```python
code.execute(language="python", code="
import json, random, string
mock_data = [{'id': i, 'name': ''.join(random.choices(string.ascii_letters, k=8))} for i in range(10)]
print(json.dumps(mock_data, indent=2))
")
```

## 规则

- 每次请求输出 HTTP 状态码，不只看响应体
- 认证信息（Token / API Key）不在输出中明文显示，用变量替代
- 开发环境可用 `-k` 跳过 SSL 验证，但必须注明"仅限测试"
- 批量测试时先测一个端点确认格式，再批量执行

## 常见错误处理

- **连接超时** → 检查 URL 和网络，增加 `--connect-timeout` 参数
- **401/403** → 检查认证信息（Token / API Key）
- **CORS 错误** → 这是浏览器限制，curl 不受影响
- **SSL 错误** → 开发环境用 `-k`，生产环境检查证书
