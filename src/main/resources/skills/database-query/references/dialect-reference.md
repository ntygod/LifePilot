# 数据库方言命令速查

## 探索数据库结构

### SQLite

```bash
shell.exec(command="sqlite3 db.db \".tables\"")
shell.exec(command="sqlite3 db.db \".schema table_name\"")
```

### MySQL

```bash
shell.exec(command="mysql -e \"SHOW TABLES;\" database")
shell.exec(command="mysql -e \"DESCRIBE table_name;\" database")
```

### PostgreSQL

```bash
shell.exec(command="psql -c \"\\dt\" database")
shell.exec(command="psql -c \"\\d table_name\" database")
```

## 查询与预览

先用 LIMIT 限制结果集预览：

```bash
shell.exec(command="sqlite3 -header db.db \"SELECT col1, col2 FROM table_name LIMIT 10;\"")
```

用户确认后执行完整查询。

## 导出结果为 CSV

```bash
shell.exec(command="sqlite3 -header -csv db.db \"SELECT col1, col2 FROM table_name;\" > output.csv")
file.read(path="output.csv", maxChars=3000)
```

## 连接信息要点

向用户询问：

- 数据库类型（SQLite / MySQL / PostgreSQL）
- 连接地址、用户名、数据库名
- 密码通过环境变量传递，不明文输入

## 常见错误处理

- **连接失败** → 确认地址、端口、用户名，检查数据库服务是否运行
- **权限不足** → 提示用户检查数据库用户权限
- **查询超时** → 优化 SQL（添加索引提示、减少 JOIN、缩小范围）
- **编码问题** → 指定字符集 `--default-character-set=utf8mb4`
</content>
</invoke>