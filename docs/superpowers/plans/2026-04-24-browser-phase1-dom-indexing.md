# 浏览器能力补全 Phase 1 — DOM 标号 + vision 闭环 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **上游 roadmap**：`docs/superpowers/plans/2026-04-24-browser-capability-roadmap.md`
> **前置依赖**：Phase 0 已合入 develop —— `BrowserSessionScheduler` + `@PreDestroy` + `SsrfGuard` + web.fetch method/headers 实现 + screenshot data-uri 挂 vision + CDP last-active + UA 动态化

**Goal：** 新增 `browser.snapshot` action，一次性返回截图 + 可交互元素标号表；`click` / `input` / `hover` 支持 `index` 参数；更新 browser-automation skill 首选路径为 snapshot → index。目标 WebVoyager 同类任务成功率达 browser-use 0.12.6 水位（~89%）。

**Architecture：**
1. **JS 注入层**：纯 JS 脚本 `interactive-elements.js` 遍历 DOM、按启发式判定可交互、注入 `data-zhiwei-idx` 属性、可选叠加视觉编号标签
2. **Java 加载层**：`InteractiveElementIndexer` 启动时从 classpath 读脚本缓存，`page.evaluate(script, opts)` 调用并解析 JSON 为 `List<IndexedElement>` record
3. **Page Wrapper 层**：`PlaywrightPageWrapper` 新增 `indexInteractiveElements` / `clickByIndex` / `inputByIndex` / `hoverByIndex`；index 定位走 `data-zhiwei-idx` 选择器（比 bbox 坐标更抗 layout 抖动）
4. **Action 层**：新 `BrowserSnapshotToolExecutor`；`click/input/hover` 扩展 `index` 参数，与 `selector` 二选一
5. **Skill 层**：browser-automation 首选路径改 snapshot→index，fallback 链补全

**Tech Stack：** Playwright-Java 1.58、Java 22（record / sealed）、JUnit 5 + Mockito、JS（浏览器端脚本）、Jackson（JSON 解析）

---

## File Structure

### 后端新建

| 路径 | 责任 |
|---|---|
| `src/main/resources/static/browser-scripts/interactive-elements.js` | DOM 可交互元素遍历、编号、data 属性注入、可选视觉标签 |
| `src/main/java/com/lifepilot/meta/infra/browser/IndexedElement.java` | record：index/tag/role/text/name/id/ariaLabel/bbox |
| `src/main/java/com/lifepilot/meta/infra/browser/IndexedSnapshot.java` | record：elements / total / viewport / truncated |
| `src/main/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer.java` | 加载脚本 + `page.evaluate` + 解析 |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor.java` | `browser.snapshot` action |

### 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/meta/infra/browser/PlaywrightPageWrapper.java` | 新增 `indexInteractiveElements(InteractiveElementIndexer, boolean)`、`clickByIndex(int)`、`inputByIndex(int,String)`、`hoverByIndex(int)` |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserClickToolExecutor.java` | schema/逻辑：`index` 与 `selector` 二选一 |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserInputToolExecutor.java` | 同上 |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserHoverToolExecutor.java` | 同上 |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserActionDispatchExecutor.java` | 构造器注册 `snapshot` action（对齐 `screenshot` 的注册形式） |
| `src/main/java/com/lifepilot/meta/infra/BrowserToolProvider.java`（或等价 Provider） | schema 新增 `snapshot` action + `click/input/hover` 加 `index` 字段 |
| `src/main/resources/application.yml` | 新增 `browser.snapshot.max-elements: 200`；新增 `browser.snapshot.viewport-only: true` |
| `src/main/java/com/lifepilot/meta/config/MetaProperties.java` | `Browser.Snapshot` 内部类（maxElements / viewportOnly） |
| `src/main/resources/skills/browser-automation/SKILL.md` | 首选路径 snapshot→index；fallback 链；登录墙 → 交接占位（Phase 2 引入） |

### 测试

| 路径 | 用途 |
|---|---|
| `src/test/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer_标号测试.java` | 脚本加载 / 参数传递 / JSON 解析 / 空页面 |
| `src/test/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor_快照测试.java` | 成功路径 / 截断 / session 不存在 |
| `src/test/java/com/lifepilot/meta/infra/browser/BrowserClickToolExecutor_index路径测试.java` | index 路径 / index+selector 冲突 / stale 元素 |
| `src/test/java/com/lifepilot/meta/infra/browser/BrowserInputToolExecutor_index路径测试.java` | 同上 |
| `src/test/resources/fixtures/browser/sample-interactive.html` | 测试夹具：含各类可交互元素的静态 HTML |

---

## Task 1: IndexedElement / IndexedSnapshot record

**Files:**
- Create: `src/main/java/com/lifepilot/meta/infra/browser/IndexedElement.java`
- Create: `src/main/java/com/lifepilot/meta/infra/browser/IndexedSnapshot.java`

- [ ] **Step 1: 创建 IndexedElement record**

