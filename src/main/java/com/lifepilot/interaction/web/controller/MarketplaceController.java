package com.lifepilot.interaction.web.controller;

import com.lifepilot.skill.marketplace.MarketplaceService;
import com.lifepilot.skill.marketplace.model.InstallResult;
import com.lifepilot.skill.marketplace.model.SkillPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Skill 市场 REST API 控制器。
 *
 * <p>提供 Skill 浏览、搜索、安装、卸载、升级和索引刷新端点。
 * 仅在 {@link MarketplaceService} Bean 存在时注册。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
@RestController
@RequestMapping("/api/marketplace")
@ConditionalOnBean(MarketplaceService.class)
public class MarketplaceController {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceController.class);

    private final MarketplaceService marketplaceService;

    public MarketplaceController(MarketplaceService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    /**
     * 分页获取 Skill 包列表 — 支持关键词搜索和标签筛选。
     *
     * @param search 搜索关键词（匹配名称或描述）
     * @param tag    标签筛选
     * @param page   页码（从 0 开始，默认 0）
     * @param size   每页大小（默认 20）
     * @return 分页结果
     */
    @GetMapping("/skills")
    public ResponseEntity<MarketplaceService.PagedResult<SkillPackage>> listSkills(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询 Skill 列表: search={}, tag={}, page={}, size={}", search, tag, page, size);
        var result = marketplaceService.getSkills(search, tag, page, size);
        return ResponseEntity.ok(result);
    }

    /**
     * 按 ID 获取单个 Skill 包详情。
     *
     * @param id 包 ID
     * @return SkillPackage 或 404
     */
    @GetMapping("/skills/{id}")
    public ResponseEntity<SkillPackage> getSkill(@PathVariable String id) {
        log.debug("查询 Skill 详情: id={}", id);
        return marketplaceService.getSkill(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 安装 Skill。
     *
     * @param id              市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险 Skill（默认 false）
     * @return 安装结果
     */
    @PostMapping("/skills/{id}/install")
    public ResponseEntity<InstallResult> installSkill(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean confirmHighRisk) {
        log.info("安装 Skill: id={}, confirmHighRisk={}", id, confirmHighRisk);
        var result = marketplaceService.install(id, confirmHighRisk);
        return ResponseEntity.ok(result);
    }

    /**
     * 卸载 Skill。
     *
     * @param id 市场包 ID
     * @return 卸载结果
     */
    @DeleteMapping("/skills/{id}")
    public ResponseEntity<InstallResult> uninstallSkill(@PathVariable String id) {
        log.info("卸载 Skill: id={}", id);
        var result = marketplaceService.uninstall(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 刷新所有索引源。
     *
     * @return 刷新结果（成功刷新的索引源数量）
     */
    @PostMapping("/index/refresh")
    public ResponseEntity<RefreshResult> refreshIndex() {
        log.info("刷新索引");
        int count = marketplaceService.refreshIndex();
        return ResponseEntity.ok(new RefreshResult(count, "索引刷新完成，成功刷新 " + count + " 个索引源"));
    }

    /**
     * 获取有可用更新的 Skill 包列表。
     *
     * @return 有更新的 SkillPackage 列表
     */
    @GetMapping("/updates")
    public ResponseEntity<List<SkillPackage>> getUpdates() {
        log.debug("查询可用更新");
        var updates = marketplaceService.getUpdates();
        return ResponseEntity.ok(updates);
    }

    /**
     * 升级 Skill。
     *
     * @param id              市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险 Skill（默认 false）
     * @return 升级结果
     */
    @PostMapping("/skills/{id}/upgrade")
    public ResponseEntity<InstallResult> upgradeSkill(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean confirmHighRisk) {
        log.info("升级 Skill: id={}, confirmHighRisk={}", id, confirmHighRisk);
        var result = marketplaceService.upgrade(id, confirmHighRisk);
        return ResponseEntity.ok(result);
    }

    /**
     * 索引刷新结果。
     *
     * @param count   成功刷新的索引源数量
     * @param message 结果描述
     */
    public record RefreshResult(int count, String message) {}
}
