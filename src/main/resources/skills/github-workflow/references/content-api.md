# GitHub Contents API 参考

不 clone 整库直接读 / 改远程仓库文件。Base64 必须**去空白后整体解码**，逐行解会破坏多字节 UTF-8。

## 读取文件

```bash
gh api "repos/<owner>/<repo>/contents/<path>?ref=<branch>"
```

返回 JSON 包含 `sha`（更新时必传）和 `content`（Base64 含换行）。

## 更新文件（Bash）

```bash
file_json=$(gh api "repos/<owner>/<repo>/contents/<path>?ref=<branch>")
sha=$(echo "$file_json" | jq -r '.sha')
old_content=$(echo "$file_json" | jq -r '.content' | tr -d '\n\r' | base64 -d)

new_content="<modified content>"
new_base64=$(echo -n "$new_content" | base64 -w 0)

gh api -X PUT "repos/<owner>/<repo>/contents/<path>" \
  -f message="<commit message>" \
  -f content="$new_base64" \
  -f branch="<branch>" \
  -f sha="$sha"
```

## 更新文件（PowerShell）

```powershell
$info = gh api "repos/<owner>/<repo>/contents/<path>?ref=<branch>" | ConvertFrom-Json
$sha = $info.sha
$oldContent = [System.Text.Encoding]::UTF8.GetString(
    [System.Convert]::FromBase64String($info.content -replace '\s',''))

$newContent = "<modified content>"
$newBase64 = [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($newContent))

gh api -X PUT "repos/<owner>/<repo>/contents/<path>" `
  -f message="<commit message>" `
  -f content="$newBase64" `
  -f branch="<branch>" `
  -f sha="$sha"
```

## 创建新文件

不传 `sha` 即可，PUT 同样路径。文件已存在会返回 422。

## 时间范围查询

```bash
gh api "repos/<owner>/<repo>/commits?sha=<branch>&since=<ISO8601>&until=<ISO8601>&per_page=100" \
  --jq '.[] | {sha: .sha, message: .commit.message}'
```

结果过多缩窗口，不要 `--paginate` 拉全量。

## 错误处理

| 现象 | 处理 |
|---|---|
| 422 sha mismatch | 文件已被改过；重新 GET 拿最新 sha 后重试 |
| 404 Not Found | 路径 / branch 拼错；或私有仓库 token 无权限 |
| 解码后乱码 | 没有 `tr -d '\n\r'` 去空白；或编码不是 UTF-8 |
| 写入丢字符 | 逐行 base64 解码导致；改成整体一次解码 |
