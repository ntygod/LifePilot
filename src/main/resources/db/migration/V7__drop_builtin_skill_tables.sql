-- 删除待办/日程/习惯内置 Skill 相关表
-- 这些功能已被自主任务执行模块替代

DROP TABLE IF EXISTS todos;
DROP TABLE IF EXISTS schedules;
DROP TABLE IF EXISTS habits;
DROP TABLE IF EXISTS habit_logs;
