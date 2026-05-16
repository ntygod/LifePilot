<script setup lang="ts">
/**
 * 空态示例 prompt 卡片集合。
 *
 * <p>空态对话页底部渲染的一组快捷提示卡片，点击后把 prompt 填入输入框。
 * 设计参考 ChatGPT / Claude.ai / Kimi 的示例卡片，典型 4 张 2x2 布局，
 * 点击后 emit pick 事件，由 ChatView 将 content 灌入 ChatInput。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { Lightbulb, PenLine, Search, Sparkles } from 'lucide-vue-next'
import { computed, type Component } from 'vue'

interface PromptCard {
  id: string
  title: string
  subtitle?: string
  prompt: string
  icon: Component
}

const DEFAULT_CARDS: PromptCard[] = [
  {
    id: 'brainstorm',
    title: '帮我头脑风暴',
    subtitle: '产品点子 / 标题 / 方案',
    prompt: '帮我围绕「」做一次头脑风暴，列出 5 个不同角度的想法，每个想法给一句话说明。',
    icon: Sparkles,
  },
  {
    id: 'writing',
    title: '润色一段文字',
    subtitle: '公文 / 周报 / 邮件',
    prompt: '请帮我润色下面这段文字，保留原意但更简洁、更专业。内容：\n\n',
    icon: PenLine,
  },
  {
    id: 'research',
    title: '查找资料',
    subtitle: '综述 / 对比 / 核实',
    prompt: '请帮我查找「」相关的资料，给出关键要点、权威来源和你的结论。',
    icon: Search,
  },
  {
    id: 'explain',
    title: '用我听得懂的方式解释',
    subtitle: '把概念讲明白',
    prompt: '请用我听得懂的方式解释：',
    icon: Lightbulb,
  },
]

const props = defineProps<{ cards?: PromptCard[] }>()

const cards = computed<PromptCard[]>(() => props.cards ?? DEFAULT_CARDS)

const emit = defineEmits<{
  pick: [card: PromptCard]
}>()

function handlePick(card: PromptCard) {
  emit('pick', card)
}
</script>

<template>
  <div class="prompt-gallery">
    <button
      v-for="card in cards"
      :key="card.id"
      type="button"
      class="prompt-card"
      @click="handlePick(card)"
    >
      <div class="prompt-card__icon">
        <component :is="card.icon" class="size-[18px]" />
      </div>
      <div class="prompt-card__body">
        <div class="prompt-card__title">{{ card.title }}</div>
        <div v-if="card.subtitle" class="prompt-card__subtitle">
          {{ card.subtitle }}
        </div>
      </div>
    </button>
  </div>
</template>

<style scoped>
.prompt-gallery {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
  max-width: 560px;
}

@media (max-width: 640px) {
  .prompt-gallery {
    grid-template-columns: 1fr;
  }
}

.prompt-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 16px;
  border-radius: 14px;
  background: hsl(from var(--card) h s l / 0.9);
  border: 1px solid hsl(from var(--border) h s l / 0.55);
  text-align: left;
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease, transform 160ms ease,
    box-shadow 160ms ease;
}

.prompt-card:hover {
  background: hsl(from var(--card) h s l / 1);
  border-color: hsl(from var(--border) h s l / 1);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px -8px hsl(var(--shadow-color) / 0.14);
}

.prompt-card:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

.prompt-card__icon {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: hsl(from var(--primary) h s l / 0.08);
  color: var(--primary);
}

.prompt-card__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.prompt-card__title {
  font-size: 14px;
  font-weight: 500;
  color: var(--foreground);
  line-height: 1.35;
}

.prompt-card__subtitle {
  font-size: 12px;
  color: var(--muted-foreground);
  line-height: 1.4;
}
</style>