```java
package com.lifepilot.meta.infra.browser;

/**
 * DOM 中一个可交互元素的标号信息。
 *
 * @param index     连续整数，供 LLM 选择操作目标
 * @param tag       HTML 标签小写（a/button/input 等）
 * @param role      ARIA role（可空字符串）
 * @param text      可见文本或 value/placeholder，截断到 80 字符
 * @param name      name 属性
 * @param id        id 属性
 * @param ariaLabel aria-label 属性
 * @param bbox      [x, y, width, height]，整数像素，viewport 坐标系
 *
 * @author zsg
 * @since 2026-04-24
 */
public record IndexedElement(
        int index,
        String tag,
        String role,
        String text,
        String name,
        String id,
        String ariaLabel,
        int[] bbox
) {}
```

- [ ] **Step 2: 创建 IndexedSnapshot record**

```java
package com.lifepilot.meta.infra.browser;

import java.util.List;

/**
 * 一次 interactive element 扫描结果。
 *
 * @param elements  按发现顺序编号的元素列表
 * @param total     元素总数（可能 > elements.size() 如果被截断）
 * @param viewport  [width, height]
 * @param truncated 是否达到 maxElements 被截断
 *
 * @author zsg
 * @since 2026-04-24
 */
public record IndexedSnapshot(
        List<IndexedElement> elements,
        int total,
        int[] viewport,
        boolean truncated
) {}
```

- [ ] **Step 3: mvn compile 通过**

```bash
mvn compile -pl . -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/IndexedElement.java src/main/java/com/lifepilot/meta/infra/browser/IndexedSnapshot.java
git commit -m "feat(browser): 新增 IndexedElement 和 IndexedSnapshot record"
```

---

## Task 2: JS 注入脚本

**Files:**
- Create: `src/main/resources/static/browser-scripts/interactive-elements.js`

- [ ] **Step 1: 创建脚本文件**

文件路径：`src/main/resources/static/browser-scripts/interactive-elements.js`

```javascript
// 知微浏览器工具 - 可交互元素标号脚本
// 返回 { elements, total, viewport, truncated }
// 参数 opts: { injectLabels: bool, maxElements: int }

(function(opts) {
    const options = opts || {};
    const maxElements = options.maxElements || 200;
    const injectLabels = options.injectLabels === true;

    const INTERACTIVE_TAGS = new Set(['A','BUTTON','INPUT','SELECT','TEXTAREA','LABEL','SUMMARY','DETAILS']);
    const INTERACTIVE_ROLES = new Set([
        'button','link','menuitem','menuitemcheckbox','menuitemradio',
        'checkbox','radio','tab','treeitem','textbox','combobox',
        'slider','switch','option','searchbox','spinbutton'
    ]);

    function isVisible(el) {
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
        // 完全在 viewport 外的不计（只要部分可见就算）
        if (r.bottom < 0 || r.top > innerHeight) return false;
        if (r.right < 0 || r.left > innerWidth) return false;
        return true;
    }

    function isInteractive(el) {
        if (INTERACTIVE_TAGS.has(el.tagName)) {
            // input 排除 type=hidden
            if (el.tagName === 'INPUT' && el.type === 'hidden') return false;
            return true;
        }
        const role = el.getAttribute('role');
        if (role && INTERACTIVE_ROLES.has(role.toLowerCase())) return true;
        if (el.hasAttribute('onclick')) return true;
        if (el.hasAttribute('contenteditable') && el.getAttribute('contenteditable') !== 'false') return true;
        if (el.tabIndex >= 0 && getComputedStyle(el).cursor === 'pointer') return true;
        return false;
    }

    // 清旧标签和旧索引
    document.querySelectorAll('.__zhiwei-dom-label').forEach(n => n.remove());
    document.querySelectorAll('[data-zhiwei-idx]').forEach(n => n.removeAttribute('data-zhiwei-idx'));

    const elements = [];
    let total = 0;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
    let node;
    while ((node = walker.nextNode())) {
        if (!isInteractive(node)) continue;
        if (!isVisible(node)) continue;
        total++;
        if (elements.length >= maxElements) continue;

        const rect = node.getBoundingClientRect();
        const index = elements.length;
        node.setAttribute('data-zhiwei-idx', String(index));

        const text = (node.innerText || node.value || node.placeholder || '').trim().slice(0, 80);

        elements.push({
            index: index,
            tag: node.tagName.toLowerCase(),
            role: (node.getAttribute('role') || '').toLowerCase(),
            text: text,
            name: node.getAttribute('name') || '',
            id: node.id || '',
            ariaLabel: node.getAttribute('aria-label') || '',
            bbox: [Math.round(rect.left), Math.round(rect.top),
                   Math.round(rect.width), Math.round(rect.height)]
        });

        if (injectLabels) {
            const label = document.createElement('div');
            label.textContent = String(index);
            label.className = '__zhiwei-dom-label';
            label.style.cssText = 'position:fixed;z-index:2147483647;' +
                'background:#ff0050;color:white;padding:1px 4px;' +
                'font-size:10px;font-family:monospace;border-radius:2px;' +
                'pointer-events:none;line-height:1.2;' +
                'left:' + rect.left + 'px;top:' + Math.max(0, rect.top - 14) + 'px;';
            document.body.appendChild(label);
        }
    }

    return {
        elements: elements,
        total: total,
        viewport: [innerWidth, innerHeight],
        truncated: total > elements.length
    };
})(arguments[0] || {});
```

