# GitHub 内容 API 详细参考

## 远程文件内容更新

GitHub Contents API 返回的 Base64 含换行符，**必须先去除换行再整体解码**，禁止逐行解码，否则多字节 UTF-8 字符会被截断产生乱码。

### Bash

```bash
# 读取文件（获取内容和 SHA）
file_json=$(gh api "repos/owner/repo/contents/path.md?ref=branch")
sha=$(echo "$file_json" | jq -r '.sha')
old_content=$(echo "$file_json" | jq -r '.content' | tr -d '\n\r' | base64 -d)

# 拼接新内容并更新
new_content="${prepend_text}${old_content}"
new_base64=$(echo -n "$new_content" | base64 -w 0)
gh api -X PUT "repos/owner/repo/contents/path.md" \
  -f message="docs: 更新说明" -f content="$new_base64" -f branch="branch" -f sha="$sha"
```

### PowerShell

```powershell
# 读取文件（ConvertFrom-Json 保留完整 content 字符串）
$info = gh api "repos/owner/repo/contents/path.md?ref=branch" | ConvertFrom-Json
$sha = $info.sha
$oldContent = [System.Text.Encoding]::UTF8.GetString(
    [System.Convert]::FromBase64String($info.content -replace '\s','')
)

# 拼接新内容并更新
$newContent = $prependText + $oldContent
$newBase64 = [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($newContent))
gh api -X PUT "repos/owner/repo/contents/path.md" `
  -f message="docs: 更新说明" -f content="$newBase64" -f branch="branch" -f sha="$sha"
```

## 时间范围查询

```bash
shell.exec(command="gh api \"repos/owner/repo/commits?sha=main&since=2026-04-01T00:00:00Z&until=2026-04-08T00:00:00Z&per_page=100\" --jq '.[] | {sha: .sha, message: .commit.message}'")
```

结果过多时缩小时间窗口，不翻页拉全量。
