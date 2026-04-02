# 部署体验 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：跨模块（部署基础设施）
> **最后更新**：2026-04

提供多种部署方式，降低使用门槛。后端为纯 REST/SSE API 服务，前端为独立 Vue 3 SPA。

## 1. 架构简化

删除 CLI 模式、Tray 模式和 LaunchMode 枚举，后端统一为纯 Web 服务器：

- `java -jar zhiwei.jar` 启动即为完整后端服务
- 不再支持 `--mode=cli` / `--mode=tray` 等启动参数
- 自主任务通知走 IM Channel（企微/钉钉/飞书）或未来 Web Push
- 桌面客户端通过 Tauri 2.x 实现（详见下文第 2 节）

## 2. 部署方式

| 方式 | 适用场景 | 说明 |
|------|----------|------|
| `java -jar` | 本地桌面 / VPS | 标准 JAR 包运行，需 Java 22+ |
| Docker Compose | 服务器部署 | 一键启动后端 + 前端 Nginx，环境隔离 |
| `start.sh` / `start.bat` | 桌面用户 | 自动检测 Java 环境，友好提示，一键启动后端 |
| Tauri 桌面安装包 | 桌面用户 | NSIS（Windows）/ DMG（macOS）/ DEB+AppImage（Linux），内嵌后端管理 |

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

## 6. Tauri 桌面客户端

Tauri 2.x 桌面应用位于 `zhiwei-web/src-tauri/`，通过 WebView 加载前端 SPA，Rust 层管理 Java 后端进程生命周期：

- **启动流程**：SplashView 等待后端就绪 → 检查是否已配置模型服务 → 有则进入对话页，无则进入 SetupWizard 引导配置
- **后端管理**：JavaManager 负责查找 Java 运行时、动态端口分配、启动/停止后端进程
- **系统托盘**：关闭窗口时隐藏到托盘而非退出，托盘菜单支持显示/退出操作
- **IPC 命令**：前端可通过 Tauri IPC 查询后端状态、重启后端

## 7. 限制与未来扩展

- **不含 GraalVM native image**：SQLite JNI + sqlite-vec native 兼容性问题未解决，归入远期
- **不含 CI/CD**：GitHub Actions 等归入运维层面
- **不含进程守护**：用户可自行配置 systemd / launchd / Windows Service
