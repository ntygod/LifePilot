# 知微（ZhiWei）内置工具使用指导书

本文档详细介绍知微项目中的三类核心工具：浏览器工具、文件工具和 MCP 工具。

## 一、浏览器工具（13 个）

浏览器工具基于 Playwright 实现，支持自动化网页操作。所有工具标记为 `infrastructure` 标签。

### 1.1 基础浏览器工具（4 个）

| 工具 ID | 名称 | 描述 | 风险等级 |
|--------|------|------|----------|
| `builtin.browser.navigate` | 浏览器导航 | 导航到指定 URL，返回页面标题和文本快照。适用于 JavaScript 渲染的动态网页 | MEDIUM |
| `builtin.browser.click` | 浏览器点击 | 点击页面中指定 CSS 选择器的元素，等待导航或响应完成 | MEDIUM |
| `builtin.browser.input` | 浏览器输入 | 在页面表单字段中填入文本内容，使用 CSS 选择器定位输入框 | MEDIUM |
| `builtin.browser.screenshot` | 浏览器截图 | 截取当前页面截图，返回 Base64 编码的 PNG 图片。支持全页截图 | LOW |

### 1.2 扩展浏览器工具（9 个）

| 工具 ID | 名称 | 描述 | 风险等级 |
|--------|------|------|----------|
| `builtin.browser.scroll` | 浏览器滚动 | 滚动页面或滚动到指定元素。支持方向滚动（up/down）和元素定位滚动 | MEDIUM |
| `builtin.browser.wait` | 浏览器等待 | 等待页面中指定元素达到目标状态（visible/hidden/attached） | LOW |
| `builtin.browser.hover` | 浏览器悬停 | 将鼠标悬停到指定 CSS 选择器的元素上，返回元素信息 | MEDIUM |
| `builtin.browser.select` | 浏览器下拉选择 | 从 select 下拉元素中选择选项，支持按 value 或 label 选择 | MEDIUM |
| `builtin.browser.keyboard` | 浏览器键盘 | 模拟键盘操作，支持单键/组合键按下和逐字符文本输入 | MEDIUM |
| `builtin.browser.evaluate` | 浏览器 JS 执行 | 在当前页面上下文中执行 JavaScript 表达式，返回 JSON 序列化结果 | HIGH |
| `builtin.browser.accessibility` | 浏览器无障碍树 | 获取页面或子树的无障碍树结构快照，用于理解页面语义结构 | LOW |
| `builtin.browser.tab` | 浏览器标签页 | 管理浏览器标签页，支持打开新标签页、切换、关闭和列出所有标签页 | MEDIUM |
| `builtin.browser.storage` | 浏览器存储 | 管理浏览器存储，支持 Cookie 和 localStorage 的读取、设置和清除 | MEDIUM |

### 1.3 通用参数

所有浏览器工具都支持以下可选参数：

```json
{
  "sessionId": "浏览器会话 ID，默认 'default'，同一会话复用 Page"
}
```

### 1.4 使用示例

**场景：自动登录网站**

```json
// 1. 导航到登录页
{
  "tool": "builtin.browser.navigate",
  "input": {
    "url": "https://example.com/login"
  }
}

// 2. 等待页面加载完成
{
  "tool": "builtin.browser.wait",
  "input": {
    "selector": "#username",
    "state": "visible"
  }
}

// 3. 输入用户名
{
  "tool": "builtin.browser.input",
  "input": {
    "selector": "#username",
    "value": "myuser"
  }
}

// 4. 输入密码
{
  "tool": "builtin.browser.input",
  "input": {
    "selector": "#password",
    "value": "mypass123"
  }
}

// 5. 点击登录按钮
{
  "tool": "builtin.browser.click",
  "input": {
    "selector": "#login-btn"
  }
}

// 6. 等待登录完成，跳转到 dashboard
{
  "tool": "builtin.browser.wait",
  "input": {
    "selector": "#dashboard",
    "state": "visible",
    "timeout": 30
  }
}
```