- [ ] **Step 2: commit 脚本**

```bash
git add src/main/resources/static/browser-scripts/interactive-elements.js
git commit -m "feat(browser): DOM 可交互元素标号 JS 脚本"
```

**说明**：本脚本暂不支持 iframe / shadow DOM 穿透，Phase 1.1 可补。已知不足已在 roadmap 文档记录。

---

## Task 3: InteractiveElementIndexer

**Files:**
- Create: `src/main/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer.java`
- Create: `src/test/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer_标号测试.java`
- Create: `src/test/resources/fixtures/browser/sample-interactive.html`

- [ ] **Step 1: 写测试 fixture HTML**

文件：`src/test/resources/fixtures/browser/sample-interactive.html`
```html
<!DOCTYPE html>
<html><body>
<a href="#" id="link1">首页</a>
<button id="btn1" aria-label="搜索">🔍</button>
<input type="text" name="q" placeholder="请输入关键词">
<input type="hidden" name="csrf" value="abc">
<select id="country"><option>CN</option><option>US</option></select>
<div role="button" tabindex="0" style="cursor:pointer">角色按钮</div>
<div>非交互元素</div>
</body></html>
```

- [ ] **Step 2: 写失败单测**

```java
package com.lifepilot.meta.infra.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InteractiveElementIndexer 真实 Playwright 集成测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Tag("playwright")
class InteractiveElementIndexer_标号测试 {

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void setup() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void teardown() {
        browser.close();
        playwright.close();
    }

    @Test
    void 扫描_sample_页面_应识别所有可交互元素_排除_hidden_input() throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/browser/sample-interactive.html")
                .toAbsolutePath();
        var context = browser.newContext();
        var page = context.newPage();
        page.navigate(fixture.toUri().toString());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        IndexedSnapshot snapshot = indexer.index(page, false, 200);

        List<IndexedElement> els = snapshot.elements();
        // link1 / btn1 / text-input / select / role=button —— 5 个，不含 hidden input
        assertThat(els).hasSize(5);
        assertThat(els.get(0).tag()).isEqualTo("a");
        assertThat(els.get(0).id()).isEqualTo("link1");
        assertThat(els.get(1).tag()).isEqualTo("button");
        assertThat(els.get(1).ariaLabel()).isEqualTo("搜索");
        assertThat(els.get(2).tag()).isEqualTo("input");
        assertThat(els.get(2).name()).isEqualTo("q");
        assertThat(snapshot.truncated()).isFalse();

        context.close();
    }

    @Test
    void 注入_data_zhiwei_idx_属性_选择器可命中() throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/browser/sample-interactive.html")
                .toAbsolutePath();
        var context = browser.newContext();
        var page = context.newPage();
        page.navigate(fixture.toUri().toString());

        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        indexer.index(page, false, 200);

        // 用 index=0 的 selector 能找到 link1
        var locator = page.locator("[data-zhiwei-idx='0']");
        assertThat(locator.getAttribute("id")).isEqualTo("link1");

        context.close();
    }

    @Test
    void maxElements_截断_truncated_为_true() throws Exception {
        Path fixture = Paths.get("src/test/resources/fixtures/browser/sample-interactive.html")
                .toAbsolutePath();
        var context = browser.newContext();
        var page = context.newPage();
        page.navigate(fixture.toUri().toString());

        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        IndexedSnapshot snapshot = indexer.index(page, false, 2);

        assertThat(snapshot.elements()).hasSize(2);
        assertThat(snapshot.total()).isGreaterThanOrEqualTo(5);
        assertThat(snapshot.truncated()).isTrue();

        context.close();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn test -Dtest=InteractiveElementIndexer_标号测试
```
Expected: FAIL（`InteractiveElementIndexer` 类不存在）

- [ ] **Step 4: 实现 InteractiveElementIndexer**

