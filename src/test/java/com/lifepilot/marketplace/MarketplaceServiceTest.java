package com.lifepilot.marketplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.install.ExtensionInstaller;
import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import com.lifepilot.marketplace.model.ExtensionAssetContent;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.marketplace.model.InstalledExtensionAsset;
import com.lifepilot.marketplace.version.VersionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link MarketplaceService} 单元测试。
 *
 * @author zsg
 * @since 2026-03-29
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceServiceTest {

    @Mock
    private IndexManager indexManager;

    @Mock
    private ExtensionInstaller extensionInstaller;

    @Mock
    private VersionResolver versionResolver;

    @Mock
    private InstalledExtensionRepository installedExtensionRepository;

    @TempDir
    Path tempDir;

    @Test
    void getInstallationAsset_已登记资源_返回文件内容() throws Exception {
        Path readme = tempDir.resolve("docs").resolve("README.md");
        Files.createDirectories(readme.getParent());
        Files.writeString(readme, "# 插件说明");

        InstalledExtension installed = new InstalledExtension(
                "ext-1",
                "pkg-1",
                ExtensionType.CHANNEL,
                "测试渠道",
                "1.0.0",
                "https://example.com/index.json",
                "https://example.com/repo",
                tempDir.resolve("channel-plugin.json").toString(),
                tempDir.toString(),
                null,
                null,
                new ObjectMapper().writeValueAsString(List.of(
                        new InstalledExtensionAsset("README", "docs/README.md", readme.toString())
                )),
                Instant.now(),
                Instant.now()
        );
        MarketplaceService service = new MarketplaceService(
                indexManager,
                extensionInstaller,
                versionResolver,
                installedExtensionRepository,
                new ObjectMapper()
        );
        when(installedExtensionRepository.findByPackageId("pkg-1")).thenReturn(Optional.of(installed));

        Optional<ExtensionAssetContent> asset = service.getInstallationAsset("pkg-1", "docs/README.md");

        assertThat(asset).isPresent();
        assertThat(new String(asset.orElseThrow().content())).isEqualTo("# 插件说明");
        assertThat(asset.orElseThrow().contentType()).contains("text/markdown");
    }

    @Test
    void getInstallationAsset_未登记路径_返回空() {
        InstalledExtension installed = new InstalledExtension(
                "ext-1",
                "pkg-1",
                ExtensionType.CHANNEL,
                "测试渠道",
                "1.0.0",
                "https://example.com/index.json",
                "https://example.com/repo",
                tempDir.resolve("channel-plugin.json").toString(),
                tempDir.toString(),
                null,
                null,
                "[]",
                Instant.now(),
                Instant.now()
        );
        MarketplaceService service = new MarketplaceService(
                indexManager,
                extensionInstaller,
                versionResolver,
                installedExtensionRepository,
                new ObjectMapper()
        );
        when(installedExtensionRepository.findByPackageId("pkg-1")).thenReturn(Optional.of(installed));

        assertThat(service.getInstallationAsset("pkg-1", "docs/README.md")).isEmpty();
    }
}
