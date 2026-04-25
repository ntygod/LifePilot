# Gitee OpenAPI 命令速查

## 前置条件

需要配置 Gitee 私人令牌 `GITEE_TOKEN`（在 gitee.com/profile/personal_access_tokens 创建）。

## 查看仓库

```
web.fetch(url="https://gitee.com/api/v5/user/repos?access_token=${GITEE_TOKEN}&type=all&page=1&per_page=20", method="GET")
```

## 创建 Issue

```
web.fetch(
  url="https://gitee.com/api/v5/repos/${owner}/${repo}/issues",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"access_token\": \"${GITEE_TOKEN}\", \"title\": \"标题\", \"body\": \"描述\"}"
)
```

## 创建 Pull Request

```
web.fetch(
  url="https://gitee.com/api/v5/repos/${owner}/${repo}/pulls",
  method="POST",
  headers={"Content-Type": "application/json"},
  body="{\"access_token\": \"${GITEE_TOKEN}\", \"title\": \"PR标题\", \"head\": \"源分支\", \"base\": \"目标分支\", \"body\": \"描述\"}"
)
```

## 本地 Git 操作

只读查询用 `git.query`：

```
git.query(action="status")
git.query(action="log", count=10)
```

写操作用 `git.mutate`：

```
git.mutate(action="commit", message="提交信息", files=["file1.java"])
git.mutate(action="branch", branchAction="create", name="feature-xxx")
```

推送用 `shell.exec`：

```bash
shell.exec(command="git push origin feature-branch")
```

## 常见错误处理

- **Token 无效** → 提示用户检查或重新生成令牌
- **仓库不存在** → 确认 owner/repo 拼写
- **权限不足** → 确认 Token 的权限范围
- **频率超限** → API 限制 5000 次/小时，减少请求
</content>
</invoke>