# 欢迎页重设计 — 呼吸光圈方案

## 目标

将新建对话的欢迎页从"功能展示型"改为"极简温暖型"，去掉快捷操作卡片和能力标签，只保留品牌问候 + 精致输入框，传达温暖亲和 + 精致美观的感觉。

## 改动范围

| 文件 | 改动程度 | 说明 |
|------|---------|------|
| `EmptyState.vue` | 大幅重写 | 移除卡片和标签，只留 Logo 光晕 + 问候 + 副标题 |
| `ChatInput.vue` | 仅改 CSS | 毛玻璃浮岛外观，逻辑完全不动 |
| `ChatView.vue` | 微调 | 空状态区域垂直定位从 `pt-[15vh]` 改为 `pt-[12vh]` |

## 设计细节

### 1. 问候区（EmptyState.vue）

**品牌标记：**
- 保留 `ZhiweiMark` 组件
- 外围加径向渐变光晕：`radial-gradient(circle, primary/12 0%, transparent 70%)`
- 光晕容器 64x64，带 3 秒周期呼吸缩放动画（`scale 1.0 → 1.08`，`ease-in-out`）
- 外层再套一圈更大更淡的光晕（`inset: -8px`，`primary/6`），同步呼吸但幅度略大（`scale 1.0 → 1.15`）

**问候语：**
- 保留现有时段判断逻辑（早上好/下午好/晚上好/夜深了）
- 字号 22px（`text-[22px]`），`font-semibold`，`tracking-tight`

**副标题：**
- 文案改为 "知微 · 你的 AI 助手"
- 字号 13px，`text-muted-foreground`

**移除的元素：**
- 4 个推荐 Prompt 卡片（`prompts` 数组 + 对应模板）
- 6 个能力标签（`capabilities` 数组 + 对应模板）
- `prompt-float` 关键帧动画
- `fill` emit 事件（不再需要）

### 2. 输入框（ChatInput.vue — 仅 CSS）

**毛玻璃浮岛外观：**
- `.chat-composer-shell` 背景改为 `rgba(255, 255, 255, 0.85)` + `backdrop-filter: blur(12px)`
- 圆角从 `rounded-2xl`（16px）加大到 20px
- 阴影改为 `0 4px 24px -8px rgba(0,0,0,0.06)`，内侧保留 `inset 0 1px 0 rgba(255,255,255,0.8)` 高光
- hover 状态：阴影加深至 `0 8px 32px -8px rgba(0,0,0,0.08)`，边框色微变
- focus-within 状态：边框显示 `primary/40`，外围 `0 0 0 3px primary/8` 光晕

**工具栏微调：**
- 工具按钮圆角统一为 10px（`rounded-[10px]`），尺寸保持 32x32
- 工具栏与输入区之间去掉视觉分隔，靠 8px 间距自然呼吸

**暗色模式适配：**
- 毛玻璃背景改为 `hsl(from var(--card) h s l / 0.85)` + 同样的 `backdrop-filter`
- 内侧高光改为 `inset 0 1px 0 hsl(from var(--card) h s l / 0.5)`
- 阴影跟随 `--shadow-color` 变量
- 其余跟随现有 CSS 变量体系自动适配

**不改的：**
- 所有 JS/TS 逻辑：@ 引用、附件上传、语音录制、拖拽、字数统计、发送等
- 工具栏功能按钮的排列顺序和行为
- 所有 emit 事件和 props 接口

### 3. 布局（ChatView.vue）

- 空状态容器 `pt-[15vh]` 改为 `pt-[12vh]`，视觉重心略微上移
- 最大宽度保持 `max-w-[540px]`

### 4. 动画

**入场动画：**
- 问候区：`fade-in` + `slide-in-from-bottom-4`，`duration-500`
- 输入框：同样的入场动画，`delay-200` 延迟跟上，形成层次感

**呼吸光晕：**
```css
@keyframes glow-breathe {
  0%, 100% { transform: scale(1); opacity: 0.8; }
  50% { transform: scale(1.08); opacity: 1; }
}
```
- 3 秒周期，`ease-in-out`，无限循环
- `prefers-reduced-motion` 时禁用

**移除：**
- `prompt-float` 浮动动画（随卡片一起删除）

## 不在范围内

- ChatInput 的功能逻辑改动
- 消息列表、底部输入栏（非空状态）的样式
- 侧边栏样式
- 新增组件或依赖
