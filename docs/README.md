# 知微文档目录说明

## 文档分层

- [ARCHITECTURE.md](ARCHITECTURE.md)：系统架构总览，适合先建立整体认知
- [FEATURES.md](FEATURES.md)：产品能力总览，适合查看“已经具备什么能力”
- [API_ENDPOINTS.md](API_ENDPOINTS.md)：REST / SSE 接口清单
- [API_STANDARD.md](API_STANDARD.md)：接口设计约定
- `architecture/`：稳定的模块架构文档，只保留对外可见、可长期维护的设计说明
- `features/`：面向使用者和集成方的特性说明
- `guides/`：使用、集成和运维指南
- `images/`：README 和文档所用截图

## 维护约定

- `docs/architecture/` 只放正式架构文档，不再混入任务拆解、重构草案、实施计划
- 本地规划文档统一放在仓库根目录的 `.plans/`，该目录默认被 Git 忽略
- 代码行为发生变化时，优先同步更新 `README.md`、`docs/ARCHITECTURE.md`、`docs/FEATURES.md` 和相关模块文档

## 推荐阅读顺序

1. [README.md](../README.md)
2. [ARCHITECTURE.md](ARCHITECTURE.md)
3. [FEATURES.md](FEATURES.md)
4. 目标模块对应的 `architecture/*.md` 与 `features/*.md`
