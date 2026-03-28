<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowRight } from 'lucide-vue-next'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'
import { Button } from '@/components/ui/button'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const chatStore = useChatStore()

async function startUsing() {
  if (chatStore.sessions.length === 0) {
    await chatStore.loadSessions()
  }

  if (chatStore.activeSessionId) {
    router.push({ name: 'conversationDetail', params: { sessionId: chatStore.activeSessionId } })
    return
  }

  const session = await chatStore.startNewSession()
  router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
}

const features = [
  {
    key: '隐',
    eyebrow: '隐私安全',
    title: '数据归藏，安如磐石',
    description: '本地优先，重要数据尽量留在自己手中。',
  },
  {
    key: '驭',
    eyebrow: '工具丰富',
    title: '善假于物，袖里乾坤',
    description: '技能、工具与模型汇于一处，调用切换更顺手。',
  },
  {
    key: '忆',
    eyebrow: '记忆模块',
    title: '过目不忘，心有灵犀',
    description: '上下文与偏好持续沉淀，越用越懂你。',
  },
  {
    key: '谋',
    eyebrow: '自主任务',
    title: '谋定后动，次第成章',
    description: '围绕目标拆解步骤，接续执行，让任务持续向前。',
  },
] as const
</script>

<template>
  <div class="landing-page relative min-h-screen overflow-hidden text-foreground">
    <div class="landing-grid absolute inset-0" />
    <div class="landing-top-glow absolute inset-x-0 top-[-18%] h-[34rem]" />
    <div class="landing-side-glow landing-side-glow-left absolute left-[-10%] top-[16%] h-[24rem] w-[24rem] rounded-full blur-3xl" />
    <div class="landing-side-glow landing-side-glow-right absolute right-[-8%] bottom-[-12%] h-[22rem] w-[22rem] rounded-full blur-3xl" />
    <div class="landing-divider absolute left-[8%] top-0 hidden h-full w-px lg:block" />
    <div class="landing-dot landing-dot-primary absolute right-[14%] top-[18%] h-2 w-2 rounded-full" />
    <div class="landing-dot landing-dot-muted absolute right-[19%] top-[26%] h-1.5 w-1.5 rounded-full" />
    <div class="landing-dot landing-dot-soft absolute right-[10%] top-[33%] h-1.5 w-1.5 rounded-full" />
    <div class="landing-dot landing-dot-muted absolute left-[18%] bottom-[20%] h-2 w-2 rounded-full" />

    <div class="relative mx-auto flex min-h-screen max-w-[1480px] flex-col px-6 py-6 sm:px-8 lg:px-10">
      <header class="flex items-center justify-center">
        <div class="landing-brand-chip inline-flex items-center gap-3 rounded-full px-4 py-2">
          <div class="landing-logo flex size-10 items-center justify-center rounded-2xl text-white">
            <ZhiweiMark class="size-5" />
          </div>
          <div>
            <div class="text-[0.72rem] font-semibold tracking-[0.22em] text-muted-foreground">ZHIWEI</div>
            <div class="text-sm text-foreground/78">见微知著的个人助手</div>
          </div>
        </div>
      </header>

      <main class="flex flex-1 items-center justify-center">
        <section class="flex w-full max-w-[1180px] flex-col items-center space-y-16 py-16 text-center lg:py-24">
          <div class="flex max-w-[760px] flex-col items-center space-y-10">
            <div class="landing-eyebrow inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-sm">
              <span class="landing-eyebrow-dot inline-block h-2 w-2 rounded-full" />
              见微知著的个人助手
            </div>

            <div class="space-y-8">
              <div class="text-5xl font-semibold tracking-[-0.08em] text-foreground sm:text-6xl lg:text-[5.4rem]">
                知微
              </div>
              <h1 class="text-4xl leading-[1.08] tracking-[-0.08em] text-foreground sm:text-5xl lg:text-[3.7rem]">
                见微知著，
                谋定后动。
              </h1>
              <p class="max-w-[40rem] text-base leading-8 text-muted-foreground sm:text-lg">
                守住数据，记住语境，调度工具，
                让复杂事务在一处稳步推进。
              </p>
            </div>

            <div class="flex flex-col items-center gap-4">
              <Button size="lg" class="landing-primary-button h-13 min-w-[190px] rounded-full px-6 text-base font-semibold text-primary-foreground" @click="startUsing">
                启卷知微
                <ArrowRight class="size-4" />
              </Button>
              <div class="text-sm text-muted-foreground">
                隐私安全、工具丰富、记忆模块、自主任务
              </div>
            </div>
          </div>

          <div class="grid w-full max-w-[1140px] gap-5 md:grid-cols-2 xl:grid-cols-4">
            <article
              v-for="item in features"
              :key="item.key"
              class="landing-feature-card relative overflow-hidden rounded-[30px] px-5 py-6"
            >
              <div class="landing-feature-tint absolute inset-0 rounded-[30px]" />
              <div class="landing-feature-glow absolute right-3 top-3 h-24 w-24 rounded-full blur-2xl" />
              <div class="relative flex flex-col items-center gap-4 text-center">
                <div class="landing-feature-icon relative flex size-[3.25rem] shrink-0 items-center justify-center overflow-hidden rounded-[1.15rem] text-foreground">
                  <svg viewBox="0 0 48 48" class="absolute inset-[6px] text-foreground/12" fill="none" aria-hidden="true">
                    <path d="M10 31c3-6 8-9 14-9s11 3 14 9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                    <path d="M14 18.5h20" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
                    <path d="M17 14.5h14" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
                    <path d="M17 35.5h14" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
                  </svg>
                  <span class="relative text-[1.2rem] font-semibold tracking-[-0.06em]">{{ item.key }}</span>
                </div>
                <div class="min-w-0 flex-1 space-y-2">
                  <div class="text-sm font-medium tracking-[0.04em] text-muted-foreground">{{ item.eyebrow }}</div>
                  <h2 class="text-xl tracking-[-0.05em] text-foreground">{{ item.title }}</h2>
                  <p class="text-sm leading-7 text-muted-foreground">
                    {{ item.description }}
                  </p>
                </div>
              </div>
            </article>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