```java
package com.lifepilot.meta.infra.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * DOM 可交互元素扫描器。
 *
 * <p>启动时从 classpath 加载 JS 脚本 {@code interactive-elements.js}，
 * 每次 index 调用时通过 {@link Page#evaluate} 注入执行，解析返回 JSON 为
 * {@link IndexedSnapshot}。扫描同时给每个命中元素注入 {@code data-zhiwei-idx}
 * 属性，供后续 {@code clickByIndex} 等坐标定位。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class InteractiveElementIndexer {

    private static final Logger log = LoggerFactory.getLogger(InteractiveElementIndexer.class);
    private static final String SCRIPT_PATH = "static/browser-scripts/interactive-elements.js";

    private final ObjectMapper objectMapper;
    private final String script;

    public InteractiveElementIndexer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.script = loadScript();
    }

    private String loadScript() {
        try (var is = new ClassPathResource(SCRIPT_PATH).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 DOM 标号脚本: " + SCRIPT_PATH, e);
        }
    }

    /**
     * 扫描页面可交互元素并注入编号。
     *
     * @param page         Playwright Page
     * @param injectLabels 是否叠加红色视觉编号标签（true 时用户可直接看到编号）
     * @param maxElements  最大返回数，超出部分仍计入 total 但不返回 elements
     * @return 扫描结果
     */
    @SuppressWarnings("unchecked")
    public IndexedSnapshot index(Page page, boolean injectLabels, int maxElements) {
        Object opts = Map.of("injectLabels", injectLabels, "maxElements", maxElements);
        Object raw = page.evaluate(script, opts);
        if (!(raw instanceof Map<?, ?> map)) {
            log.warn("DOM 标号脚本返回值非 Map: {}", raw);
            return new IndexedSnapshot(List.of(), 0, new int[]{0, 0}, false);
        }
        try {
            String json = objectMapper.writeValueAsString(map);
            var tree = objectMapper.readTree(json);
            List<IndexedElement> elements = objectMapper.convertValue(
                    tree.get("elements"),
                    new TypeReference<List<IndexedElement>>() {}
            );
            int total = tree.get("total").asInt();
            var viewportNode = tree.get("viewport");
            int[] viewport = {viewportNode.get(0).asInt(), viewportNode.get(1).asInt()};
            boolean truncated = tree.get("truncated").asBoolean();
            return new IndexedSnapshot(elements, total, viewport, truncated);
        } catch (Exception e) {
            log.error("解析 DOM 标号结果失败", e);
            return new IndexedSnapshot(List.of(), 0, new int[]{0, 0}, false);
        }
    }
}
```

- [ ] **Step 5: 运行测试通过**

```bash
mvn test -Dtest=InteractiveElementIndexer_标号测试
```
Expected: PASS（3 个测试）

- [ ] **Step 6: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer.java src/test/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer_标号测试.java src/test/resources/fixtures/browser/sample-interactive.html
git commit -m "feat(browser): InteractiveElementIndexer 加载 JS 脚本扫描标号"
```

---

## Task 4: PlaywrightPageWrapper 扩展

**Files:**
- Modify: `src/main/java/com/lifepilot/meta/infra/browser/PlaywrightPageWrapper.java`

- [ ] **Step 1: 新增 indexInteractiveElements / clickByIndex / inputByIndex / hoverByIndex**

在 `PlaywrightPageWrapper` 类中添加以下方法（字段补 `InteractiveElementIndexer indexer` 通过构造器注入；如原构造器签名不便改，可用 setter 或把 indexer 作为调用参数传入）：

```java
/**
 * 扫描当前页面可交互元素并注入编号。
 */
public IndexedSnapshot indexInteractiveElements(InteractiveElementIndexer indexer,
                                                 boolean injectLabels,
                                                 int maxElements) {
    humanDelay();
    return indexer.index(page, injectLabels, maxElements);
}

/**
 * 点击 index 对应元素（前置条件：最近一次 snapshot 已注入 data-zhiwei-idx）。
 */
public void clickByIndex(int index) {
    humanDelay();
    String selector = "[data-zhiwei-idx='" + index + "']";
    page.click(selector);
}

/**
 * 填入 index 对应输入框。
 */
public void inputByIndex(int index, String value) {
    humanDelay();
    String selector = "[data-zhiwei-idx='" + index + "']";
    page.fill(selector, value);
}

/**
 * 悬停 index 对应元素。
 */
public void hoverByIndex(int index) {
    humanDelay();
    String selector = "[data-zhiwei-idx='" + index + "']";
    page.hover(selector);
}
```

- [ ] **Step 2: mvn compile 验证**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/PlaywrightPageWrapper.java
git commit -m "feat(browser): PageWrapper 新增 index 路径点击/输入/悬停"
```

---

## Task 5: BrowserSnapshotToolExecutor

**Files:**
- Create: `src/main/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor.java`
- Create: `src/test/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor_快照测试.java`
- Modify: `src/main/java/com/lifepilot/meta/config/MetaProperties.java`（新增 `Browser.Snapshot` 配置）
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 新增配置属性**

在 `MetaProperties.Browser` 内部类中新增：

```java
@NestedConfigurationProperty
private Snapshot snapshot = new Snapshot();

public Snapshot getSnapshot() { return snapshot; }
public void setSnapshot(Snapshot s) { this.snapshot = s; }

public static class Snapshot {
    /** 单次 snapshot 最大返回元素数。 */
    private int maxElements = 200;
    /** true 则默认只截 viewport；false 截全页。 */
    private boolean viewportOnly = true;
    /** 是否叠加视觉编号标签（桌面 headless=false 场景建议 true）。 */
    private boolean injectLabels = false;

    // getter / setter 省略
}
```

`application.yml` 的 `zhiwei.meta.infra.browser` 节点下追加：
```yaml
snapshot:
  max-elements: 200
  viewport-only: true
  inject-labels: false
```

