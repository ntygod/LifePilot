# 混合工具生态

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应原文 §2.6 / §2.16 章节。

> ⚠️ 本文档描述的是目标功能设计，尚未实现。

ZhiWei 设计了三层混合工具生态系统，将统一管理不同来源的工具，让 Agent 无需关心工具的实现方式。

## 1. 三层架构

```
┌─────────────────────────────────────────────────────┐
│              统一工具注册中心                          │
│           (DynamicToolRegistry)                      │
├────────────────┬────────────────┬───────────────────┤
│  Layer 1       │  Layer 2       │  Layer 3          │
│  MCP 外部工具   │  YAML 声明式    │  Java 原生插件    │
├────────────────┼────────────────┼───────────────────┤
│ 远程服务调用    │ 配置文件解析     │ Spring Bean 扫描  │
│ stdio / SSE    │ 模板引擎渲染     │ 编译时类型安全     │
│ 动态发现       │ 零代码开发       │ 完整 Spring 生态   │
│ 跨语言支持     │ 运行时热加载     │ 性能最优           │
└────────────────┴────────────────┴───────────────────┘
```

| 层级 | 工具类型 | 优先级 | 适用场景 |
|------|---------|--------|---------|
| Layer 3 | Java 原生插件 | 最高 | 核心能力，性能要求高 |
| Layer 2 | YAML 声明式 Skill | 中 | 用户自定义扩展，快速迭代 |
| Layer 1 | MCP 外部工具 | 基础 | 接入外部生态，跨语言支持 |

**统一体验**：无论工具来源如何，Agent 看到的是统一的工具描述格式。`AgentToolProvider` 自动聚合三层工具，对 `AgentLoop` 完全透明。

## 2. YAML 声明式工具示例

```yaml
# ~/.zhiwei/skills/weather-query.yml
skill:
  id: weather-query
  name: 天气查询
  description: 查询指定城市的天气信息

  parameters:
    - name: city
      type: string
      required: true
      description: 城市名称
    - name: days
      type: integer
      required: false
      default: 1
      description: 预报天数

  action:
    type: http
    method: GET
    url: "https://api.weather.com/v1/forecast"
    headers:
      Authorization: "Bearer ${env.WEATHER_API_KEY}"
    query:
      location: "${params.city}"
      days: "${params.days}"

  output:
    template: |
      🌤️ ${params.city} 天气：
      温度：${result.temperature}°C
      天气：${result.condition}
      建议：${result.suggestion}

  # 关联认知记忆（ZhiWei 独有）
  memory:
    read:
      - "用户偏好.出行习惯"
    write:
      - entity: "天气记录"
        attributes: ["城市", "温度", "日期"]
```

> 与竞品的关键差异：声明式工具可以读写认知记忆和知识图谱，实现"有记忆的工具"。

## 3. Browser 工具 / 网页信息提取

轻量级网页信息提取能力，让 ZhiWei 能够从互联网获取实时信息。

| 能力 | 实现方案 | 说明 |
|------|----------|------|
| 网页搜索 | 搜索引擎 API | 支持 Google / Bing / DuckDuckGo |
| 内容提取 | Jsoup | HTML 解析、正文提取、结构化数据抽取 |
| 动态页面 | HtmlUnit | JavaScript 渲染后的页面内容获取 |
| 截图 | HtmlUnit / Playwright | 页面截图保存 |

**自动知识沉淀**：提取的网页内容经过结构化处理后，自动写入知识图谱。下次询问相同主题时，优先从本地知识图谱检索，减少重复抓取。知识图谱中的网页信息带有时效标记，过期后自动触发更新。

```
你：帮我查一下明天北京的天气

ZhiWei：正在搜索天气信息...
         🌤️ 北京明天天气：
         温度：18-26°C，晴转多云
         空气质量：良（AQI 68）
         建议：适合户外活动，注意防晒

         [信息来源：中国天气网，已存入知识图谱]
```
