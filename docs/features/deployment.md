# 部署体验

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应原文 §2.19 章节。
> **最后更新**：2026-03-01

提供多种部署方式，降低使用门槛。后端为纯 REST/SSE API 服务，前端为独立 Vue 3 SPA。

## 1. 架构简化

删除 CLI 模式、Tray 模式和 LaunchMode 枚举，后端统一为纯 Web 服务器：

- `java -jar lifepilot.jar` 启动即为完整后端服务
- 不再支持 `--mode=cli` / `--mode=tray` 等启动参数
- 主动推理通知走 IM Channel（企微/钉钉/飞书）或未来 Web Push
- 如需桌面客户端体验，远期可用 Tauri WebView 包装本地 Web UI

## 2. 部署方式

| 方式 | 适用场景 | 说明 |
|------|----------|------|
| `java -jar` | 本地桌面 / VPS | 标准 JAR 包运行，需 Java 22+ |
| Docker Compose | 服务器部署 | 一键启动后端 + 前端 Nginx，环境隔离 |
| `start.sh` / `start.bat` | 桌面用户 | 自动检测 Java 环境，友好提示，一键启动后端 |

## 3. Docker 一键启动

```bash
# 克隆项目后一键启动
docker compose up -d

# 访问
# 前端：http://localhost
# 后端 API：http://localhost/api/
```

docker-compose.yml 编排两个服务：
- **backend**：Spring Boot JAR，暴露 8080 端口，数据持久化到 Docker Volume
- **frontend**：Nginx 静态服务 + 反向代理，暴露 80 端口

## 4. 启动脚本

面向不使用 Docker 的桌面用户，提供 `start.sh`（Linux/macOS）和 `start.bat`（Windows）：

- 自动检测 Java 版本（要求 22+）
- 根据可用内存自动调整 JVM 参数（`-Xmx` 默认系统内存 50%，上限 2G）
- 首次运行创建数据目录 `~/.zhiwei/`
- 彩色输出启动状态和错误提示

## 5. 配置版本迁移

应用升级时自动迁移旧版配置到新版格式：

- 启动时检测 `lifepilot.config-version`，自动执行迁移链
- 迁移逻辑用 Java 代码实现（可测试、可调试）
- 当前为 v1（首个版本），建立迁移框架

## 6. 限制与未来扩展

- **不含 GraalVM native image**：SQLite JNI + sqlite-vec native 兼容性问题未解决，归入远期
- **不含桌面客户端**：浏览器即客户端，远期可用 Tauri 包装
- **不含 CI/CD**：GitHub Actions 等归入运维层面
- **不含进程守护**：用户可自行配置 systemd / launchd / Windows Service