- [ ] **Step 2: 写 BrowserSnapshotToolExecutor 失败测试**

```java
package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrowserSnapshotToolExecutor_快照测试 {

    @Mock BrowserSessionManager sessionManager;
    @Mock PlaywrightPageWrapper page;
    @Mock InteractiveElementIndexer indexer;

    private MetaProperties properties;

    @org.junit.jupiter.api.BeforeEach
    void init() {
        properties = new MetaProperties();
        properties.getInfra().getBrowser().getSnapshot().setMaxElements(200);
    }

    @Test
    void 成功返回_screenshot_和_elements() {
        when(sessionManager.getOrCreatePage("task-x")).thenReturn(page);
        var elements = List.of(new IndexedElement(0, "a", "", "首页", "", "home", "",
                new int[]{0, 0, 50, 20}));
        when(page.indexInteractiveElements(eq(indexer), eq(false), eq(200)))
                .thenReturn(new IndexedSnapshot(elements, 1, new int[]{1920, 1080}, false));
        when(page.screenshotDataUri(false)).thenReturn("data:image/png;base64,AAAA");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("示例");

        var exec = new BrowserSnapshotToolExecutor(sessionManager, indexer, properties);
        ToolResult result = exec.execute(ToolInput.of(Map.of("sessionId", "task-x")));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data).containsKey("screenshotDataUri");
        assertThat(data).containsKey("elements");
        assertThat(data.get("total")).isEqualTo(1);
        assertThat(data.get("truncated")).isEqualTo(false);
    }

    @Test
    void sessionManager_不可用_返回错误() {
        var exec = new BrowserSnapshotToolExecutor(null, indexer, properties);
        ToolResult result = exec.execute(ToolInput.of(Map.of()));
        assertThat(result.isSuccess()).isFalse();
    }
}
```

- [ ] **Step 3: 实现 BrowserSnapshotToolExecutor**

```java
package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器页面快照工具 — 一次性返回截图 + 可交互元素标号表。
 *
 * <p>LLM 调用此 action 后，后续 click/input/hover 可用 index 精准定位，
 * 避免猜 CSS 选择器。截图通过 screenshotDataUri 字段自动挂 vision 输入
 * （依赖 Phase 0 的 useChat 钩子）。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class BrowserSnapshotToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserSnapshotToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;
    private final InteractiveElementIndexer indexer;
    private final MetaProperties.Browser.Snapshot config;

    public BrowserSnapshotToolExecutor(@Nullable BrowserSessionManager sessionManager,
                                       InteractiveElementIndexer indexer,
                                       MetaProperties properties) {
        this.sessionManager = sessionManager;
        this.indexer = indexer;
        this.config = properties.getInfra().getBrowser().getSnapshot();
    }

    public ToolResult execute(ToolInput input) {
        if (sessionManager == null) {
            return ToolResult.error("浏览器功能未配置");
        }
        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
        boolean injectLabels = input.getOptionalParam("injectLabels", Boolean.class)
                .orElse(config.isInjectLabels());
        int maxElements = input.getOptionalParam("maxElements", Integer.class)
                .orElse(config.getMaxElements());
        boolean viewportOnly = input.getOptionalParam("viewportOnly", Boolean.class)
                .orElse(config.isViewportOnly());

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            IndexedSnapshot snapshot = page.indexInteractiveElements(indexer, injectLabels, maxElements);
            String dataUri = page.screenshotDataUri(!viewportOnly);

            var result = new LinkedHashMap<String, Object>();
            result.put("screenshotDataUri", dataUri);
            result.put("elements", snapshot.elements());
            result.put("total", snapshot.total());
            result.put("truncated", snapshot.truncated());
            result.put("viewport", snapshot.viewport());
            result.put("url", page.url());
            result.put("title", page.title());
            log.debug("浏览器快照完成: sessionId={}, elements={}, total={}", sessionId,
                    snapshot.elements().size(), snapshot.total());
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("浏览器快照失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器快照失败: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试通过**

```bash
mvn test -Dtest=BrowserSnapshotToolExecutor_快照测试
```
Expected: PASS

- [ ] **Step 5: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor.java src/test/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor_快照测试.java src/main/java/com/lifepilot/meta/config/MetaProperties.java src/main/resources/application.yml
git commit -m "feat(browser): 新增 browser.snapshot executor 和配置"
```

---

## Task 6: 注册 snapshot action 到 Dispatcher 和 Provider

**Files:**
- Modify: `src/main/java/com/lifepilot/meta/infra/browser/BrowserActionDispatchExecutor.java`
- Modify: `src/main/java/com/lifepilot/meta/infra/BrowserToolProvider.java`（或 `BrowserToolProvider` 真实路径，按 `BrowserAutoConfiguration` 调用处查）

- [ ] **Step 1: Dispatcher 构造器新增 snapshot**

