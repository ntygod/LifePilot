# API 调试命令速查

## curl 模板

### GET

```bash
shell.exec(command="curl -s -w '\\nHTTP_CODE:%{http_code}' 'https://api.example.com/users'")
```

### POST（JSON）

```bash
shell.exec(command="curl -s -X POST -H 'Content-Type: application/json' -d '{\"name\":\"test\"}' 'https://api.example.com/users'")
```

### 带认证

```bash
shell.exec(command="curl -s -H 'Authorization: Bearer TOKEN' 'https://api.example.com/protected'")
```

### GraphQL

```bash
shell.exec(command="curl -s -X POST -H 'Content-Type: application/json' -d '{\"query\":\"{ users { id name } }\"}' 'https://api.example.com/graphql'")
```

## 响应分析检查点

- HTTP 状态码是否符合预期
- 响应体结构是否与文档一致
- 错误响应是否包含有用信息
- Content-Type 是否正确

格式化输出：

```bash
shell.exec(command="curl -s 'URL' | python -m json.tool")
```

## 批量测试 OpenAPI

读取接口文档或 OpenAPI spec：

```
file.read(path="openapi.yaml")
```

逐个端点测试，记录结果：

```
file.write(path="api-test-report.md", content="测试报告")
```

## Mock 数据生成

```python
code.execute(language="python", code="
import json, random, string
mock_data = [{'id': i, 'name': ''.join(random.choices(string.ascii_letters, k=8))} for i in range(10)]
print(json.dumps(mock_data, indent=2))
")
```

## 常见错误处理

- **连接超时** → 检查 URL 和网络，增加 `--connect-timeout` 参数
- **401/403** → 检查认证信息（Token / API Key）
- **CORS 错误** → 这是浏览器限制，curl 不受影响
- **SSL 错误** → 开发环境用 `-k`，生产环境检查证书
</content>
</invoke>