.landing-page {
  background:
    radial-gradient(circle at top center, hsl(from var(--primary) h s l / 0.08), transparent 28rem),
    linear-gradient(180deg, hsl(212 26% 98.9%) 0%, hsl(216 22% 96.9%) 100%);
}

.landing-grid {
  background-image:
    linear-gradient(180deg, transparent 0%, hsl(from var(--border) h s l / 0.28) 48%, transparent 100%),
    radial-gradient(circle, hsl(from var(--border) h s l / 0.24) 1px, transparent 1.35px);
  background-size: 100% 100%, 28px 28px;
  opacity: 0.42;
}

.landing-top-glow {
  background: radial-gradient(circle at top, hsl(from var(--primary) h s l / 0.14), transparent 56%);
}

.landing-side-glow-left {
  background: radial-gradient(circle, hsl(from var(--foreground) h s l / 0.05), transparent 64%);
}

.landing-side-glow-right {
  background: radial-gradient(circle, hsl(from var(--primary) h s l / 0.09), transparent 65%);
}

.landing-divider {
  background: linear-gradient(180deg, transparent, hsl(from var(--border) h s l / 0.78), transparent);
}

.landing-dot-primary {
  background: hsl(from var(--primary) h s l / 0.72);
  box-shadow: 0 0 18px hsl(from var(--primary) h s l / 0.2);
}

.landing-dot-muted {
  background: hsl(from var(--foreground) h s l / 0.16);
}

.landing-dot-soft {
  background: hsl(from var(--primary) h s l / 0.28);
}

.landing-brand-chip {
  border: 1px solid hsl(from var(--border) h s l / 0.85);
  background:
    linear-gradient(180deg, hsl(from var(--card) h s l / 0.92), hsl(from var(--card) h s l / 0.76)),
    hsl(from var(--card) h s l / 0.82);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.82),
    0 18px 34px -32px hsl(from var(--shadow-color) / 0.16);
  backdrop-filter: blur(16px);
}

.landing-logo {
  background:
    linear-gradient(180deg, hsl(222 34% 12%) 0%, hsl(222 38% 9%) 100%);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.08),
    0 14px 24px -20px hsl(from var(--shadow-color) / 0.35);
}

.landing-eyebrow {
  border: 1px solid hsl(from var(--primary) h s l / 0.14);
  background:
    linear-gradient(180deg, hsl(from var(--card) h s l / 0.86), hsl(from var(--card) h s l / 0.68)),
    hsl(from var(--card) h s l / 0.72);
  color: hsl(from var(--primary) h s l / 0.9);
  box-shadow: 0 16px 28px -26px hsl(from var(--primary) h s l / 0.24);
  backdrop-filter: blur(16px);
}

.landing-eyebrow-dot {
  background: hsl(from var(--primary) h s l / 0.76);
  box-shadow: 0 0 0 5px hsl(from var(--primary) h s l / 0.08);
}

.landing-primary-button {
  background:
    linear-gradient(180deg, hsl(from var(--primary) h s l / 0.94), hsl(from var(--primary) h s l / 0.84));
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.18),
    0 24px 48px -28px hsl(from var(--primary) h s l / 0.52);
}

.landing-primary-button:hover {
  background:
    linear-gradient(180deg, hsl(from var(--primary) h s l / 0.98), hsl(from var(--primary) h s l / 0.88));
}

.landing-feature-card {
  border: 1px solid hsl(from var(--border) h s l / 0.85);
  background:
    linear-gradient(180deg, hsl(from var(--card) h s l / 0.88), hsl(from var(--card) h s l / 0.72)),
    hsl(from var(--card) h s l / 0.8);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.88),
    0 22px 42px -38px hsl(from var(--shadow-color) / 0.18);
  backdrop-filter: blur(18px);
}

.landing-feature-card::before {
  position: absolute;
  inset: 0 0 auto 0;
  height: 2px;
  content: "";
  background: linear-gradient(90deg, transparent, hsl(from var(--primary) h s l / 0.52), transparent);
}

.landing-feature-tint {
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.18), transparent 58%);
}

.landing-feature-glow {
  background: radial-gradient(circle, hsl(from var(--primary) h s l / 0.1), transparent 68%);
}

.landing-feature-icon {
  border: 1px solid hsl(from var(--border) h s l / 0.9);
  background:
    linear-gradient(180deg, hsl(from var(--card) h s l / 0.92), hsl(from var(--muted) h s l / 0.84));
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.88),
    0 14px 24px -24px hsl(from var(--shadow-color) / 0.18);
}
</style>