在 `BrowserActionDispatchExecutor` 构造器：
1. 构造参数追加 `InteractiveElementIndexer indexer`
2. 创建 executor：
```java
var snapshotExecutor = new BrowserSnapshotToolExecutor(browserSessionManager, indexer, properties);
```
3. 注册：
```java
register("snapshot", RiskLevel.LOW, browserSessionSemantics, snapshotExecutor::execute);
```

- [ ] **Step 2: 装配类更新 Bean**

在 `BrowserAutoConfiguration`（或同等 @Configuration）中：
1. 确保 `ObjectMapper` Bean 可用
2. 新增 `InteractiveElementIndexer` Bean
3. 把 indexer 传给 `BrowserActionDispatchExecutor` 构造

- [ ] **Step 3: Provider schema 新增 snapshot**

在 browser 工具 schema（`BrowserToolProvider` 或等价位置）的 `action` 枚举中加 `snapshot`，并补参数 schema：
- `injectLabels`: bool，默认 false
- `maxElements`: int，默认 200
- `viewportOnly`: bool，默认 true

description 内嵌在 schema 里（按记忆「SKILL.md 不要引用 docs 目录」原则，必要信息直接写 schema）：
> "扫描当前页面可交互元素并返回截图 + 编号表（elements[].{index,tag,role,text,bbox}）。后续 click/input/hover 应优先用 index 而非选择器。"

- [ ] **Step 4: mvn compile + 启动上下文测试**

```bash
mvn compile -q
mvn test -Dtest='Browser*Test' -q
```
Expected: 全部 PASS

