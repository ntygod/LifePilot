# 部署体验

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应原文 §2.19 章节。

> ⚠️ 本文档描述的是目标功能设计，尚未实现。

提供多种部署方式，降低使用门槛。

## 1. 部署方式

| 方式 | 适用场景 | 启动时间 | 说明 |
|------|----------|----------|------|
| `java -jar` | 通用 | ~5s | 标准 JAR 包运行 |
| Docker | 服务器部署 | ~8s | 一键启动，环境隔离 |
| GraalVM Native | 追求极致启动速度 | < 500ms | 原生镜像，内存占用低 |
| `start.bat` / `start.sh` | 桌面用户 | ~5s | 自动检测环境，友好提示 |

## 2. Docker 一键启动

```yaml
# docker-compose.yml
version: '3.8'
services:
  lifepilot:
    image: lifepilot/lifepilot:latest
    ports:
      - "8080:8080"
    volumes:
      - ~/.lifepilot:/root/.lifepilot
    environment:
      - DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
    restart: unless-stopped
```

```bash
# 一键启动
docker-compose up -d
```

## 3. 启动脚本增强

- 自动检测 Java 版本（要求 22+）
- 自动检测可用内存并调整 JVM 参数
- 首次运行引导配置向导
- 彩色输出启动状态和错误提示