**场景：网页内容抓取**

```json
// 1. 导航到目标页面
{
  "tool": "builtin.browser.navigate",
  "input": {
    "url": "https://news.example.com"
  }
}

// 2. 滚动加载更多内容
{
  "tool": "builtin.browser.scroll",
  "input": {
    "direction": "down",
    "pixels": 500
  }
}

// 3. 截图保存
{
  "tool": "builtin.browser.screenshot",
  "input": {
    "fullPage": true
  }
}

// 4. 获取无障碍树了解页面结构
{
  "tool": "builtin.browser.accessibility",
  "input": {
    "maxDepth": 3
  }
}
```

---

## 二、文件工具（10 个）

文件工具提供本地文件系统操作能力。所有工具标记为 `infrastructure` 标签。

### 2.1 工具列表

| 工具 ID | 名称 | 描述 | 风险等级 |
|--------|------|------|----------|
| `builtin.file.read` | 读取文件 | 读取指定路径的文件内容，支持行范围读取、maxChars 截断和编码指定 | LOW |
| `builtin.file.write` | 写入文件 | 原子写入文件内容（先写临时文件再重命名），支持自动创建父目录 | MEDIUM |
| `builtin.file.list` | 列出目录 | 列出指定目录的文件和子目录，支持深度限制、glob 过滤、maxEntries 截断 | LOW |
| `builtin.file.search` | 搜索文件���容 | 递归搜索目录下文件内容，支持正则表达式、glob 过滤、上下文行 | LOW |
| `builtin.file.append` | 追加文件 | 向文件末尾追加内容，文件不存在时自动创建 | MEDIUM |
| `builtin.file.patch` | 补丁文件 | 对文件执行行级 insert/replace/delete 操作，原子写入 | MEDIUM |
| `builtin.file.delete` | 删除文件 | 删除文件或目录，支持递归删除非空目录 | HIGH |
| `builtin.file.copy` | 复制文件 | 复制文件到目标路径，支持覆盖控制 | MEDIUM |
| `builtin.file.move` | 移动文件 | 原子移动文件到目标路径，支持覆盖控制 | HIGH |
| `builtin.file.info` | 文件信息 | 查询文件或目录的元数据，包括大小、修改时间、权限和 MIME 类型 | LOW |

### 2.2 参数说明

**读取文件**
```json
{
  "path": "文件路径（必填）",
  "encoding": "文件编码，默认 UTF-8",
  "startLine": "起始行号（1-based），可选",
  "endLine": "结束行号（1-based），可选",
  "maxChars": "最大返回字符数，默认 30000"
}
```

**写入文件**
```json
{
  "path": "目标文件路径（必填）",
  "content": "要写入的文件内容（必填）",
  "createDirectories": "父目录不存在时是否自动创建，默认 true"
}
```

**列出目录**
```json
{
  "path": "目录路径（必填）",
  "maxDepth": "最大遍历深度，默认 3",
  "pattern": "glob 过滤模式（如 *.java），可选",
  "maxEntries": "最大返回条目数，默认 200"
}
```

**搜索文件内容**
```json
{
  "path": "搜索起始目录路径（必填）",
  "pattern": "搜索内容的正则表达式（必填）",
  "filePattern": "文件名 glob 过滤模式（如 *.java），可选",
  "maxResults": "最大返回结果数，默认 50",
  "offset": "分页偏移量，默认 0",
  "limit": "分页每页数量，默认等于 maxResults",
  "contextLines": "匹配行前后上下文行数，默认 0"
}
```

**补丁文件**
```json
{
  "path": "目标文件路径（必填）",
  "operations": [
    {
      "type": "操作类型: insert / replace / delete（必填）",
      "line": "目标行号（1-based，必填）",
      "endLine": "结束行号（replace/delete 时可选）",
      "content": "插入或替换的内容（insert/replace 时必需）"
    }
  ]
}
```

### 2.3 使用示例

**场景：读取并修改配置文件**