- [ ] **Step 5: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/BrowserActionDispatchExecutor.java src/main/java/com/lifepilot/meta/infra/BrowserToolProvider.java src/main/java/com/lifepilot/meta/infra/browser/BrowserAutoConfiguration.java
git commit -m "feat(browser): 注册 snapshot action 到 dispatcher 和 schema"
```

---

## Task 7: click/input/hover 加 index 参数

**Files:**
- Modify: `BrowserClickToolExecutor.java`
- Modify: `BrowserInputToolExecutor.java`
- Modify: `BrowserHoverToolExecutor.java`
- Modify: `BrowserToolProvider` schema（click/input/hover 的参数新增 `index: int?`）
- Create: 对应三个 executor 的 `_index路径测试.java`

- [ ] **Step 1: 写 BrowserClickToolExecutor_index路径测试**

```java
package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrowserClickToolExecutor_index路径测试 {

    @Mock BrowserSessionManager sessionManager;
    @Mock PlaywrightPageWrapper page;

    @Test
    void 传_index_走_clickByIndex_不调用_click() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.url()).thenReturn("x");
        when(page.title()).thenReturn("y");

        var exec = new BrowserClickToolExecutor(sessionManager);
        ToolResult r = exec.execute(ToolInput.of(Map.of("index", 12)));

        assertThat(r.isSuccess()).isTrue();
        verify(page).clickByIndex(12);
        verify(page, never()).click(any());
    }

    @Test
    void 传_selector_走_click_不调用_clickByIndex() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.url()).thenReturn("x");
        when(page.title()).thenReturn("y");

        var exec = new BrowserClickToolExecutor(sessionManager);
        ToolResult r = exec.execute(ToolInput.of(Map.of("selector", "#btn")));

        assertThat(r.isSuccess()).isTrue();
        verify(page).click("#btn");
        verify(page, never()).clickByIndex(anyInt());
    }

    @Test
    void 同时传_index_和_selector_返回参数错误() {
        var exec = new BrowserClickToolExecutor(sessionManager);
        ToolResult r = exec.execute(ToolInput.of(Map.of("index", 1, "selector", "#x")));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).contains("二选一");
    }

    @Test
    void 都不传_返回参数错误() {
        var exec = new BrowserClickToolExecutor(sessionManager);
        ToolResult r = exec.execute(ToolInput.of(Map.of()));
        assertThat(r.isSuccess()).isFalse();
    }
}
```

- [ ] **Step 2: 改造 BrowserClickToolExecutor.execute**

```java
public ToolResult execute(ToolInput input) {
    var maybeIndex = input.getOptionalParam("index", Integer.class);
    var maybeSelector = input.getOptionalParam("selector", String.class);

    if (maybeIndex.isPresent() && maybeSelector.isPresent()) {
        return ToolResult.error("index 和 selector 只能二选一");
    }
    if (maybeIndex.isEmpty() && maybeSelector.isEmpty()) {
        return ToolResult.error("缺少必需参数: index 或 selector");
    }

    String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
    try {
        var page = sessionManager.getOrCreatePage(sessionId);
        if (maybeIndex.isPresent()) {
            int idx = maybeIndex.get();
            page.clickByIndex(idx);
            log.debug("浏览器点击完成(index): index={}, sessionId={}", idx, sessionId);
            return ToolResult.success(Map.of(
                    "clicked", "index=" + idx,
                    "url", page.url(),
                    "title", page.title()
            ));
        } else {
            String selector = maybeSelector.get();
            page.click(selector);
            log.debug("浏览器点击完成(selector): selector={}, sessionId={}", selector, sessionId);
            return ToolResult.success(Map.of(
                    "clicked", selector,
                    "url", page.url(),
                    "title", page.title()
            ));
        }
    } catch (Exception e) {
        log.error("浏览器点击失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        return ToolResult.error("浏览器点击失败: " + e.getMessage());
    }
}
```

- [ ] **Step 3: 运行 click 测试通过**

```bash
mvn test -Dtest=BrowserClickToolExecutor_index路径测试
```
Expected: PASS

- [ ] **Step 4: 同款改造 BrowserInputToolExecutor**

`input` 的必需组合：`(index, value)` 或 `(selector, value)`，value 始终必需。实现模式同 click。

写对应测试 `BrowserInputToolExecutor_index路径测试`（覆盖 index 路径、selector 路径、冲突、缺 value 四场景）。

- [ ] **Step 5: 同款改造 BrowserHoverToolExecutor**

`hover` 参数结构同 click（index 或 selector 二选一，无 value）。写对应测试。

- [ ] **Step 6: Provider schema 更新**

在 click/input/hover 的参数 schema 中新增可选字段：
```
index?: integer  # 最近一次 snapshot 返回的元素索引，与 selector 二选一
```

保留原 selector 为可选。description 说明"若已调用 browser.snapshot，优先用 index 而非 selector"。

- [ ] **Step 7: 运行所有 click/input/hover 测试通过**

```bash
mvn test -Dtest='Browser{Click,Input,Hover}*Test,BrowserClickToolExecutor_*,BrowserInputToolExecutor_*,BrowserHoverToolExecutor_*'
```
Expected: PASS

- [ ] **Step 8: commit**

```bash
git add src/main/java/com/lifepilot/meta/infra/browser/BrowserClickToolExecutor.java src/main/java/com/lifepilot/meta/infra/browser/BrowserInputToolExecutor.java src/main/java/com/lifepilot/meta/infra/browser/BrowserHoverToolExecutor.java src/main/java/com/lifepilot/meta/infra/BrowserToolProvider.java src/test/java/com/lifepilot/meta/infra/browser/Browser{Click,Input,Hover}ToolExecutor_index路径测试.java
git commit -m "feat(browser): click/input/hover 支持 index 参数"
```

---

## Task 8: browser-automation SKILL 更新

**Files:**
- Modify: `src/main/resources/skills/browser-automation/SKILL.md`

- [ ] **Step 1: 重写工作流章节**

把现有 "1. 导航并获取内容 / 2. 截图确认状态 / 3. 交互操作 / 4. 提取结构化数据 / 5. 保存结果 / 6. 关闭会话" 改为：

```markdown
### 推荐工作流

1. **navigate** 到目标 URL（复用 sessionId）
2. **snapshot** 一次性获取截图 + 可交互元素标号（elements[].{index, tag, role, text, bbox}）
3. **click / input / hover** 用 index 定位（首选），或 selector（fallback）
4. 页面可能变化（导航、弹窗）后**重新 snapshot**
5. 抓数据用 evaluate；保存用 file.write；结束调 close

### 选择器 fallback 链

- 首选：`browser.snapshot` → `click(index=N)`
- 若 index 对应元素报 stale / not found → 再次 snapshot 对比 elements 是否变化
- 同一目标连续两次 index 失败 → 回落到 selector（id > data-testid > CSS）
- selector 也两次失败 → 换策略（browser → web.fetch → web.search）

### 登录墙识别

导航后 title/url 含 login/signin/auth 或截图明显是登录页时，调 `browser.requestHumanTakeover`（Phase 2 引入）并说明原因。在 Phase 2 合入前，遇此场景应提示用户切 CDP 模式预先登录。
```

（不写具体话术模板，流程约束让 LLM 自己组织措辞）

- [ ] **Step 2: 更新 action 列表表格**

在 `完整 action 列表` 表格中插入 `snapshot` 行，排在 `screenshot` 之前：

```markdown
| `snapshot` | 截图 + 可交互元素标号（推荐首选） | `injectLabels`, `maxElements`, `viewportOnly` |
```

`click` / `input` / `hover` 行的关键参数列补 `index`：
```markdown
| `click` | 点击元素 | `index` 或 `selector` |
| `input` | 输入文本 | `index` 或 `selector`, `value` |
| `hover` | 鼠标悬停 | `index` 或 `selector` |
```

- [ ] **Step 3: 更新 frontmatter version**

```yaml
version: "3.0.0"
```

- [ ] **Step 4: 通读 skill 文档无歧义**

无代码操作，人工/AI 通读。

- [ ] **Step 5: commit**

```bash
git add src/main/resources/skills/browser-automation/SKILL.md
git commit -m "docs(skill): browser-automation 改用 snapshot→index 首选路径"
```

---

## Task 9: mini-benchmark 骨架（@Disabled 默认不跑）

**Files:**
- Create: `src/test/java/com/lifepilot/meta/infra/browser/BrowserE2eBenchmark.java`

- [ ] **Step 1: 建 benchmark 骨架**

```java
package com.lifepilot.meta.infra.browser;

import org.junit.jupiter.api.*;

/**
 * 浏览器工具端到端基准。默认 {@code @Disabled}，手动执行以评估成功率：
 *
 * <pre>
 * mvn test -Dtest=BrowserE2eBenchmark -DexcludedGroups= -Dgroups=benchmark
 * </pre>
 *
 * <p>10 个任务三类：开放搜索 3、填表 3、跨站导航 2、滚动加载 2。
 * 目标成功率 ≥ 80%（Phase 0 基线约 40-60%）。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Disabled("benchmark，手动执行")
@Tag("benchmark")
class BrowserE2eBenchmark {

    @Test void 任务1_开放搜索_google_knn() { /* TODO 执行时填实际步骤 */ }
    @Test void 任务2_开放搜索_bing_知微() {}
    @Test void 任务3_开放搜索_duckduckgo() {}
    @Test void 任务4_填表_登录示例站点() {}
    @Test void 任务5_填表_搜索表单() {}
    @Test void 任务6_填表_多步注册() {}
    @Test void 任务7_跨站导航_github_到_issue() {}
    @Test void 任务8_跨站导航_stackoverflow() {}
    @Test void 任务9_滚动加载_hn_首页() {}
    @Test void 任务10_滚动加载_twitter_时间线() {}
}
```

**说明**：具体任务步骤在执行 benchmark 时补。本 task 只建骨架，保证有一个可运行的对比基准位置。

- [ ] **Step 2: commit**

```bash
git add src/test/java/com/lifepilot/meta/infra/browser/BrowserE2eBenchmark.java
git commit -m "test(browser): mini-benchmark 骨架"
```

---

## Task 10: Phase 1 合并验收

- [ ] **Step 1: 全量编译 + 单测**

```bash
mvn clean compile
mvn test -q
```
Expected: BUILD SUCCESS + 全测试绿

- [ ] **Step 2: 启动应用手测端到端**

```bash
mvn spring-boot:run
```
前端发自然对话（按记忆「冒烟测试用自然对话」，**不指定工具名**，看 AI 是否走对路径）：

1. "帮我打开 github.com 看看页面上有哪些按钮"
   - 预期：AI 调 navigate → snapshot；对话卡展示 elements 列表；AI 能说"看到了顶部 Sign in、Sign up 按钮，index 分别是 N1、N2"
2. "在这个页面搜索 'zhiwei'"
   - 预期：AI 通过 index 找到搜索输入框并输入；snapshot/click 接力完成

若 AI 选错工具或路径，看 Skill 是否还需调整。

- [ ] **Step 3: 推分支 + 开 PR**

```bash
git push -u origin feature/browser-phase1-dom-indexing
gh pr create --base develop --title "feat(browser): Phase 1 DOM 标号 + vision 闭环" --body "$(cat <<'EOF'
## 摘要

