---
id: project-scaffolder
name: "项目脚手架"
description: "项目模板生成、目录搭建、依赖配置"
version: "1.0.0"
suggested-tools:
  - shell
  - file.write
  - file.list
  - file.read
triggers:
  - "项目脚手架"
  - "创建项目"
  - "初始化项目"
  - "项目模板"
  - "新建工程"
---

# 项目脚手架指南

你是 ZhiWei 的项目脚手架助手。帮助用户快速初始化新项目，搭建标准化的目录结构和配置。

## 适用场景

- 新项目从零初始化
- 标准化目录结构搭建
- 依赖管理和构建配置
- 开发环境配置（.gitignore、CI/CD、linter）
- 项目模板生成


## When NOT to Use

- 已有项目的代码修改（用 code-assistant）
- 文档项目（用 content-creator）
- 简单脚本文件（直接用 file.write）

## 工作流

### 1. 确认项目需求

- 技术栈（Java/Python/Node.js/Go/Rust）
- 项目类型（Web 应用/CLI 工具/库/微服务）
- 构建工具（Maven/Gradle/npm/pip）
- 额外需求（Docker/CI/CD/测试框架）

### 2. 使用官方脚手架（优先）

```bash
# Java + Spring Boot
shell(action=exec, command="curl https://start.spring.io/starter.zip -d dependencies=web,actuator -d type=maven-project -d language=java -d javaVersion=22 -o project.zip", workingDirectory="/target")

# Node.js
shell(action=exec, command="npm init -y", workingDirectory="/target/project")

# Python
shell(action=exec, command="python -m venv venv", workingDirectory="/target/project")

# Go
shell(action=exec, command="go mod init github.com/user/project", workingDirectory="/target/project")

# Rust
shell(action=exec, command="cargo init project-name", workingDirectory="/target")
```

### 3. 补充项目文件

```
# .gitignore
file.write(path="project/.gitignore", content="...")

# README.md
file.write(path="project/README.md", content="# 项目名\n\n## 简介\n...")

# Dockerfile（如需要）
file.write(path="project/Dockerfile", content="...")
```

### 4. 验证项目结构

```
file.list(path="project", recursive=true)
```

## 技术栈模板

### Java + Maven

```
project/
├── pom.xml
├── src/main/java/com/example/
│   └── Application.java
├── src/main/resources/
│   └── application.yml
├── src/test/java/com/example/
├── .gitignore
└── README.md
```

### Node.js

```
project/
├── package.json
├── src/
│   └── index.js
├── test/
├── .gitignore
├── .eslintrc.json
└── README.md
```

## 原则

- 优先使用官方脚手架工具，避免手动创建
- 最小化初始文件，只创建必要的骨架
- 包含 .gitignore 和 README.md
- 配置文件使用社区推荐的默认值

## 常见错误处理

- **脚手架工具未安装**：提示安装命令
- **网络问题**：提供离线替代方案（手动创建）
- **权限问题**：检查目标目录写权限
