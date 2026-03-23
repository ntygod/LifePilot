# ZhiWei 仓库更新日志

## 2026-03-23

**提交总数**: 10

### 更新摘要

#### 1. [6904f4f](6904f4f) - refactor(skill): SkillDiscoveryRegistrar 改为自动扫描 classpath 释放所有 Skill

- **提交者**: 金陵雪
- **时间**: 2026-03-22 18:42:51 UTC
- **详情**: 不再维护种子列表（skillPaths），直接扫描 classpath:skills/*/SKILL.md，自动释放所有内置 Skill 到用户目录。新增 Skill 只需放到 resources/skills/ 下即可。

#### 2. [7e6f9b2](7e6f9b2) - fix: 恢复 infrastructure 和 introspection 到种子 Skill 列表

- **提交者**: 金陵雪
- **时间**: 2026-03-22 18:37:08 UTC
- **详情**: 这两个 Skill 提供基础工具使用指南和系统自省能力，Agent 需要它们来了解如何使用工具和回答'你能做什么'类问题。

#### 3. [5756b45](5756b45) - fix: 系统提示词时间缓存优化 + skills CLI 包名修正

- **提交者**: 金陵雪
- **时间**: 2026-03-22 18:33:56 UTC
- **详情**: 1. react-system.st 移除动态时间注入（改为引用 User Prompt 中的时间），避免每次请求 system prompt 都变化导致 LLM 缓存失效

#### 4. [b9c28bb](b9c28bb) - fix(tool+skill): 修复 Skill 注册失败 — 工具名修正 + 新增 browser.close/workflow.resume

- **提交者**: 金陵雪
- **时间**: 2026-03-22 18:03:52 UTC
- **详情**: 工具名修正（SKILL.md 中引用了不存在的工具名）

#### 5. [f933644](f933644) - refactor(skill): SkillDiscoveryRegistrar 恢复为纯 classpath 释放器

- **提交者**: 金陵雪
- **时间**: 2026-03-22 17:46:01 UTC
- **详情**: SkillHub 集成点应该只在 find-skills Skill 中（Agent 运行时按需调用），不应该在启动时的种子 Skill 释放流程中。

#### 6. [913da98](913da98) - fix(skill): SkillHub 改为 CLI 模式，修复 HTML 误写入问题

- **提交者**: 金陵雪
- **时间**: 2026-03-22 17:35:10 UTC
- **详情**: SkillHub 提供的是 CLI 工具（skillhub search/install），不是 REST API。

#### 7. [e9530eb](e9530eb) - Revert "fix(skill): SkillHubClient 和 SkillDiscoveryRegistrar 添加内容校验"

- **提交者**: 金陵雪
- **时间**: 2026-03-22 17:28:01 UTC
- **详情**: This reverts commit e34caad10118f5654e1dfd353eaec0b62bb457ab.

#### 8. [e34caad](e34caad) - fix(skill): SkillHubClient 和 SkillDiscoveryRegistrar 添加内容校验

- **提交者**: 金陵雪
- **时间**: 2026-03-22 17:23:25 UTC
- **详情**: SkillHub 返回 HTML 页面而非 Markdown API 响应，导致 HTML 被当作 SKILL.md 写入用户目录。

#### 9. [6e28b2a](6e28b2a) - Revert "fix(skill): SkillDiscoveryRegistrar 支持种子 Skill 版本更新覆盖"

- **提交者**: 金陵雪
- **时间**: 2026-03-22 17:18:43 UTC
- **详情**: This reverts commit fb73619e24ebaea28553f09ebde8413530fb77ec.

#### 10. [fb73619](fb73619) - fix(skill): SkillDiscoveryRegistrar 支持种子 Skill 版本更新覆盖

- **提交者**: 金陵雪
- **时间**: 2026-03-22 17:09:41 UTC
- **详情**: 之前已存在的 SKILL.md 不会被更新，导致用户目录中的旧版本无法被解析。

---
*此文档由 ZhiWei 自动生成并每日更新*
