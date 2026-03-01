# LifePilot Web UI 设计优化文档

> 基于 OpenClaw、ChatGPT、Claude 等竞品分析，以及 UI-UX-Pro-Max 等专业设计系统的最佳实践

**文档版本**: v1.0  
**创建日期**: 2025-01-27  
**适用项目**: lifepilot-web (Vue 3 + Tailwind CSS + shadcn-vue)

---

## 📋 目录

1. [当前UI设计分析](#当前ui设计分析)
2. [竞品对比分析](#竞品对比分析)
3. [核心问题识别](#核心问题识别)
4. [优化建议](#优化建议)
5. [实施优先级](#实施优先级)
6. [设计系统规范](#设计系统规范)

---

## 当前UI设计分析

### 技术栈
- **框架**: Vue 3 + Vite
- **UI库**: shadcn-vue (New York 风格)
- **样式**: Tailwind CSS + CSS Variables
- **配色**: Neutral 基础色系
- **组件库**: 自定义 A2UI 组件系统

### 当前设计特点

#### ✅ 优点
1. **设计系统基础完善**: 使用 shadcn-vue，具备完整的颜色变量系统
2. **响应式布局**: 采用 Flexbox 布局，支持自适应
3. **暗色模式支持**: 完整的 dark mode 变量定义
4. **组件化程度高**: 消息气泡、输入框等组件封装良好

#### ⚠️ 待优化点
1. **间距系统不统一**: 各组件使用 `px-4 py-3`、`p-2` 等不一致的间距
2. **最大宽度限制**: 消息区域使用 `max-w-3xl`，但整体布局缺少统一的最大宽度约束
3. **视觉层次不够清晰**: 缺少明确的视觉焦点和层级关系
4. **交互反馈不足**: hover 状态、加载状态、错误状态反馈不够丰富
5. **字体系统未优化**: 未定义完整的字体层级（标题、正文、辅助文字等）

---

## 竞品对比分析

### ChatGPT 界面特点
- **最大宽度**: 768px (移动端) / 1024px (桌面端)
- **消息间距**: 24px 垂直间距
- **圆角**: 消息气泡 16px，输入框 24px
- **字体**: 15px 正文，行高 1.5
- **颜色对比度**: 严格遵循 WCAG AA 标准
- **动画**: 平滑的淡入淡出，无晃眼效果

### Claude 界面特点
- **最大宽度**: 1200px
- **消息布局**: 左右对齐，用户消息右对齐，AI消息左对齐
- **头像设计**: 圆形头像，8px 间距
- **输入框**: 多行文本，自动扩展，最大高度 200px
- **加载状态**: 优雅的骨架屏或打字机效果

### OpenClaw 设计规范
- **去"AI味"**: 使用 SVG 图标替代 emoji
- **统一间距**: 8px 基础间距系统（4px、8px、12px、16px、24px、32px）
- **配色方案**: 行业专属配色（SaaS蓝、金融深黑+金色等）
- **字体搭配**: Inter + JetBrains Mono（代码字体）
- **响应式断点**: 移动端优先，桌面端增强

---

## 核心问题识别

### 🔴 高优先级问题

#### 1. 间距系统不统一
**问题描述**:
- `MessageBubble.vue`: `px-4 py-3` (16px/12px)
- `ChatInput.vue`: `p-4` (16px)
- `Sidebar.vue`: `p-4`, `p-2` (16px/8px)
- `ChatView.vue`: `py-4` (16px)

**影响**: 视觉不协调，缺乏专业感

#### 2. 最大宽度未统一
**问题描述**:
- 消息区域: `max-w-3xl` (768px)
- 输入框容器: `max-w-3xl` (768px)
- 但整体布局缺少统一的最大宽度约束

**影响**: 大屏幕上内容过于分散，阅读体验差

#### 3. 字体系统未定义
**问题描述**:
- 所有文字使用默认 `text-sm` (14px)
- 缺少标题层级（h1/h2/h3）
- 缺少辅助文字样式定义

**影响**: 信息层次不清晰，可读性差

#### 4. 交互反馈不足
**问题描述**:
- 按钮 hover 状态简单
- 缺少 focus 状态样式
- 加载状态仅文字提示，无视觉反馈
- 错误状态样式单一

**影响**: 用户体验不够流畅

### 🟡 中优先级问题

#### 5. 颜色对比度未验证
- 当前使用 Neutral 色系，但未验证是否符合 WCAG AA 标准
- `muted-foreground` 可能对比度不足

#### 6. 圆角系统不统一
- 消息气泡: `rounded-lg` (8px)
- 输入框: `rounded-lg` (8px)
- 按钮: `rounded-lg` (8px)
- 但缺少更细致的圆角层级定义

#### 7. 阴影系统缺失
- 当前设计扁平化，缺少阴影层次
- 卡片、弹出层等缺少 elevation 定义

### 🟢 低优先级问题

#### 8. 动画效果不足
- 缺少页面过渡动画
- 消息出现动画简单
- 输入框聚焦动画未优化

#### 9. 空状态设计
- 空状态仅文字提示，缺少图标和引导

#### 10. 响应式优化
- 移动端适配需要进一步优化
- 侧边栏在小屏幕上可能需要折叠

---

## 优化建议

### 1. 建立统一的间距系统

#### 建议方案
```css
/* 在 main.css 中定义间距系统 */
:root {
  --spacing-xs: 4px;    /* 0.25rem */
  --spacing-sm: 8px;    /* 0.5rem */
  --spacing-md: 16px;   /* 1rem */
  --spacing-lg: 24px;   /* 1.5rem */
  --spacing-xl: 32px;   /* 2rem */
  --spacing-2xl: 48px;  /* 3rem */
}
```

#### 应用规则
- **组件内间距**: 使用 `p-md` (16px) 或 `px-md py-sm` (16px/8px)
- **组件间距**: 使用 `gap-md` (16px) 或 `gap-lg` (24px)
- **页面边距**: 使用 `px-lg` (24px) 或 `px-xl` (32px)
- **消息间距**: 统一使用 `py-lg` (24px)

#### 具体修改
- `MessageBubble.vue`: `px-4 py-3` → `px-md py-lg`
- `ChatInput.vue`: `p-4` → `p-md`
- `Sidebar.vue`: 统一使用间距系统
- `ChatView.vue`: `py-4` → `py-lg`

### 2. 统一最大宽度约束

#### 建议方案
```css
:root {
  --max-width-content: 768px;    /* 消息内容区域 */
  --max-width-container: 1200px;  /* 整体容器 */
}
```

#### 布局结构
```vue
<!-- ChatView.vue -->
<div class="flex flex-col h-full">
  <div class="flex-1 overflow-y-auto">
    <div class="max-w-[var(--max-width-content)] mx-auto px-lg py-lg">
      <!-- 消息列表 -->
    </div>
  </div>
  <div class="border-t border-border bg-card">
    <div class="max-w-[var(--max-width-content)] mx-auto px-lg py-md">
      <!-- 输入框 -->
    </div>
  </div>
</div>
```

### 3. 建立完整的字体系统

#### 建议方案
```css
:root {
  /* 字体大小 */
  --font-size-xs: 12px;    /* 0.75rem - 辅助文字 */
  --font-size-sm: 14px;    /* 0.875rem - 正文小 */
  --font-size-base: 16px;  /* 1rem - 正文 */
  --font-size-lg: 18px;    /* 1.125rem - 小标题 */
  --font-size-xl: 20px;    /* 1.25rem - 标题 */
  --font-size-2xl: 24px;   /* 1.5rem - 大标题 */
  
  /* 行高 */
  --line-height-tight: 1.25;
  --line-height-normal: 1.5;
  --line-height-relaxed: 1.75;
  
  /* 字重 */
  --font-weight-normal: 400;
  --font-weight-medium: 500;
  --font-weight-semibold: 600;
  --font-weight-bold: 700;
}
```

#### 应用示例
```vue
<!-- 标题 -->
<h1 class="text-2xl font-semibold leading-tight">LifePilot</h1>

<!-- 正文 -->
<p class="text-base leading-normal">消息内容</p>

<!-- 辅助文字 -->
<span class="text-xs text-muted-foreground">时间戳</span>
```

### 4. 增强交互反馈

#### 按钮状态优化
```vue
<button
  class="
    rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground
    transition-all duration-200
    hover:bg-primary/90 hover:shadow-md
    focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2
    active:scale-[0.98]
    disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-primary
  "
>
  发送
</button>
```

#### 加载状态优化
```vue
<!-- 流式生成指示器 -->
<div v-if="isStreaming" class="flex items-center gap-2 px-4 py-2">
  <div class="flex gap-1">
    <div class="w-2 h-2 rounded-full bg-primary animate-pulse" style="animation-delay: 0ms"></div>
    <div class="w-2 h-2 rounded-full bg-primary animate-pulse" style="animation-delay: 150ms"></div>
    <div class="w-2 h-2 rounded-full bg-primary animate-pulse" style="animation-delay: 300ms"></div>
  </div>
  <span class="text-xs text-muted-foreground">正在生成...</span>
  <button
    class="ml-auto text-xs text-muted-foreground hover:text-foreground underline"
    @click="abort"
  >
    停止
  </button>
</div>
```

### 5. 优化消息气泡设计

#### 参考 ChatGPT/Claude 设计
```vue
<template>
  <div 
    class="flex gap-3 px-lg py-lg"
    :class="message.role === 'user' ? 'justify-end' : ''"
  >
    <!-- AI 头像 -->
    <div
      v-if="message.role === 'assistant'"
      class="shrink-0 w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center
             text-xs font-semibold text-primary border border-primary/20"
    >
      AI
    </div>

    <!-- 消息内容 -->
    <div
      class="max-w-[85%] rounded-2xl px-4 py-3 shadow-sm"
      :class="message.role === 'user'
        ? 'bg-primary text-primary-foreground rounded-br-sm'
        : 'bg-muted text-foreground rounded-bl-sm'"
    >
      <!-- 内容 -->
    </div>

    <!-- 用户头像 -->
    <div
      v-if="message.role === 'user'"
      class="shrink-0 w-8 h-8 rounded-full bg-secondary flex items-center justify-center
             text-xs font-semibold border border-border"
    >
      你
    </div>
  </div>
</template>
```

**优化点**:
- 增大圆角 (`rounded-2xl` = 16px)
- 消息气泡根据角色调整圆角方向（用户消息右下角小圆角，AI消息左下角小圆角）
- 添加轻微阴影 (`shadow-sm`)
- 头像添加边框增强层次感

### 6. 优化输入框设计

#### 参考 Claude 设计
```vue
<template>
  <div class="border-t border-border bg-card/50 backdrop-blur-sm">
    <div class="max-w-[var(--max-width-content)] mx-auto px-lg py-md">
      <div class="flex gap-2 items-end">
        <div class="flex-1 relative">
          <textarea
            v-model="input"
            placeholder="输入消息... (Shift+Enter 换行)"
            rows="1"
            class="
              w-full resize-none rounded-2xl border border-input bg-background
              px-4 py-3 text-base leading-normal
              placeholder:text-muted-foreground
              focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent
              disabled:opacity-50
              min-h-[52px] max-h-[200px]
              transition-all duration-200
            "
            @keydown="handleKeydown"
            @input="autoResize"
          />
          <!-- 字符计数（可选） -->
          <div v-if="input.length > 0" class="absolute bottom-2 right-3 text-xs text-muted-foreground">
            {{ input.length }}
          </div>
        </div>
        <button
          :disabled="disabled || !input.trim()"
          class="
            rounded-full w-10 h-10 flex items-center justify-center
            bg-primary text-primary-foreground
            hover:bg-primary/90 hover:shadow-md
            focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2
            disabled:opacity-50 disabled:cursor-not-allowed
            transition-all duration-200
            shrink-0
          "
          @click="submit"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>
```

**优化点**:
- 输入框圆角增大 (`rounded-2xl`)
- 发送按钮改为圆形图标按钮
- 添加背景模糊效果 (`backdrop-blur-sm`)
- 自动调整高度功能
- 字符计数（可选）

### 7. 优化侧边栏设计

#### 参考 ChatGPT 设计
```vue
<template>
  <aside class="w-[var(--sidebar-width)] border-r border-border bg-card/50 backdrop-blur-sm flex flex-col h-full">
    <!-- 顶部 -->
    <div class="p-lg border-b border-border flex items-center justify-between">
      <h1 class="text-xl font-semibold text-foreground">LifePilot</h1>
      <button
        class="
          w-8 h-8 rounded-lg flex items-center justify-center
          text-muted-foreground hover:text-foreground hover:bg-accent
          transition-colors
        "
        title="新建对话"
        @click="newChat"
      >
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto p-sm">
      <div
        v-for="session in chatStore.sessions"
        :key="session.id"
        class="
          group flex items-center gap-2 px-3 py-2.5 rounded-lg
          text-sm cursor-pointer transition-all duration-200
          mb-1
        "
        :class="chatStore.activeSessionId === session.id
          ? 'bg-accent text-accent-foreground shadow-sm'
          : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
        @click="selectSession(session.id)"
      >
        <svg class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
        </svg>
        <span class="flex-1 truncate font-medium">{{ session.title || '新对话' }}</span>
        <button
          class="
            opacity-0 group-hover:opacity-100
            w-5 h-5 rounded flex items-center justify-center
            text-muted-foreground hover:text-destructive hover:bg-destructive/10
            transition-all duration-200 shrink-0
          "
          title="删除会话"
          @click.stop="handleDelete(session.id)"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
    </div>

    <!-- 底部导航 -->
    <div class="p-sm border-t border-border space-y-0.5">
      <router-link
        v-for="nav in navItems"
        :key="nav.path"
        :to="nav.path"
        class="
          flex items-center gap-2 px-3 py-2.5 rounded-lg
          text-sm font-medium transition-all duration-200
        "
        :class="route.path === nav.path
          ? 'bg-accent text-accent-foreground shadow-sm'
          : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'"
      >
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <!-- 图标路径 -->
        </svg>
        {{ nav.label }}
      </router-link>
    </div>
  </aside>
</template>
```

**优化点**:
- 使用 SVG 图标替代文字符号
- 会话项添加图标
- 优化 hover 和 active 状态
- 添加背景模糊效果
- 统一间距和圆角

### 8. 优化空状态设计

```vue
<template>
  <div
    v-if="chatStore.messages.length === 0 && !isStreaming"
    class="flex flex-col items-center justify-center h-full text-center px-lg"
  >
    <div class="w-16 h-16 rounded-full bg-primary/10 flex items-center justify-center mb-lg">
      <svg class="w-8 h-8 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
      </svg>
    </div>
    <h2 class="text-xl font-semibold text-foreground mb-2">开始新对话</h2>
    <p class="text-sm text-muted-foreground max-w-md">
      输入你的问题，LifePilot 将帮助你完成任务
    </p>
    <div class="mt-lg flex flex-wrap gap-2 justify-center">
      <button
        v-for="suggestion in suggestions"
        :key="suggestion"
        class="
          px-4 py-2 rounded-lg text-sm
          bg-muted text-muted-foreground
          hover:bg-accent hover:text-accent-foreground
          transition-colors
        "
        @click="handleSuggestion(suggestion)"
      >
        {{ suggestion }}
      </button>
    </div>
  </div>
</template>
```

### 9. 颜色对比度验证

#### 建议使用工具
- [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/)
- [Coolors Contrast Checker](https://coolors.co/contrast-checker)

#### 需要验证的颜色对
- `text-foreground` / `bg-background`
- `text-muted-foreground` / `bg-background`
- `text-primary-foreground` / `bg-primary`
- `text-accent-foreground` / `bg-accent`

#### 如果对比度不足，建议调整
```css
:root {
  --muted-foreground: hsl(240 3.8% 40%); /* 从 46.1% 降低到 40% 提高对比度 */
}

.dark {
  --muted-foreground: hsl(240 5% 70%); /* 从 64.9% 提高到 70% */
}
```

### 10. 添加阴影系统

```css
:root {
  --shadow-sm: 0 1px 2px 0 rgb(0 0 0 / 0.05);
  --shadow-md: 0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1);
  --shadow-lg: 0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1);
  --shadow-xl: 0 20px 25px -5px rgb(0 0 0 / 0.1), 0 8px 10px -6px rgb(0 0 0 / 0.1);
}

.dark {
  --shadow-sm: 0 1px 2px 0 rgb(0 0 0 / 0.3);
  --shadow-md: 0 4px 6px -1px rgb(0 0 0 / 0.4), 0 2px 4px -2px rgb(0 0 0 / 0.4);
  --shadow-lg: 0 10px 15px -3px rgb(0 0 0 / 0.5), 0 4px 6px -4px rgb(0 0 0 / 0.5);
  --shadow-xl: 0 20px 25px -5px rgb(0 0 0 / 0.6), 0 8px 10px -6px rgb(0 0 0 / 0.6);
}
```

---

## 实施优先级

### 🔴 Phase 1: 核心优化（1-2周）
1. ✅ 统一间距系统
2. ✅ 统一最大宽度约束
3. ✅ 建立字体系统
4. ✅ 优化消息气泡设计
5. ✅ 优化输入框设计

### 🟡 Phase 2: 体验增强（1周）
6. ✅ 增强交互反馈
7. ✅ 优化侧边栏设计
8. ✅ 优化空状态设计
9. ✅ 颜色对比度验证

### 🟢 Phase 3: 细节完善（1周）
10. ✅ 添加阴影系统
11. ✅ 优化动画效果
12. ✅ 响应式优化
13. ✅ 添加 SVG 图标库

---

## 设计系统规范

### 间距系统 (Spacing System)

#### 1. 间距刻度

- **基础单位**: 4px（0.25rem）
- **推荐刻度**:
  - `xs` = 4px
  - `sm` = 8px
  - `md` = 16px
  - `lg` = 24px
  - `xl` = 32px
  - `2xl` = 48px

与前文 CSS 变量保持一致：

```css
:root {
  --spacing-xs: 4px;    /* 0.25rem */
  --spacing-sm: 8px;    /* 0.5rem */
  --spacing-md: 16px;   /* 1rem */
  --spacing-lg: 24px;   /* 1.5rem */
  --spacing-xl: 32px;   /* 2rem */
  --spacing-2xl: 48px;  /* 3rem */
}
```

#### 2. 使用规则

- **页面级**:
  - 页面左右留白：`px-xl`（32px，桌面端），移动端 `px-md`（16px）
  - 页面顶部/底部：`py-lg`（24px）
- **区块级（Section/Card）**:
  - 区块与区块间距：`mt-xl`（32px）
  - 卡片内边距：`p-lg`（24px，内容型卡片）/ `p-md`（16px，控件型卡片）
- **组件级**:
  - 按钮内边距：`px-4 py-2`（16px / 8px）
  - 输入框内边距：`px-4 py-3`（16px / 12px）
  - 列表项上下间距：`py-2.5`（10px）或 `py-3`（12px）
- **布局间隙（gap）**:
  - 表单字段间距：`gap-md`（16px）
  - 卡片网格间距：`gap-lg`（24px）
  - 消息垂直间距：`py-lg`（24px）

#### 3. 消息区专用间距

- 消息与消息之间：24px
- 消息气泡内边距：左右 16px，上下 12px
- 头像与气泡之间：8px
- 空状态区顶部留白：占可视高度的 20%–25%

---

### 排版系统 (Typography System)

#### 1. 字体家族

- **中文主字体**: `system-ui, -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`
- **等宽字体（代码 / 技术内容）**: `"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`

通过 Tailwind + CSS 变量结合管理：

```css
:root {
  --font-sans: system-ui, -apple-system, BlinkMacSystemFont, "SF Pro Text",
    "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei",
    sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco,
    Consolas, "Liberation Mono", "Courier New", monospace;
}
```

#### 2. 字号与行高

与前文变量一致，结合具体使用场景整理为表：

- **Display / 页面大标题**:
  - 尺寸：`text-3xl`（约 28px–30px）
  - 场景：营销页、欢迎页（后续如有）
- **Page Title / 页面标题**:
  - 尺寸：`text-2xl` / `--font-size-2xl`（24px）
  - 行高：`leading-tight`
  - 使用：各 View 顶部标题（如 `ChatView`、`KnowledgeBaseView` 标题）
- **Section Title / 区块标题**:
  - 尺寸：`text-xl`（20px）
  - 行高：`leading-snug` 或 `leading-tight`
- **Body / 正文**:
  - 主体文字：`text-base`（16px），`leading-normal`
  - 次要文字：`text-sm`（14px），`leading-normal`
- **Meta / 辅助信息**:
  - 尺寸：`text-xs`（12px）
  - 使用：时间戳、标签、副提示

#### 3. 字重（Weight）

- 标题：`font-semibold`（600）
- 小标题 / 标签：`font-medium`（500）
- 正文：`font-normal`（400）
- 不使用 `font-bold`，除非极少数需要强烈强调的按钮或警告信息。

#### 4. 对齐与段落

- 聊天内容：默认左对齐
- 表单标签与说明：左对齐
- 按钮文本：居中
- 段落间距：正文段落间距 `mt-2`（8px），区块标题与内容间 `mt-3`–`mt-4`（12px–16px）

---

### 配色系统 (Color System)

#### 1. 色板结构

- **基础色组**:
  - `background` / `foreground`
  - `muted` / `muted-foreground`
  - `card` / `card-foreground`
  - `border` / `input`
- **品牌与状态色**:
  - `primary` / `primary-foreground`
  - `secondary` / `secondary-foreground`
  - `accent` / `accent-foreground`
  - `destructive` / `destructive-foreground`
  - `ring`

遵循 shadcn-vue 默认 Token，但做以下本项目定制：

#### 2. 品牌风格

- 整体风格：**专业的 SaaS 蓝 + 中性灰**，偏冷静、理性
- 建议：
  - `primary`: SaaS 蓝（例如 `hsl(222 84% 56%)`）
  - `accent`: 略浅的蓝绿或品牌辅助色
  - `muted`: 高亮度的灰色背景，用于次级区域

#### 3. 对比度规范

- 正文与背景对比度：≥ 4.5:1
- 大字号（≥ 18px）与背景对比度：≥ 3:1
- 辅助文字（`muted-foreground`）仍需满足可读性，避免过浅

可通过前文所列工具（WebAIM 等）验证，并在主题文件中做适当调整：

```css
:root {
  --muted-foreground: hsl(240 3.8% 40%);
}

.dark {
  --muted-foreground: hsl(240 5% 70%);
}
```

#### 4. 使用规范

- **Primary**:
  - 用于主按钮、主要操作入口
  - 聊天用户气泡背景
- **Accent**:
  - 用于选中态导航、侧边栏当前项
  - 空状态高亮区域
- **Destructive**:
  - 慎用，只用于删除、危险操作
- **Muted / Card**:
  - 消息气泡背景（AI 侧）、卡片背景
  - 快捷提示、空状态背景圆形区域

---

### 圆角与阴影 (Radius & Elevation)

#### 1. 圆角层级

- `rounded-sm`：4px —— Tag、小徽标
- `rounded-lg`：8px —— 按钮、卡片、小弹层
- `rounded-2xl`：16px —— 消息气泡、输入框、空状态大卡片
- `rounded-full`：全圆 —— 头像、圆形按钮、图标按钮

#### 2. 组件圆角规则

- 消息气泡：
  - 基础使用 `rounded-2xl`
  - 用户消息：右下角使用 `rounded-br-sm` 制造“尖角”效果
  - AI 消息：左下角使用 `rounded-bl-sm`
- 输入框：
  - 使用 `rounded-2xl`，与消息气泡在视觉上保持一致
- 侧边栏项 / 菜单项：
  - `rounded-lg`，避免过度圆润导致信息不聚焦

#### 3. 阴影层级

配合前文 CSS 变量：

- `--shadow-sm`：轻微浮起，用于消息气泡、选中态卡片
- `--shadow-md`：模块卡片、弹出层
- `--shadow-lg` / `--shadow-xl`：对话框、模态框（如未来设置弹窗）

在 Tailwind 中通过自定义 `shadow-sm` 等类或使用 `style="box-shadow: var(--shadow-sm)"` 方式统一。

---

### 图标与插画 (Iconography & Illustration)

#### 1. 图标风格

- 使用 **线性 SVG 图标**（与 Lucide / Heroicons 风格接近）
- 线宽统一：`stroke-width="1.5"` 或 `2`
- 拐角尽量圆润（`stroke-linecap="round"`，`stroke-linejoin="round"`）

#### 2. 使用规范

- 按钮图标尺寸：`w-4 h-4`（16px）
- 导航图标尺寸：`w-4 h-4` 或 `w-5 h-5`
- 空状态图标尺寸：`w-8 h-8`（32px）
- 与文字间距：8px（`gap-2`）

#### 3. 插画与空状态

- 避免使用过于卡通的插画，保持专业、简洁
- 形状以几何、线性为主，与 SaaS 产品调性一致
- 颜色限制在品牌色板内，不引入额外多彩色

---

### 交互与动效 (Interaction & Motion)

#### 1. 动画原则

- **轻量**：动画时长 150–250ms 为主，不超过 300ms
- **统一**：使用统一的缓动曲线，如 `ease-out` / `cubic-bezier(0.22, 0.61, 0.36, 1)`
- **有意义**：只为状态变化、层级变化添加动效

#### 2. 关键场景

- 按钮 hover：
  - 轻微背景色变化 + 阴影（`hover:bg-primary/90 hover:shadow-md`）
- 消息出现：
  - 使用淡入 + 轻微位移动画（如 `animate-fade-in-up`）
- 侧边栏展开 / 折叠（未来如实现）：
  - 宽度变化添加过渡（`transition-[width] duration-200`）
- 空状态 → 有消息：
  - 淡出空状态容器，再淡入消息列表

#### 3. 禁止事项

- 禁止长时间（>500ms）且无意义的 Loading 动画
- 禁止频繁闪烁、高饱和度的动效
- 禁止大面积位移造成“页面抖动”

---

### 响应式与布局 (Responsive & Layout)

#### 1. 断点策略

基于 Tailwind 默认断点：

- `sm`: 640px
- `md`: 768px
- `lg`: 1024px
- `xl`: 1280px

#### 2. 布局规则

- ≤ `md`:
  - 聊天页面侧边栏默认折叠，通过顶部按钮打开（未来实现）
  - 内容宽度：使用 `px-md`，不固定 `max-width-container`
- ≥ `lg`:
  - 启用固定侧边栏 + 内容区域
  - 内容最大宽度：`var(--max-width-container)`（1200px）
- 消息列表：
  - 始终使用 `max-w-[var(--max-width-content)] mx-auto`

#### 3. 滚动与高度

- 主布局使用 `h-screen`，顶部/底部固定，中间内容区域 `overflow-y-auto`
- 避免出现双滚动条（浏览器滚动条 + 内部容器同时滚动）

---

### 无障碍与可用性 (Accessibility & Usability)

#### 1. 键盘操作

- 所有可交互元素需可通过 `Tab` 访问
- 使用 `focus-visible` 或 `focus:ring` 提供明显焦点样式
- 输入框支持：
  - `Enter` 发送
  - `Shift+Enter` 换行

#### 2. 状态反馈

- 所有异步操作需要有**明确的视觉反馈**：
  - 加载：骨架屏 / Loading 指示器
  - 成功：轻量提示或状态变化
  - 错误：红色文本 + 适度图标，定位在错误字段附近

#### 3. 文案与提示

- 占位符使用**具体示例句**，避免只写“请输入内容”
- 错误提示尽量说明原因与解决方式，而非仅提示“出错了”

---

### 组件规范概览 (Component Guidelines)

后续在具体组件文档中可进一步拆分，这里给出统一基线：

- **Chat 消息 (MessageBubble)**:
  - 圆角：`rounded-2xl`，角色方向圆角差异
  - 间距：外 24px，内 16px/12px
  - 头像：8px 间距，AI/用户配色区分
- **输入框 (ChatInput)**:
  - 多行、自动扩展，高度 52px–200px
  - 右侧圆形发送按钮
- **侧边栏 (Sidebar)**:
  - 宽度变量：`--sidebar-width`
  - 背景半透明 + 模糊（`bg-card/50 backdrop-blur-sm`）
  - 会话项 hover/active 状态一致
- **空状态 (Empty State)**:
  - 图标 + 标题 + 描述 + 建议操作
  - 使用品牌色弱化版，不喧宾夺主

---

后续如引入更多模块（如 Workflow、KnowledgeBase 管理等），应继续沿用本设计系统中的**间距、排版、配色、圆角与动效规范**，确保整个 LifePilot Web 在不同功能页之间保持统一的体验与品牌识别度。