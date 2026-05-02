# GitHub Contents API 参考

不 clone 整库直接读/改远程文件。Base64 必须去空白后整体解码，逐行解会破坏 UTF-8。

## 读取文件

```bash
gh api "repos/<owner>/<repo>/contents/<path>?ref=<branch>"
```

返回 JSON 含 `sha`（更新时必传）和 `content`（Base64 含换行）。

## 更新文件

1. `gh api repos/<owner>/<repo>/contents/<path>?ref=<branch>` → 拿 sha + 旧 content
2. 旧 content 去空白后 `base64 -d` 解码
3. 构造新 content：`echo -n "<new>" | base64 -w0`
4. `gh api -X PUT repos/<owner>/<repo>/contents/<path> -f message="..." -f content="<b64>" -f sha="<sha>" -f branch="<branch>"`

## 约束

- 不要在循环里逐文件调 API → 合并成一个 commit 的多文件请求
- 二进制文件（png/pdf）不读 content 字段，读 `download_url`
- 大文件（>1MB）不读 content，切 git clone 方式
- 如果返回 `{"message":"This API returns blobs up to 1 MB in size"}` → 切 `git clone --depth=1 --filter=blob:limit=1m`
- 不直接 API 编辑二进制 → 提示用户本地操作
