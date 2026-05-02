---
name: database-query
description: 当用户要连接外部数据库（SQLite / MySQL / PostgreSQL）编写 SQL、查询数据、导出结果或分析库表结构时使用。
version: 2.1.0
metadata:
  zhiwei:
    tags:
      - database
      - sql
      - mysql
      - postgresql
      - sqlite
      - data-export
    suggested_tools:
      - shell_exec
      - file_write
      - file_read
---

# 数据库查询指南

通过 `shell_exec` 调 sqlite3 / mysql / psql CLI 连接外部数据库执行 SQL。**核心约束：默认只允许 SELECT；写操作必须用户确认。**

## 适用场景

- 查询数据（SELECT）
- 写 / 优化 SQL 语句
- 导出查询结果（CSV / JSON）
- 分析库表结构（表 / 列 / 索引）
- 跨表关联（JOIN）/ 聚合（GROUP BY）

## 不适用场景

- 内存 CSV / Excel 分析 → data-analyst
- 日志文件分析 → log-analyzer
- 知微自身数据库（dataDir 下的 zhiwei.db）→ 用户没指明就不要碰

## 工作流（按场景分流）

| 场景 | 路径 |
|---|---|
| 第一次接触某库 | 先列表 → 再看 schema → 再写 SELECT |
| 已知表名查数据 | 直接写 SELECT，先 LIMIT 10 验证 → 全量 |
| 数据导出 | SELECT → CSV / JSON 落盘 |
| 写操作（INSERT/UPDATE/DELETE/DDL） | 先用 SELECT 看影响范围 → 用户确认 → 执行 |
| SQL 优化 | EXPLAIN 看执行计划 → 改写 |

各路径要点：

- **连接信息确认**：类型（SQLite / MySQL / PostgreSQL）+ 地址 + 库名 + 用户；密码走环境变量 `$DB_PASS`，不明文
- **列表 / schema 探索**：方言不同（SQLite `.tables` / MySQL `SHOW TABLES` / PostgreSQL `\dt`），见参考
- **预览先 LIMIT 10**：未知数据量 / 未验证条件先小样验证，确认正确再去 LIMIT 跑全量
- **写操作保护**：INSERT / UPDATE / DELETE / DROP / TRUNCATE 必须用户明确确认；DDL（ALTER / CREATE / DROP TABLE）尤其要二次确认
- **大表导出限范围**：> 10 万行先按时间 / ID 范围切，不要一次拉全表

## 详细参考

- SQLite / MySQL / PostgreSQL 方言命令、导出语法、常见错误：`{skill_dir}/references/dialect-reference.md`
