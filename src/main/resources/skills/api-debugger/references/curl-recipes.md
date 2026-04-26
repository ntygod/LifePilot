# API 调试速查

curl / requests 调试 REST 与 GraphQL。敏感凭证一律走环境变量 `${TOKEN}` / `${API_KEY}`。

## curl 模板（按方法）

### GET

```bash
shell.exec(command="curl -sS -w '\\nHTTP_CODE:%{http_code}\\n' '<url>'")
shell.exec(command="curl -sS -G '<url>' --data-urlencode '<key>=<value>'")
```

### POST / PUT / PATCH（JSON）

```bash
shell.exec(command="curl -sS -X POST -H 'Content-Type: application/json' -d '<json body>' '<url>'")
shell.exec(command="curl -sS -X PUT -H 'Content-Type: application/json' --data @<file.json> '<url>'")
```

### DELETE

```bash
shell.exec(command="curl -sS -X DELETE '<url>'")
```

### 表单 / 文件

```bash
shell.exec(command="curl -sS -X POST -F 'field=<value>' -F 'file=@<path>' '<url>'")
```

## 认证

| 类型 | 头 / 参数 |
|---|---|
| Bearer Token | `-H 'Authorization: Bearer ${TOKEN}'` |
| Basic Auth | `-u '<user>:<pass>'` |
| API Key（header） | `-H '<header-name>: ${API_KEY}'` |
| API Key（query） | `-G --data-urlencode 'apikey=${API_KEY}'` |
| Cookie | `-b '<cookie>=<value>'` 或 `-b <cookie.txt>` |

## GraphQL

```bash
shell.exec(command="curl -sS -X POST -H 'Content-Type: application/json' -H 'Authorization: Bearer ${TOKEN}' -d '{\"query\":\"<query>\",\"variables\":<vars>}' '<endpoint>'")
```

带变量时把 `<vars>` 拼成 JSON 对象，避免在 query 字符串里插值。

## 响应分析

```bash
shell.exec(command="curl -sS '<url>' | python -m json.tool")
shell.exec(command="curl -sS '<url>' | jq '.data | length'")
shell.exec(command="curl -sS -o /dev/null -w 'code=%{http_code}\\nsize=%{size_download}\\ntime=%{time_total}\\n' '<url>'")
```

检查清单：状态码、Content-Type、body 结构（含错误时是否同样是预期 schema）、错误码字段名（`code` / `error` / `errors`）。

## 批量端点（OpenAPI）

```bash
# 1) 读 spec
file.read(path="<openapi.yaml>")

# 2) 先单端点验通，再循环
for ep in <endpoint list>; do
  curl -sS -w '\n%{http_code}\n' "<base>${ep}"
done
```

复杂校验改用 `code.execute(language="python")` 走 `requests`，能直接断言响应 schema。

## Mock 数据生成

```python
code.execute(language="python", code="""
import json, random, string
mock = [{'id': i, 'name': ''.join(random.choices(string.ascii_letters, k=8))}
        for i in range(10)]
print(json.dumps(mock, indent=2, ensure_ascii=False))
""")
```

按用户给的字段约束填；没给约束就给典型值（int 给 1-100、string 给 8 位字母、time 给 ISO 8601 当前时间）。

## 落盘报告

```
file.write(path="<workspace>/api-test-report.md", content="<report>")
```

报告含：端点、方法、状态码、响应摘要、文档对比差异。**不要把 ${TOKEN} 实际值写进文件**。

## 错误处理

| 现象 | 处理 |
|---|---|
| 连接超时 | 加 `--connect-timeout 5 --max-time 30`；确认 URL / 网络 |
| 401 | 检查 Authorization header；token 过期就重取 |
| 403 | 鉴权过但权限不足；确认账号 scope |
| 404 | 路径 / 版本号错；查 base URL |
| 429 | 频率限制；加 `Retry-After` 重试或减并发 |
| 5xx | 服务端错；先复测确认稳定，再报告 |
| CORS 报错 | curl 不受影响；只在浏览器端出现，不算 API 故障 |
| SSL 证书错 | 仅 dev / staging 用 `-k` 跳过；输出报告需注明 |
| 响应是 HTML | 实际命中登录页 / 错误页；查认证或代理 |
