package com.lifepilot.skill.marketplace;

import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.install.InstalledSkillRepository;
import com.lifepilot.skill.marketplace.install.SkillInstaller;
import com.lifepilot.skill.marketplace.model.InstallResult;
import com.lifepilot.skill.marketplace.model.InstalledSkill;
import com.lifepilot.skill.marketplace.model.SkillPackage;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Skill 市场服务门面 — 协调 IndexManager、SkillInstaller、VersionResolver 的业务逻辑。
 *
 * <p>提供 Skill 浏览、搜索、安装、卸载、升级、索引刷新和更新检测等统一入口。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class MarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);

    private final IndexManager indexManager;
    private final SkillInstaller skillInstaller;
    private final VersionResolver versionResolver;
    private final InstalledSkillRepository installedSkillRepository;

    public MarketplaceService(IndexManager indexManager,
                              SkillInstaller skillInstaller,
                              VersionResolver versionResolver,
                              InstalledSkillRepository installedSkillRepository) {
        this.indexManager = indexManager;
        this.skillInstaller = skillInstaller;
        this.versionResolver = versionResolver;
        this.installedSkillRepository = installedSkillRepository;
    }

    /**
     * 分页获取 Skill 包列表 — 支持关键词搜索和标签筛选。
     *
     * @param search 搜索关键词（匹配名称或描述，大小写不敏感），null 或空字符串表示不过滤
     * @param tag    标签筛选，null 或空字符串表示不过滤
     * @param page   页码（从 0 开始）
     * @param size   每页大小
     * @return 分页结果
     */
    public PagedResult<SkillPackage> getSkills(String search, String tag, int page, int size) {
        List<SkillPackage> allPackages = indexManager.getPackages();

        // 按搜索关键词过滤（名称或描述包含关键词，大小写不敏感）
        if (search != null && !search.isBlank()) {
            String lowerSearch = search.toLowerCase(Locale.ROOT);
            allPackages = allPackages.stream()
                    .filter(pkg -> containsIgnoreCase(pkg.name(), lowerSearch)
                            || containsIgnoreCase(pkg.description(), lowerSearch))
                    .toList();
        }

        // 按标签过滤
        if (tag != null && !tag.isBlank()) {
            allPackages = allPackages.stream()
                    .filter(pkg -> pkg.tags() != null && pkg.tags().contains(tag))
                    .toList();
        }

        // 按下载量降序排序
        allPackages = allPackages.stream()
                .sorted((a, b) -> Integer.compare(b.downloads(), a.downloads()))
                .toList();

        // 分页
        long totalElements = allPackages.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        int fromIndex = Math.min(page * size, allPackages.size());
        int toIndex = Math.min(fromIndex + size, allPackages.size());
        List<SkillPackage> content = allPackages.subList(fromIndex, toIndex);

        log.debug("Skill 列表查询: search={}, tag={}, page={}, size={}, total={}",
                search, tag, page, size, totalElements);
        return new PagedResult<>(List.copyOf(content), page, size, totalElements, totalPages);
    }

    /**
     * 按 ID 获取单个 Skill 包详情。
     *
     * @param id 包 ID
     * @return SkillPackage Optional，不存在时返回 empty
     */
    public Optional<SkillPackage> getSkill(String id) {
        return indexManager.getPackage(id);
    }

    /**
     * 安装 Skill。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险 Skill
     * @return 安装结果
     */
    public InstallResult install(String packageId, boolean confirmHighRisk) {
        log.info("安装 Skill: packageId={}, confirmHighRisk={}", packageId, confirmHighRisk);
        return skillInstaller.install(packageId, confirmHighRisk);
    }

    /**
     * 卸载 Skill。
     *
     * @param packageId 市场包 ID
     * @return 卸载结果
     */
    public InstallResult uninstall(String packageId) {
        log.info("卸载 Skill: packageId={}", packageId);
        return skillInstaller.uninstall(packageId);
    }

    /**
     * 升级 Skill。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险 Skill
     * @return 升级结果
     */
    public InstallResult upgrade(String packageId, boolean confirmHighRisk) {
        log.info("升级 Skill: packageId={}, confirmHighRisk={}", packageId, confirmHighRisk);
        return skillInstaller.upgrade(packageId, confirmHighRisk);
    }

    /**
     * 刷新所有索引源。
     *
     * @return 成功刷新的索引源数量
     */
    public int refreshIndex() {
        log.info("刷新索引");
        return indexManager.refreshAll();
    }

    /**
     * 获取有可用更新的 Skill 包列表。
     *
     * @return 有更新的 SkillPackage 列表
     */
    public List<SkillPackage> getUpdates() {
        List<InstalledSkill> installedSkills = installedSkillRepository.findAll();
        List<SkillPackage> allPackages = indexManager.getPackages();
        return versionResolver.findUpdates(installedSkills, allPackages);
    }

    /**
     * 大小写不敏感的包含检查。
     */
    private static boolean containsIgnoreCase(String text, String lowerSearch) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerSearch);
    }

    /**
     * 分页结果。
     *
     * @param content       当前页内容
     * @param page          页码（从 0 开始）
     * @param size          每页大小
     * @param totalElements 总元素数
     * @param totalPages    总页数
     * @param <T>           元素类型
     */
    public record PagedResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
