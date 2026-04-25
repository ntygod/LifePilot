# 数据库方言速查

`shell.exec` 调对应 CLI（sqlite3 / mysql / psql）。密码走环境变量，不要明文。

## 列表 / 查 schema

| 操作 | SQLite | MySQL | PostgreSQL |
|---|---|---|---|
| 列所有表 | `.tables` | `SHOW TABLES;` | `\dt` |
| 看表结构 | `.schema <table>` | `DESCRIBE <table>;` 或 `SHOW CREATE TABLE <table>;` | `\d <table>` |
| 看索引 | `.indexes <table>` | `SHOW INDEX FROM <table>;` | `\di` |
| 看库列表 | `.databases` | `SHOW DATABASES;` | `\l` |
| 当前库 | — | `SELECT DATABASE();` | `SELECT current_database();` |

CLI 命令：

```bash
shell.exec(command="sqlite3 <db.sqlite> '.tables'")
shell.exec(command="sqlite3 <db.sqlite> '.schema <table>'")

shell.exec(command="mysql -h <host> -u <user> -p\"$DB_PASS\" -e 'SHOW TABLES;' <database>")
shell.exec(command="mysql -h <host> -u <user> -p\"$DB_PASS\" -e 'DESCRIBE <table>;' <database>")

shell.exec(command="psql -h <host> -U <user> -d <database> -c '\\dt'")
shell.exec(command="psql -h <host> -U <user> -d <database> -c '\\d <table>'")
```

PostgreSQL 密码用 `PGPASSWORD=$DB_PASS psql ...` 注入。

## 查询 / 预览

未知数据量先 LIMIT 10 验证：

```bash
shell.exec(command="sqlite3 -header <db.sqlite> 'SELECT <cols> FROM <table> WHERE <cond> LIMIT 10;'")
shell.exec(command="mysql -h <host> -u <user> -p\"$DB_PASS\" -e 'SELECT <cols> FROM <table> WHERE <cond> LIMIT 10;' <database>")
shell.exec(command="psql -h <host> -U <user> -d <database> -c 'SELECT <cols> FROM <table> WHERE <cond> LIMIT 10;'")
```

确认后再去 LIMIT 跑全量。

## 导出 CSV / JSON

| 方言 | 命令 |
|---|---|
| SQLite CSV | `sqlite3 -header -csv <db> '<select>' > <out.csv>` |
| SQLite JSON | `sqlite3 <db> -json '<select>' > <out.json>` |
| MySQL CSV | `mysql -h <host> -u <user> -p"$DB_PASS" -B --batch -e '<select>' <db> \| sed 's/\\t/,/g' > <out.csv>` |
| PostgreSQL CSV | `psql -h <host> -U <user> -d <db> -c "\\copy (<select>) TO '<out.csv>' WITH CSV HEADER"` |

> 落盘路径：`file.write` 仅允许 `~/.zhiwei/workspace` / `~/.zhiwei/skills`；CLI 重定向也尽量写到工作区下。

## 写操作的影响范围预演

写操作执行前用等价 SELECT 估算影响：

```sql
SELECT COUNT(*) FROM <table> WHERE <cond>;
SELECT <cols> FROM <table> WHERE <cond> LIMIT 10;
```

把行数 + 样例展示给用户后等确认。事务包裹示例：

```bash
shell.exec(command="mysql -h <host> -u <user> -p\"$DB_PASS\" <database> -e 'BEGIN; UPDATE <table> SET <col>=<val> WHERE <cond>; ROLLBACK;'")
# 确认无误后把 ROLLBACK 改 COMMIT 重跑
```

## EXPLAIN 优化

| 方言 | 命令 |
|---|---|
| SQLite | `EXPLAIN QUERY PLAN <select>` |
| MySQL | `EXPLAIN <select>` 或 `EXPLAIN ANALYZE <select>`（8.0+） |
| PostgreSQL | `EXPLAIN ANALYZE <select>` |

读执行计划的优先级：是否走索引 > 估算行数 > join 顺序 > 全表扫描点。

## 大表导出

> 10 万行先按时间 / 主键范围切片，循环导出：

```bash
for d in <date list>; do
  sqlite3 -header -csv <db> "SELECT <cols> FROM <table> WHERE <date_col>='${d}'" > "<out>_${d}.csv"
done
```

不要单条 SELECT 拉全表。

## 错误处理

| 现象 | 处理 |
|---|---|
| 连接失败 / Connection refused | 确认 host / port / 服务是否启动；防火墙 |
| Access denied / 1045 | 用户名 / 密码错；确认 DB_PASS 环境变量是否注入 |
| Unknown database | 库名错或当前用户无访问权 |
| 编码乱码 | MySQL 加 `--default-character-set=utf8mb4`；连接串带 `charset=utf8mb4` |
| 查询超时 | 加 LIMIT / 索引 / 缩窗口；EXPLAIN 看是否全表扫 |
| 锁等待（MySQL 1205） | 长事务卡住；查 `SHOW PROCESSLIST` 找元凶，不要盲目 KILL |
| SQLite database is locked | WAL 下单写入者；等当前事务提交 |