```json
// 1. 读取配置文件
{
  "tool": "builtin.file.read",
  "input": {
    "path": "/app/config/application.yml",
    "startLine": 1,
    "endLine": 50
  }
}

// 2. 使用 patch 修改配置（添加新配置项）
{
  "tool": "builtin.file.patch",
  "input": {
    "path": "/app/config/application.yml",
    "operations": [
      {
        "type": "insert",
        "line": 51,
        "content": "new-setting: value"
      }
    ]
  }
}
```

**场景：搜索代码中的特定内容**

```json
{
  "tool": "builtin.file.search",
  "input": {
    "path": "/project/src",
    "pattern": "TODO|FIXME",
    "filePattern": "*.java",
    "contextLines": 2,
    "maxResults": 20
  }
}
```

**场景：批量文件操作**

```json
// 1. 列出目录结构
{
  "tool": "builtin.file.list",
  "input": {
    "path": "/project",
    "maxDepth": 2,
    "pattern": "*.java"
  }
}

// 2. 复制文件
{
  "tool": "builtin.file.copy",
  "input": {
    "source": "/project/src/Main.java",
    "destination": "/project/backup/Main.java",
    "overwrite": true
  }
}

// 3. 移动文件
{
  "tool": "builtin.file.move",
  "input": {
    "source": "/project/temp/data.txt",
    "destination": "/project/data/data.txt",
    "overwrite": false
  }
}
```

---

## 三、MCP 工具

MCP（Model Context Protocol）工具是知微通过 MCP 协议集成的外部能力。

### 3.1 MCP 架构

```
┌─────────────────────────────────────────────────────────────┐
│                         知微 (ZhiWei)                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    │
│  │  Skill 工具  │    │  内置工具    │    │  MCP 工具    │    │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘    │
│         │                   │                   │            │
│         └───────────────────┼───────────────────┘            │
│                             │                                │
│                    ┌────────▼────────┐                       │
│                    │ DynamicToolRegistry │                   │
│                    └────────┬────────┘                       │
│                             │                                │
│                    ┌────────▼────────┐                       │
│                    │ ToolExecutionPipeline │                │
│                    └─────────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
                              ▲
                              │
                    ┌─────────┴─────────┐
                    │   MCP Server      │
                    │ (外部服务)        │
                    └───────────────────┘
```

### 3.2 MCP 工具注册

知微通过以下方式发现和注册 MCP 服务器：

1. **内置配置**：`classpath:builtin-mcp/servers.json`
2. **用户配置**：`~/.zhiwei/mcp/servers.json`
3. **全局配置**：`~/.mcp/servers.json`
4. **项目配置**：`{project-root}/.mcp.json`
5. **自定义路径**：通过 `lifepilot.mcp.discovery.paths` 配置

### 3.3 MCP 工具格式

MCP 工具在知微中的 ID 格式为：

```
mcp.{serverName}.{toolName}
```

例如：`mcp.filesystem.read_file`

### 3.4 MCP 工具管理 API

知微提供 Web API 管理 MCP 服务器：

```bash
# 获取 MCP 服务器状态
GET /api/mcp/status

# 获取所有 MCP 服务器列表
GET /api/mcp/servers

# 获取指定服务器详情
GET /api/mcp/servers/{name}

# 连接 MCP 服务器
POST /api/mcp/servers/{name}/connect

# 断开 MCP 服务器
POST /api/mcp/servers/{name}/disconnect

# 获取 MCP 服务器工具列表
GET /api/mcp/servers/{name}/tools

# 添加新 MCP 服务器
POST /api/mcp/servers

# 更新 MCP 服务器配置
PUT /api/mcp/servers/{name}

# 删除 MCP 服务器
DELETE /api/mcp/servers/{name}
```

### 3.5 常用 MCP 服务器

以下是常见的 MCP 服务器及其工具：

