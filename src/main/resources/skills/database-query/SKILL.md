---
name: database-query
description: 当用户要连接外部数据库（SQLite / MySQL / PostgreSQL）编写 SQL、查询数据、导出结果或分析库表结构时使用。关键词：查数据库、SQL、数据库结构、导出数据、MySQL、PostgreSQL、SQLite、查表、select。知微内置 Datastore 用 datastore，内存 CSV/Excel 分析用 data-analyst，日志分析用 log-analyzer。
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

- 知微内置 DataStore 操作 → 用 datastore
- 内存中的数据分析（CSV/Excel） → 用 data-analyst
- 日志文件分析 → 用 log-analyzer

## 工作流

### 确认连接信息

向用户询问：
- 数据库类型（SQLite / MySQL / PostgreSQL）
- 连接地址、用户名、数据库名
- 密码通过环境变量传递，不明文输入

### 探索数据库结构

**SQLite：**
```bash
shell.exec(command="sqlite3 db.db \".tables\"")
shell.exec(command="sqlite3 db.db \".schema table_name\"")
```

**MySQL：**
```bash
shell.exec(command="mysql -e \"SHOW TABLES;\" database")
shell.exec(command="mysql -e \"DESCRIBE table_name;\" database")
```

**PostgreSQL：**
```bash
shell.exec(command="psql -c \"\\dt\" database")
shell.exec(command="psql -c \"\\d table_name\" database")
```

### 编写和执行查询

先用 LIMIT 限制结果集预览：

```bash
shell.exec(command="sqlite3 -header db.db \"SELECT col1, col2 FROM table_name LIMIT 10;\"")
```

用户确认后执行完整查询。

### 导出结果

```bash
shell.exec(command="sqlite3 -header -csv db.db \"SELECT col1, col2 FROM table_name;\" > output.csv")
file.read(path="output.csv", maxChars=3000)
```

## 规则

- 默认只执行 SELECT 查询（只读）
- INSERT / UPDATE / DELETE / DROP 等写操作必须向用户确认后再执行
- 不在命令行中明文传递密码，使用环境变量或配置文件
- 大表查询默认添加 LIMIT，避免内存溢出
- 每次查询先预览少量结果，确认正确后再执行全量

## 常见错误处理

- **连接失败** → 确认地址、端口、用户名，检查数据库服务是否运行
- **权限不足** → 提示用户检查数据库用户权限
- **查询超时** → 优化 SQL（添加索引提示、减少 JOIN、缩小范围）
- **编码问题** → 指定字符集 `--default-character-set=utf8mb4`
