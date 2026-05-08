package com.lifepilot.memory.eval.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 记忆评估 Harness 配置属性。
 *
 * <p>绑定 {@code lifepilot.memory.eval} 配置前缀。默认 {@code enabled=false}，
 * 只在 Maven profile {@code memory-eval-quick} / {@code memory-eval-full} 或
 * 显式设置 {@code -Dlifepilot.memory.eval.enabled=true} 时生效。</p>
 *
 * <p>配置面向开发期使用，不面向终端用户。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.memory.eval")
public class MemoryEvalProperties {

    /** Harness 总开关，默认关闭，避免生产环境误启用。 */
    private boolean enabled = false;

    /** 运行模式：{@code quick}（快速） / {@code full}（完整）。默认 quick。 */
    private String mode = "quick";

    /** 数据集缓存目录，首次下载由 {@code scripts/download-eval-datasets.sh} 填充。 */
    private Path datasetCacheDir = Paths.get(
            System.getProperty("user.home"), ".zhiwei", "eval-cache");

    /** 重放完成后是否手动触发一次 ConsolidationPipeline。 */
    private boolean triggerConsolidation = true;

    /** 各 benchmark 的独立配置。 */
    private Benchmarks benchmarks = new Benchmarks();

    /** 答案判定配置。 */
    private Judge judge = new Judge();

    /** 基线对比与退化检测配置。 */
    private Regression regression = new Regression();

    /** 隔离评估环境配置。 */
    private Isolation isolation = new Isolation();

    /** 报告输出配置。 */
    private Reporting reporting = new Reporting();

    /**
     * 判断当前模式是否为快速模式（quick）。
     */
    public boolean isQuickMode() {
        return "quick".equalsIgnoreCase(mode);
    }

    /**
     * Benchmark 配置集合。
     */
    @Setter
    @Getter
    public static class Benchmarks {
        /** LoCoMo 基准配置。 */
        private Locomo locomo = new Locomo();
        /** LongMemEval 基准配置。 */
        private LongMemEval longMemEval = new LongMemEval();

        /**
         * LoCoMo 配置（社区数据集 snap-research/locomo，Apache-2.0）。
         */
        @Setter
        @Getter
        public static class Locomo {
            /** 是否启用 LoCoMo。 */
            private boolean enabled = true;
            /** 完整模式最大对话数。 */
            private int maxConversations = 10;
            /** 快速模式降级的最大对话数。 */
            private int quickMaxConversations = 2;
        }

        /**
         * LongMemEval 配置（ICLR 2025，MIT 许可）。
         */
        @Setter
        @Getter
        public static class LongMemEval {
            /** 是否启用 LongMemEval。 */
            private boolean enabled = true;
            /** 完整模式最大题目数。 */
            private int maxQuestions = 500;
            /** 快速模式降级的最大题目数。 */
            private int quickMaxQuestions = 50;
        }
    }

    /**
     * 答案判定配置。
     */
    @Setter
    @Getter
    public static class Judge {
        /** 是否启用 LLM-as-judge；关闭时开放问答题降级为 F1Judge 或跳过。 */
        private boolean llmEnabled = false;
        /** LLM-as-judge 使用的场景名（LlmRouter scene）。 */
        private String llmScene = "eval";
        /** 是否启用 ExactMatchJudge。 */
        private boolean exactMatchEnabled = true;
        /** 是否启用 F1Judge。 */
        private boolean f1Enabled = true;
    }

    /**
     * 基线对比与退化检测配置。
     */
    @Setter
    @Getter
    public static class Regression {
        /** 是否启用退化检测。 */
        private boolean enabled = true;
        /** 基线 JSON 路径。 */
        private Path baselinePath = Paths.get("docs", "memory-eval", "baseline.json");
        /** LLM-Score 下降超过此值视为退化（绝对差值，默认 3 个百分点）。 */
        private float llmScoreTolerance = 0.03f;
        /** p95 延迟上升超过此比例视为退化（默认 20%）。 */
        private float latencyTolerance = 0.20f;
        /** 平均 token 上升超过此比例视为退化（默认 30%）。 */
        private float tokenTolerance = 0.30f;
        /** 跑批成功后是否自动更新基线文件；默认 false，需人工 bump。 */
        private boolean updateBaseline = false;
    }

    /**
     * 隔离评估环境配置。
     */
    @Setter
    @Getter
    public static class Isolation {
        /** 是否为每个用例创建独立临时 SQLite。 */
        private boolean useTempSqlite = true;
        /** 用例失败时是否保留 SQLite 文件便于调试。 */
        private boolean keepDbOnFailure = false;
    }

    /**
     * 报告输出配置。
     */
    @Setter
    @Getter
    public static class Reporting {
        /** 报告输出目录。 */
        private Path outputDir = Paths.get("target", "memory-eval");
        /** 是否生成 Markdown 报告。 */
        private boolean markdown = true;
        /** 是否生成 JSON 报告。 */
        private boolean json = true;
    }
}
