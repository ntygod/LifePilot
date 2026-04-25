package com.lifepilot.meta.infra.browser;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 浏览器工具端到端基准。
 *
 * <p>默认 {@code @Disabled}，手动执行以评估成功率：
 * <pre>{@code
 * mvn test -Dtest=BrowserE2eBenchmark -DexcludedGroups= -Dgroups=benchmark
 * }</pre>
 *
 * <p>10 个任务四类：开放搜索 3、填表 3、跨站导航 2、滚动加载 2。
 * Phase 1 目标成功率 ≥ 80%（Phase 0 基线约 40-60%）。</p>
 *
 * <p>每个方法体内填真实 navigate + snapshot + click(index) 序列并校验最终状态。
 * 骨架先就位，执行时再补具体步骤——视真实站点 DOM 可能变化而定。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Disabled("benchmark，手动执行以评估真实成功率")
@Tag("benchmark")
class BrowserE2eBenchmark {

    @Test
    void 任务1_开放搜索_google_关键词查找() {
        // TODO: navigate google.com → snapshot → input(index=搜索框, value="browser-use") → click(提交) → 断言有结果
    }

    @Test
    void 任务2_开放搜索_bing_知微() {
        // TODO: bing.com 搜 "知微" → 验证搜索结果页
    }

    @Test
    void 任务3_开放搜索_duckduckgo_隐私友好站点() {
        // TODO: duckduckgo.com
    }

    @Test
    void 任务4_填表_示例登录页() {
        // TODO: the-internet.herokuapp.com/login → snapshot → input(index=username) + input(index=password) → click(Login) → 断言登录成功
    }

    @Test
    void 任务5_填表_多字段搜索表单() {
        // TODO: 多字段 advanced search
    }

    @Test
    void 任务6_填表_多步向导() {
        // TODO: 三步向导表单
    }

    @Test
    void 任务7_跨站导航_github_到_issue() {
        // TODO: github.com/xxx → 点仓库 → 点 issues → 点具体 issue，验证标题
    }

    @Test
    void 任务8_跨站导航_stackoverflow_tag_问题() {
        // TODO: stackoverflow.com/questions/tagged/java → 点第一个问题
    }

    @Test
    void 任务9_滚动加载_hn_首页前50楼() {
        // TODO: news.ycombinator.com → scroll + snapshot 循环直到拿到 50 条
    }

    @Test
    void 任务10_滚动加载_twitter_时间线() {
        // TODO: twitter.com 时间线滚动（可能需要登录态，用 CDP 模式）
    }
}
