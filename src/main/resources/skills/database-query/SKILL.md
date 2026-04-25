---
name: database-query
description: 当用户要连接外部数据库（SQLite / MySQL / PostgreSQL）编写 SQL、查询数据、导出结果或分析库表结构时使用。关键词：查数据库、SQL、数据库结构、导出数据、MySQL、PostgreSQL、SQLite、查表、select。内存 CSV/Excel 分析用 data-analyst，日志分析用 log-analyzer。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
    priority: normal
    tags:
      - database
      - sql
      - mysql
      - postgresql
      - sqlite
      - data-export
    suggested_tools:
      - shell.exec
      - file.write
      - file.read
---

# 数据库查询指南

帮助用户连接外部数据库、编写 SQL、执行查询和导出数据。

## 适用场景

- 查询数据库中的数据
- 编写和优化 SQL 语句
- 导出查询结果为 CSV / JSON
- 分析数据库结构（表、列、索引）

## 不适用场景

- 内存中的数据分析（CSV/Excel） → 用 data-analyst
- 日志文件分析 → 用 log-analyzer

## 工作流

1. **确认连接信息**：类型（SQLite / MySQL / PostgreSQL）、地址、库名；密码走环境变量，不明文
2. **探索结构**：先 list 表，再查目标表 schema（方言命令见参考）
3. **预览查询**：先加 `LIMIT 10` 看样本，确认正确后再跑全量
4. **写操作保护**：默认只允许 SELECT；INSERT / UPDATE / DELETE / DROP 必须用户确认后再执行
5. **导出**：结构化数据输出到 CSV / JSON，大表先限定时间范围或字段

## 详细参考

- SQLite / MySQL / PostgreSQL 方言命令 + 导出命令 + 常见错误：`{skill_dir}/references/dialect-reference.md`
</content>
</invoke>