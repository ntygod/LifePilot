---
name: web-novel-writer
description: 当用户要进行网文长篇连载创作——开书立项、设定搭建、大纲/卷纲/章纲、正文续写、改稿、资料沉淀时使用。关键词：写网文、写一章、续写、小说设定、卷纲、章纲、黄金三章、改稿、小说工作台、开书、金手指。通用文章/报告/邮件用 content-creator，外部资料调研用 research-assistant。
version: 2.0.0
metadata:
  zhiwei:
    category: content-creation
    priority: normal
    tags:
      - web-novel
      - 网文
      - fiction
      - serialization
      - storytelling
    suggested_tools:
      - knowledge.search
      - file.read
      - file.write
      - web.search
      - memory
---

# 网文写作指南

帮助用户搭建"能持续连载"的小说系统，而非一次性短篇。

## 适用场景

- 开书立项：题材、平台、受众、卖点、标签
- 书名、简介、开篇、黄金三章
- 世界观、角色、势力、金手指、升级体系
- 主线大纲、卷纲、章纲、场景拆解
- 单章正文、续写、改稿、卡文解法
- 检索前文设定、伏笔、人物状态，做一致性校对

## 不适用场景

- 通用文章、报告、邮件 → 用 content-creator
- 外部资料调研 → 用 research-assistant

## 工作流

1. **判断任务类型**：立项 / 设定 / 大纲 / 章纲 / 正文 / 续写 / 改稿 / 资料整理，别混淆
2. **检索已有资料**：涉及角色、世界规则、前文事件、伏笔时先 `knowledge.search`
3. **按类型执行**：每种任务有自己的产出模板（详见参考）
4. **正文默认追加保存**到本地小说文件，除非用户明确说"不要保存"
5. **网文基准**：开头有钩子、中段有冲突、每章有信息增量、结尾留追更点
6. **资料沉淀按需**：不主动建库，不擅改已有设定

## 详细参考

- 各任务类型产出模板、保存格式、错误处理：`{skill_dir}/references/novel-patterns.md`
</content>
</invoke>