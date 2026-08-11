---
name: dev-fix
description: "从问题分析到 API 验证的完整修复循环。用法：/dev-fix <问题描述>。自动执行：分析→方案→修复→编译→API 冒烟测试→日志检查→修复循环"
user_invocable: true
---

# 开发修复循环

你将执行一套完整的 **分析→修复→验证** 闭环流程。每轮修复后通过实际 API 调用验证，直到通过或触发终止条件。

## 输入

用户提供问题描述（如 `/dev-fix 记忆搜索返回空结果`）。如果没有提供，询问。

## 前置条件

后端服务必须运行在 `localhost:8080`。如果 curl 健康检查失败，提醒用户先启动服务：
```bash
curl -sf http://localhost:8080/api/model-services/enabled > /dev/null 2>&1
```

## 流程

### Phase 1: 问题分析

1. 理解问题描述，定位相关代码
2. 阅读关键文件，理解当前实现
3. 确定根因或可能的根因

### Phase 2: 方案制定

1. 如果改动涉及 3+ 文件或架构决策 → 进入 Plan Mode
2. 否则直接设计修复方案
3. 向用户简述方案要点，确认后继续

### Phase 3: 修复-验证循环

进入循环，每轮包含：

#### Step 3.1: 实现修复

编辑代码，遵循项目规范（Java 22、中文注释等）。

#### Step 3.2: 编译验证

```bash
mvn compile -q 2>&1 | tail -20
```

**编译失败** → 立即修复编译错误，重新编译。连续 2 次编译失败 → 终止，报告。

#### Step 3.3: 重启服务

```bash
# 停掉旧进程（Windows 兼容：用 netstat 找 PID + taskkill）
pid=$(netstat -ano 2>/dev/null | grep ":8080.*LISTENING" | head -1 | awk '{print $NF}')
if [ -n "$pid" ]; then taskkill //F //PID $pid 2>/dev/null; sleep 3; fi

# 后台启动
cd D:/WorkSpace/Project/News && mvn spring-boot:run 2>&1 &

# 轮询等待服务就绪（最多 90 秒）
for i in $(seq 1 45); do
  curl -sf http://localhost:8080/api/model-services/enabled -o /dev/null && break
  sleep 2
done
```

如果 90 秒内服务未就绪 → 终止，报告启动失败。

#### Step 3.4: API 冒烟测试

根据本轮修改范围，**动态选择**测试场景（不是固定脚本）：

**通用（每次都跑）：**
```bash
# 服务存活
curl -sf http://localhost:8080/api/model-services/enabled | head -c 200
```

**记忆相关改动：**
```bash
# 记忆统计
curl -sf http://localhost:8080/api/memories/stats

# 记忆搜索（用与问题相关的关键词）
curl -sf "http://localhost:8080/api/memories/search?query=测试&topK=3"

# 实体列表
curl -sf "http://localhost:8080/api/memories/entities?page=1&pageSize=5"
```

**对话相关改动：**
```bash
# 发送测试消息（同步模式，用已有会话或新建）
curl -sf -X POST http://localhost:8080/api/chat/messages \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"dev-test-session","content":"你好，这是冒烟测试"}'
```

**模型服务/Scene 相关改动：**
```bash
# 模型服务列表
curl -sf http://localhost:8080/api/model-services | python3 -c "import sys,json; data=json.load(sys.stdin); [print(f'{s[\"id\"]}: scenes={s.get(\"supportedScenes\",[])}') for s in data.get('data',data) if isinstance(data,dict)]" 2>/dev/null || curl -sf http://localhost:8080/api/model-services | head -c 500
```

**验证标准：**
- HTTP 200 响应
- 响应体非空，无 error 字段
- 对于搜索类接口，结果数量 > 0（如果预期有数据）

#### Step 3.5: 日志检查

```bash
# 检查最近 30 秒的 ERROR 日志（从服务启动后）
# 如果有日志文件：
tail -100 ~/.zhiwei/logs/zhiwei.log 2>/dev/null | grep -E "ERROR|SQLITE_BUSY|LlmUnavailableException" | tail -10

# 如果没有日志文件，提醒用户检查控制台输出中是否有 ERROR
```

#### Step 3.6: 结果判定

- **全部通过** → 退出循环，进入 Phase 4
- **有失败** → 记录失败原因，分析根因，进入下一轮修复

### Phase 4: 完成报告

输出结构化报告：
```
## 修复报告

### 问题
<原始问题描述>

### 根因
<根因分析>

### 修复内容
<改了哪些文件，做了什么>

### 验证结果
- 编译: ✅
- API 测试: ✅ / ⚠️ <详情>
- 日志检查: ✅ / ⚠️ <详情>

### 修复轮次
<经过了几轮修复>
```

## 终止条件

| 条件 | 触发 | 动作 |
|------|------|------|
| 成功 | API 测试通过 + 日志无 ERROR | 输出报告，正常退出 |
| 同一错误重复 | 同一错误连续 2 轮未解决 | 停止，报告"该问题可能需要更深入的排查或架构调整" |
| 修复上限 | 累计 5 轮修复 | 停止，报告残留问题和已完成的修复 |
| 编译卡住 | 连续 2 次编译失败 | 停止，报告编译错误 |
| 服务不可用 | 健康检查失败 | 停止，提醒用户检查服务状态 |

## 注意事项

- 每轮修复专注解决 **一个问题**，不要一次改太多
- 修复后的代码必须遵循 `.Codex/rules/` 中的编码规范
- API 测试选择要与改动范围匹配，不要跑无关的测试
- 日志检查区分**新产生的错误**和**历史错误**，只关注新错误
- 如果问题涉及 LLM 调用超时等外部依赖问题，记录为"外部依赖问题"而非代码缺陷