按 roadmap（docs/superpowers/plans/2026-04-24-browser-capability-roadmap.md）Phase 1 实施：
- 新增 `browser.snapshot` action，一次性返回截图 + 可交互元素标号表
- `click` / `input` / `hover` 支持 `index` 参数（与 selector 二选一）
- 新增 `InteractiveElementIndexer` + JS 注入脚本 `interactive-elements.js`
- 新增 `IndexedElement` / `IndexedSnapshot` record
- 更新 `browser-automation` skill 首选路径为 snapshot → index
- 新增 `BrowserE2eBenchmark` 骨架

## 验证

- [x] `mvn test` 全绿
- [x] `InteractiveElementIndexer_标号测试` 真实 Playwright 集成通过
- [x] 手测：自然对话触发 AI 正确使用 snapshot → index 路径
- [ ] benchmark 目标 ≥ 80% 成功率（需手动执行 `-Dgroups=benchmark`）

## 前置

- Phase 0 `feature/browser-phase0-cleanup` 已合入 develop

## 后续

- Phase 2 `feature/browser-phase2-ux` 基于本 PR 合入后开工

EOF
)"
```

- [ ] **Step 4: PR 合入 develop**

等 review 通过后合并。

---

## 风险与应对

| 风险 | 应对 |
|---|---|
| JS 脚本在某些网站被 CSP 拦截 | `page.evaluate` 原生注入不受 CSP 影响；如果失败降级为仅返回 screenshot + 无 elements |
| `data-zhiwei-idx` 属性污染页面触发反爬 | 在 click/input 完成后清理该属性（可选优化，Phase 1 先不做） |
| 页面 DOM 变化导致 index stale | 明确错误信息 `elementStale`，Skill 引导 LLM 重新 snapshot |
| shadow DOM / iframe 内元素扫不到 | 已在 roadmap 记录为 Phase 1.1 / 1.2 后续优化 |
| Jackson 反序列化 `int[] bbox` 失败 | 测试覆盖 bbox 字段，如失败改为 `List<Integer>` |

---

## 执行顺序

Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10。Task 间有强依赖（2 产物被 3 用，4 产物被 5 用，5/6/7 产物被 8/10 用），不宜并行。
