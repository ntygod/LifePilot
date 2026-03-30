# 知微插件仓库拆分方案

## 1. 结论

知微插件体系现在按三个仓库角色推进：

- `zhiwei`
  - 主系统仓库
  - 负责渠道控制面、Marketplace 运行时、安装器、manifest 校验、统一 runtime 协议、Web UI 管理页
- `ZhiWei-plugins`
  - 官方插件 monorepo
  - 负责官方渠道插件包、官方 connector 工程、插件发布流水线
- `ZhiWei-index`
  - 官方市场索引仓库
  - 只负责发布 `index.json`，不再承载插件源码

当前仓库地址：

- `ZhiWei`: `https://github.com/ntygod/ZhiWei`
- `ZhiWei-plugins`: `https://github.com/ntygod/ZhiWei-plugins`
- `ZhiWei-index`: `https://github.com/ntygod/ZhiWei-index`

`ZhiWei-index` 不适合继续演进成真正的插件仓库。它最适合保留为“索引发布仓库”。

## 2. 当前主仓库定位

当前主仓库不再保留官方插件包和 connector 源码。

主仓库只负责：

- Marketplace 控制面
- 统一安装器与校验逻辑
- connector runtime 协议
- 渠道实例生命周期管理

官方插件源码的唯一主源是 `ZhiWei-plugins`。

## 3. 仓库职责划分

### 3.1 `zhiwei`

保留：

- `src/main/java/com/lifepilot/marketplace/**`
- `src/main/java/com/lifepilot/interaction/runtime/**`
- `src/main/java/com/lifepilot/interaction/service/**`
- `src/main/java/com/lifepilot/interaction/web/controller/MarketplaceController.java`
- `src/main/java/com/lifepilot/marketplace/install/ChannelInstallStrategy.java`
- `src/main/java/com/lifepilot/marketplace/security/ChannelPluginManifestValidator.java`

不再放：

- 官方渠道插件源码
- 官方 connector 真实实现
- 插件发布产物

### 3.2 `ZhiWei-plugins`

建议目录：

```text
channels/
  feishu/
    channel-plugin.json
    docs/README.md
    examples/*.json
    assets/icon.svg
  wecom/
  dingtalk/

connectors/
  feishu-connector/
  wecom-connector/
  dingtalk-connector/

tools/
  export-channel-index.ps1

.github/workflows/
  validate-plugins.yml
  publish-connectors.yml
  publish-index.yml
```

职责：

- 维护官方插件包
- 维护官方 connector
- 运行插件校验和集成测试
- 生成发往 `ZhiWei-index` 的渠道索引

### 3.3 `ZhiWei-index`

建议只保留：

```text
index.json
README.md
```

未来如有需要，可以增加：

- `checksums.json`
- `channels.index.json`
- `skills.index.json`

但 `index.json` 仍然应该是主入口。

## 4. 官方插件 monorepo 规范

### 4.1 渠道插件目录

每个插件目录最少包含：

- `channel-plugin.json`
- `docs/README.md`
- `assets/icon.svg`
- `examples/*.json`

其中：

- `channel-plugin.json` 是 Marketplace 主入口
- `README.md` 是控制面和市场页展示的接入文档
- `examples/*.json` 用于配置示例
- `assets/*` 用于图标和补充静态资源

### 4.2 connector 目录

每个 connector 工程最少应实现：

- `POST /instances/{instanceId}/start`
- `POST /instances/{instanceId}/stop`
- `POST /instances/{instanceId}/reload`
- `GET /instances/{instanceId}/health`
- `POST /instances/{instanceId}/deliver`

如需主动回调主服务，还应调用：

- `POST /api/channel-runtime/instances/{instanceId}/events`
- `POST /api/channel-runtime/instances/{instanceId}/heartbeat`

### 4.3 官方 connector 开发基线

当前样板工程已迁入 `ZhiWei-plugins`：

- `connectors/mock-http-connector`

后续 `feishu-connector / wecom-connector / dingtalk-connector` 应从该样板复制起步，再替换各自平台协议实现。

## 5. `ZhiWei-index` 生成规则

`ZhiWei-index` 不再手写渠道条目，而由插件仓库自动生成。

当前推荐规则：

- 从 `channels/*/channel-plugin.json` 收集插件信息
- 生成 `ExtensionPackage` 兼容格式
- `repoUrl` 使用“可直接下载文件的原始地址前缀”，不要填 GitHub HTML 页面地址
- `filePath` 固定指向 `channel-plugin.json`
- `description` 优先取插件 README 第一段正文
- `requirements` 至少包含 connector 运行模式信息

示例：

```json
{
  "id": "feishu",
  "name": "飞书",
  "type": "CHANNEL",
  "version": "1.0.0",
  "author": "zhiwei",
  "description": "这个插件包用于把飞书接入到知微统一渠道控制面。",
  "repoUrl": "https://raw.githubusercontent.com/ntygod/ZhiWei-plugins/main/channels/feishu",
  "filePath": "channel-plugin.json",
  "tags": ["channel", "feishu", "external"],
  "requirements": ["需要外部 connector"],
  "minLifepilotVersion": "1.0.0",
  "createdAt": "2026-03-29T00:00:00Z",
  "updatedAt": "2026-03-29T00:00:00Z",
  "downloads": 0,
  "verified": true
}
```

## 6. 发布流水线建议

建议由 `ZhiWei-plugins` 仓库 CI 负责：

1. 校验 `channel-plugin.json`
2. 校验资源文件是否齐全
3. 构建 connector 镜像
4. 生成渠道索引 JSON
5. 推送或 PR 到 `ZhiWei-index`

主仓库不再承担插件发布职责。

## 7. 当前仓库的执行规则

当前阶段已经完成仓库拆分，执行规则调整为：

- 官方插件包只在 `ZhiWei-plugins` 维护
- 官方 connector 样板和真实实现只在 `ZhiWei-plugins` 维护
- `ZhiWei-index` 由插件仓库自动更新
- 主仓库不再新增任何本地官方插件源码目录

## 8. 下一步

当前最合理的后续动作是：

1. 在 `ZhiWei-plugins` 中继续补真实官方 connector
2. 用 CI 自动更新 `ZhiWei-index`
3. 主仓库后续只保留对外部插件仓库的消费能力
