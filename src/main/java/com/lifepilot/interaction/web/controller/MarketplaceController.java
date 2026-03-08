package com.lifepilot.interaction.web.controller;

import com.lifepilot.marketplace.MarketplaceService;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 扩展市场 REST API 控制器。
 *
 * <p>提供扩展浏览、搜索、安装、卸载、升级和索引刷新端点，
 * 支持 SKILL / AGENT / WORKFLOW 三种扩展类型。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceController.class);

    private final MarketplaceService marketplaceService;

    public MarketplaceController(MarketplaceService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    /**
     * 分页获取扩展包列表 — 支持类型筛选、关键词搜索和标签筛选。
     *
     * @param type   扩展类型筛选（SKILL / AGENT / WORKFLOW），null 表示不过滤
     * @param search 搜索关键词（匹配名称或描述）
     * @param tag    标签筛选
     * @param page   页码（从 0 开始，默认 0）
     * @param size   每页大小（默认 20）
     * @return 分页结果
     */
    @GetMapping("/extensions")
    public ResponseEntity<MarketplaceService.PagedResult<ExtensionPackage>> listExtensions(
            @RequestParam(required = false) @Nullable ExtensionType type,
            @RequestParam(required = false) @Nullable String search,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询扩展列表: type={}, search={}, tag={}, page={}, size={}", type, search, tag, page, size);
        var result = marketplaceService.getExtensions(type, search, tag, page, size);
        return ResponseEntity.ok(result);
    }

    /**
     * 按 ID 获取单个扩展包详情。
     *
     * @param id 包 ID
     * @return ExtensionPackage 或 404
     */
    @GetMapping("/extensions/{id}")
    public ResponseEntity<ExtensionPackage> getExtension(@PathVariable String id) {
        log.debug("查询扩展详情: id={}", id);
        return marketplaceService.getExtension(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 安装扩展。
     *
     * @param id              市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险扩展（默认 false）
     * @return 安装结果
     */
    @PostMapping("/extensions/{id}/install")
    public ResponseEntity<InstallResult> installExtension(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean confirmHighRisk) {
        log.info("安装扩展: id={}, confirmHighRisk={}", id, confirmHighRisk);
        var result = marketplaceService.install(id, confirmHighRisk);
        return ResponseEntity.ok(result);
    }

    /**
     * 卸载扩展。
     *
     * @param id 市场包 ID
     * @return 卸载结果
     */
    @DeleteMapping("/extensions/{id}")
    public ResponseEntity<InstallResult> uninstallExtension(@PathVariable String id) {
        log.info("卸载扩展: id={}", id);
        var result = marketplaceService.uninstall(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 升级扩展。
     *
     * @param id              市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险扩展（默认 false）
     * @return 升级结果
     */
    @PostMapping("/extensions/{id}/upgrade")
    public ResponseEntity<InstallResult> upgradeExtension(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean confirmHighRisk) {
        log.info("升级扩展: id={}, confirmHighRisk={}", id, confirmHighRisk);
        var result = marketplaceService.upgrade(id, confirmHighRisk);
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
     * 获取有可用更新的扩展包列表。
     *
     * @return 有更新的 ExtensionPackage 列表
     */
    @GetMapping("/updates")
    public ResponseEntity<List<ExtensionPackage>> getUpdates() {
        log.debug("查询可用更新");
        var updates = marketplaceService.getUpdates();
        return ResponseEntity.ok(updates);
    }

    /**
     * 索引刷新结果。
     *
     * @param count   成功刷新的索引源数量
     * @param message 结果描述
     */
    public record RefreshResult(int count, String message) {}
}
