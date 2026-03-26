# 贡献指南

感谢你愿意改进知微。

## 提交前先了解

- 默认从 `develop` 分支开展工作
- 建议分支命名：
  - `feature/{name}`
  - `bugfix/{name}`
- 提交消息格式：
  - `<type>(<scope>): 中文描述`
  - 例如：`feat(permission): 支持任务级高风险预授权`

## 本地开发

### 后端

```bash
mvn compile
mvn test
```

### 前端

```bash
cd zhiwei-web
npm install
npm run test:run
npm run build
```

## 代码与文档约定

- Java 使用 4 空格缩进，Vue / TypeScript 使用 2 空格缩进
- 注释、日志、测试名、提交说明统一使用中文
- 标识符、配置键、REST 路径、Skill ID 保持英文
- 功能行为变化时，请同步更新：
  - `README.md`
  - `docs/ARCHITECTURE.md`
  - `docs/FEATURES.md`
  - 对应模块文档

## Pull Request 要求

请在 PR 描述中至少写清楚：

- 改了什么
- 为什么要改
- 如何验证
- 是否涉及数据库迁移、配置变更、前端截图

如果是 UI 改动，请附截图；如果是迁移或配置变更，请明确标注影响范围。

## 不建议提交的内容

- `.env`
- API Key、访问令牌、数据库文件
- 个人临时笔记、任务拆解、规划草案

本仓库约定把本地规划文档放到 `.plans/`，该目录默认不进入 Git。
