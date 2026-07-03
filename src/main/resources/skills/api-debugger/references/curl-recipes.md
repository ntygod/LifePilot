# API 调试参考

> curl/httpie 命令直接用 shell.exec。本文档仅含 API 调试决策知识。

## 认证模式

| 类型 | curl 写法 |
|------|----------|
| Bearer Token | `-H 'Authorization: Bearer ${TOKEN}'` |
| Basic Auth | `-u '<user>:<pass>'` |
| API Key（header） | `-H '<header-name>: ${API_KEY}'` |
| API Key（query） | `-G --data-urlencode 'apikey=${API_KEY}'` |

敏感凭证一律走环境变量 `${TOKEN}` / `${API_KEY}`，不写明文。

## 响应检查清单

状态码 → Content-Type → body 结构（含错误时是否同样是预期 schema）→ 错误码字段名（`code` / `error` / `errors`）。

## 错误分类

| 现象 | 处理 |
|------|------|
| 连接超时 | 加 `--connect-timeout 5 --max-time 30` |
| 401 | token 过期重取 |
| 403 | 鉴权过但权限不足，确认 scope |
| 429 | 等 `Retry-After` 重试，减并发 |
| 5xx | 复测确认稳定，报告 |
| 响应是 HTML | 实际命中登录页/错误页，查认证 |
| SSL 证书错 | 仅 dev/staging 用 `-k` 跳过，报告需注明 |

## Mock 数据生成

按用户给的字段约束填；没给约束就给典型值：int→1-100、string→8 位字母、time→ISO 8601 当前时间。

## 报告结构

端点 / 方法 / 状态码 / 响应摘要 / 文档对比差异。**不写 ${TOKEN} 实际值进文件。**
