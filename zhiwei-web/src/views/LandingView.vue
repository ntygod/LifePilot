<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowRight } from 'lucide-vue-next'
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
  <div class="relative min-h-screen overflow-hidden bg-[#f5efe4] text-slate-950">
    <div class="absolute inset-0 bg-[linear-gradient(135deg,#f7f2e8_0%,#edf5ef_42%,#f7f2e8_100%)]" />
    <div class="absolute inset-0 opacity-35" style="background-image: radial-gradient(circle, rgba(15, 23, 42, 0.08) 1px, transparent 1.3px); background-size: 28px 28px;" />
    <div class="absolute inset-x-0 top-[-24%] h-[36rem] bg-[radial-gradient(circle_at_top,rgba(15,118,110,0.14),transparent_52%)]" />
    <div class="absolute left-[-8%] top-[18%] h-[26rem] w-[26rem] rounded-full bg-[radial-gradient(circle,rgba(15,23,42,0.06),transparent_64%)] blur-3xl" />
    <div class="absolute right-[-6%] bottom-[-12%] h-[24rem] w-[24rem] rounded-full bg-[radial-gradient(circle,rgba(14,165,233,0.1),transparent_65%)] blur-3xl" />
    <div class="absolute left-[8%] top-0 h-full w-px bg-[linear-gradient(180deg,transparent,rgba(148,163,184,0.22),transparent)]" />
    <div class="absolute right-[14%] top-[18%] h-2 w-2 rounded-full bg-emerald-600/60 shadow-[0_0_18px_rgba(13,148,136,0.24)]" />
    <div class="absolute right-[19%] top-[26%] h-1.5 w-1.5 rounded-full bg-slate-900/24" />
    <div class="absolute right-[10%] top-[33%] h-1.5 w-1.5 rounded-full bg-sky-700/28" />
    <div class="absolute left-[18%] bottom-[20%] h-2 w-2 rounded-full bg-slate-900/14" />

    <div class="relative mx-auto flex min-h-screen max-w-[1480px] flex-col px-6 py-6 sm:px-8 lg:px-10">
      <header class="flex items-center justify-center">
        <div class="inline-flex items-center gap-3 rounded-full border border-slate-900/7 bg-white/68 px-4 py-2 shadow-[0_16px_34px_-34px_rgba(15,23,42,0.18)] backdrop-blur-xl">
          <div class="flex size-10 items-center justify-center rounded-2xl bg-slate-950 text-white">
            <svg viewBox="0 0 48 48" class="size-5" fill="none" aria-hidden="true">
              <rect x="11" y="11" width="26" height="26" rx="8" stroke="currentColor" stroke-width="2.4" />
              <path d="M17 29c2.8-6 10.2-6 14 0" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
              <path d="M19 20.5c2.5 3 7.5 3 10 0" stroke="#6ee7b7" stroke-width="2.4" stroke-linecap="round" />
              <circle cx="24" cy="24" r="2.2" fill="#6ee7b7" />
            </svg>
          </div>
          <div>
            <div class="text-[0.72rem] font-semibold tracking-[0.22em] text-slate-500">ZHIWEI</div>
            <div class="text-sm text-slate-700">见微知著的个人助手</div>
          </div>
        </div>
      </header>

      <main class="flex flex-1 items-center justify-center">
        <section class="flex w-full max-w-[1180px] flex-col items-center space-y-16 py-16 text-center lg:py-24">
          <div class="flex max-w-[760px] flex-col items-center space-y-10">
            <div class="inline-flex items-center gap-2 rounded-full border border-emerald-600/9 bg-white/66 px-3 py-1.5 text-sm text-emerald-700 shadow-[0_14px_28px_-24px_rgba(13,148,136,0.14)] backdrop-blur-xl">
              <span class="inline-block h-2 w-2 rounded-full bg-emerald-500" />
              见微知著的个人助手工作台
            </div>

            <div class="space-y-8">
              <div class="font-brand-serif text-5xl text-slate-900 sm:text-6xl lg:text-[5.4rem]">
                知微
              </div>
              <h1 class="font-brand-serif text-4xl leading-[1.08] tracking-[-0.06em] text-slate-950 sm:text-5xl lg:text-[3.7rem]">
                知微见著，
                胸有成竹。
              </h1>
              <p class="max-w-[40rem] text-base leading-8 text-slate-700/82 sm:text-lg">
                守住数据，记住语境，调度工具，
                让复杂事务在一处稳步推进。
              </p>
            </div>

            <div class="flex flex-col items-center gap-4">
              <Button size="lg" class="h-13 min-w-[190px] rounded-full bg-slate-950 px-6 text-base font-semibold text-white shadow-[0_26px_50px_-26px_rgba(15,23,42,0.42)] hover:bg-slate-900" @click="startUsing">
                启卷知微
                <ArrowRight class="size-4" />
              </Button>
              <div class="text-sm text-slate-600/76">
                隐私安全、工具丰富、记忆模块、自主任务
              </div>
            </div>
          </div>

          <div class="grid w-full max-w-[1140px] gap-5 md:grid-cols-2 xl:grid-cols-4">
            <article
              v-for="item in features"
              :key="item.key"
              class="relative overflow-hidden rounded-[30px] bg-white/30 px-5 py-6 shadow-[0_22px_42px_-38px_rgba(15,23,42,0.16)] backdrop-blur-xl"
            >
              <div class="absolute inset-0 rounded-[30px] bg-[linear-gradient(180deg,rgba(255,255,255,0.2),rgba(255,255,255,0.04))]" />
              <div class="absolute right-3 top-3 h-24 w-24 rounded-full bg-[radial-gradient(circle,rgba(15,118,110,0.08),transparent_68%)] blur-2xl" />
              <div class="relative flex flex-col items-center gap-4 text-center">
                <div class="relative flex size-[3.25rem] shrink-0 items-center justify-center overflow-hidden rounded-[1.15rem] bg-[linear-gradient(180deg,rgba(255,255,255,0.78),rgba(255,255,255,0.36))] text-slate-950 shadow-[0_14px_24px_-24px_rgba(15,23,42,0.18)]">
                  <svg viewBox="0 0 48 48" class="absolute inset-[6px] text-slate-500/18" fill="none" aria-hidden="true">
                    <path d="M10 31c3-6 8-9 14-9s11 3 14 9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                    <path d="M14 18.5h20" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" />
                    <path d="M17 14.5h14" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
                    <path d="M17 35.5h14" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
                  </svg>
                  <span class="relative font-brand-serif text-[1.35rem]">{{ item.key }}</span>
                </div>
                <div class="min-w-0 flex-1 space-y-2">
                  <div class="font-brand-serif text-sm tracking-[0.08em] text-slate-500">{{ item.eyebrow }}</div>
                  <h2 class="font-brand-serif text-xl text-slate-950">{{ item.title }}</h2>
                  <p class="text-sm leading-7 text-slate-600/92">
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
