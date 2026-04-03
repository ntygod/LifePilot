package com.lifepilot.interaction.web.controller;

import com.lifepilot.marketplace.MarketplaceService;
import com.lifepilot.marketplace.model.ExtensionAssetContent;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionInstallation;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstallResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
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
     * 按 ID 获取已安装扩展的本地安装快照。
     *
     * @param id 包 ID
     * @return 安装快照或 404
     */
    @GetMapping("/extensions/{id}/installation")
    public ResponseEntity<ExtensionInstallation> getInstallation(@PathVariable String id) {
        log.debug("查询扩展安装快照: id={}", id);
        return marketplaceService.getInstallation(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 按相对路径读取已安装扩展资产文件。
     *
     * @param id   包 ID
     * @param path 插件目录内相对路径
     * @return 资产文件或 404
     */
    @GetMapping("/extensions/{id}/assets/file")
    public ResponseEntity<byte[]> getInstallationAsset(@PathVariable String id,
                                                       @RequestParam("path") String path) {
        log.debug("读取扩展安装资产: id={}, path={}", id, path);
        return marketplaceService.getInstallationAsset(id, path)
                .map(this::assetResponse)
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
     * 实时搜索 ClawHub 第三方 Skill — 不经过索引缓存。
     *
     * @param q 搜索关键词
     * @return ClawHub 搜索结果（转换为 ExtensionPackage 格式）
     */
    @GetMapping("/clawhub/search")
    public ResponseEntity<List<ExtensionPackage>> searchClawHub(@RequestParam String q) {
        log.debug("ClawHub 实时搜索: q={}", q);
        var results = marketplaceService.searchClawHub(q);
        return ResponseEntity.ok(results);
    }

    /**
     * 索引刷新结果。
     *
     * @param count   成功刷新的索引源数量
     * @param message 结果描述
     */
    public record RefreshResult(int count, String message) {}

    private ResponseEntity<byte[]> assetResponse(ExtensionAssetContent asset) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(asset.contentType());
        } catch (Exception ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + sanitizeFileName(asset.relativePath()) + "\"")
                .body(asset.content());
    }

    private String sanitizeFileName(String relativePath) {
        int slashIndex = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return slashIndex >= 0 ? relativePath.substring(slashIndex + 1) : relativePath;
    }
}
