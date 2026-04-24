package com.lifepilot.tool.tier1;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 每日凌晨扫描 tool_usage_stats，按覆盖率生成 Tier 1 晋升候选写入 tier1_advisory。
 *
 * <p>不自动改 application.yml，只生成 PENDING 建议等管理员审批。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1AdvisoryJob {

    private static final Logger log = LoggerFactory.getLogger(Tier1AdvisoryJob.class);

    private final ToolConfigProperties config;
    private final ToolUsageStatsRepository statsRepository;
    private final Tier1AdvisoryRepository advisoryRepository;

    public Tier1AdvisoryJob(
            ToolConfigProperties config,
            ToolUsageStatsRepository statsRepository,
            Tier1AdvisoryRepository advisoryRepository) {
        this.config = config;
        this.statsRepository = statsRepository;
        this.advisoryRepository = advisoryRepository;
    }

    @Scheduled(cron = "${lifepilot.tool.tier1.promotion.cron:0 0 3 * * *}")
    public void reviewTier1() {
        var promo = config.getTier1().getPromotion();
        if (!promo.isEnabled()) {
            return;
        }
        log.info("开始 Tier 1 晋升候选审查: window={}天, threshold={}",
                promo.getWindowDays(), promo.getSessionThreshold());
        Map<String, Double> coverage = statsRepository.computeSessionCoverage(promo.getWindowDays());
        Set<String> pinned = Set.copyOf(config.getTier1().getPinned());

        Instant now = Instant.now();
        long created = coverage.entrySet().stream()
                .filter(e -> e.getValue() >= promo.getSessionThreshold())
                .filter(e -> !pinned.contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(promo.getMaxPromoted())
                .peek(e -> {
                    var adv = new Tier1Advisory(
                            null, e.getKey(), now, promo.getWindowDays(),
                            e.getValue(), Tier1AdvisoryStatus.PENDING, null, null);
                    advisoryRepository.save(adv);
                })
                .count();
        log.info("Tier 1 建议生成完成: count={}", created);
    }
}