| 服务器 | 工具示例 | 用途 |
|--------|----------|------|
| `filesystem` | `read_file`, `write_file`, `list_directory` | 本地文件系统操作 |
| `github` | `get_pull_request`, `create_issue`, `search_code` | GitHub API 操作 |
| `brave-search` | `web_search` | 网页搜索 |
| `slack` | `send_message`, `list_channels` | Slack 消息操作 |

### 3.6 MCP 工具使用示例

```json
// 调用本地文件系统 MCP 工具
{
  "tool": "mcp.filesystem.read_file",
  "input": {
    "path": "/tmp/test.txt"
  }
}

// 调用 GitHub MCP 工具
{
  "tool": "mcp.github.get_pull_request",
  "input": {
    "owner": "example",
    "repo": "project",
    "pull_number": 123
  }
}
```

---

## 四、风险等级与安全

知微使用四级风险评估体系：

| 等级 | 描述 | 需要确认 |
|------|------|----------|
| **LOW** | 安全操作，默认执行 | 否 |
| **MEDIUM** | 中等风险，需要注意 | 否 |
| **HIGH** | 高风险，每次执行需用户确认 | 是 |
| **CRITICAL** | 极高风险，确认+二次验证 | 是 |

---

## 五、工具选择指南

### 场景快速查找

| 场景 | 推荐工具 |
|------|----------|
| 自动化网页操作（登录、填表、点击） | `builtin.browser.*` |
| 抓取网页内容（文字、截图） | `builtin.browser.navigate` + `builtin.browser.screenshot` |
| 读取本地文件 | `builtin.file.read` |
| 写入/修改本地文件 | `builtin.file.write` / `builtin.file.patch` |
| 搜索代码中的内容 | `builtin.file.search` |
| 列出目录结构 | `builtin.file.list` |
| 调用外部 MCP 服务 | `mcp.{server}.{tool}` |
| 操作浏览器 Tab | `builtin.browser.tab` |
| 执行 JavaScript | `builtin.browser.evaluate`（HIGH 风险） |

---

## 六、调试技巧

### 6.1 查看可用工具

通过自省 Skill 查询所有可用工具：

```json
{
  "tool": "builtin.skill.find-skills",
  "input": {
    "type": "tool"
  }
}
```

### 6.2 浏览器调试

```json
// 1. 获取无障碍树了解页面结构
{
  "tool": "builtin.browser.accessibility",
  "input": {
    "maxDepth": 3
  }
}

// 2. 执行自定义 JS 调试
{
  "tool": "builtin.browser.evaluate",
  "input": {
    "expression": "document.title"
  }
}

// 3. 查看当前页面 Cookie
{
  "tool": "builtin.browser.storage",
  "input": {
    "target": "cookie",
    "action": "get"
  }
}
```

### 6.3 文件操作调试

```json
// 1. 先查看文件信息
{
  "tool": "builtin.file.info",
  "input": {
    "path": "/path/to/file"
  }
}

// 2. 列出目录看结构
{
  "tool": "builtin.file.list",
  "input": {
    "path": "/path",
    "maxDepth": 2
  }
}
```

---

## 七、配置参考

### 7.1 浏览器配置

```yaml
lifepilot:
  meta:
    infra:
      browser:
        text-snapshot-max-length: 10000
        screenshot-quality: 80
        default-timeout-seconds: 30
        scroll-pixels: 500
        wait-timeout-seconds: 10
        accessibility-max-depth: 5
```

### 7.2 文件工具配置

```yaml
lifepilot:
  meta:
    infra:
      file:
        allowed-paths:
          - /home/user/projects
          - /tmp
        max-file-size-mb: 10
        read-max-chars: 30000
        search-max-results: 50
```

### 7.3 MCP 配置

```yaml
lifepilot:
  mcp:
    enabled: true
    discovery:
      paths:
        - ~/.mcp/servers.json
        - ~/.zhiwei/mcp/servers.json
    servers:
      filesystem:
        transport: stdio
        command: npx
        args:
          - -y
          - "@modelcontextprotocol/server-filesystem"
        env:
          ALLOWED_DIRECTORIES: "/home/user"
```

---

> 本文档最后更新：2026-03-17