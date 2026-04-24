import { onMounted, onUnmounted, ref } from 'vue'
import { getApiOrigin } from '@/api/config'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { logger } from '@/utils/logger'

/**
 * Skill 生成事件 SSE 订阅 payload。
 *
 * 与后端 {@code SkillGeneratedSseController#onSkillGenerated} 广播的 Map 结构保持一致。
 */
interface SkillGeneratedPayload {
  type: string
  skillName: string
  sourceType: string
  at: string
}

/**
 * Skill 生成事件 SSE 实时订阅 composable。
 *
 * <p>建立 EventSource 连接到 /api/skills/events，监听 {@code skill-generated} 事件：
 * 收到后弹出 toast 提示"ZhiWei 已为你生成技能：{name}"，并刷新技能列表
 * 以便用户切换到技能页时可以立即看到新增条目。</p>
 *
 * <p>当前 toast 基建不支持点击回调（仅 type+message），因此直接把技能名写入提示文案；
 * 用户如需查看详情，在全局侧边栏进入"技能"页即可看到。</p>
 *
 * <p>组件卸载时自动关闭连接。EventSource 自带重连机制，这里不做手动重试。</p>
 */
export function useSkillLifecycleEvents() {
  const connected = ref(false)
  let eventSource: EventSource | null = null

  function connect() {
    if (eventSource) return

    eventSource = new EventSource(`${getApiOrigin()}/api/skills/events`)
    const skillStore = useSkillStore()
    const uiStore = useUiStore()

    eventSource.addEventListener(SSE_EVENT_TYPES.SKILL_GENERATED, (e: MessageEvent) => {
      try {
        const payload = JSON.parse(e.data) as SkillGeneratedPayload
        if (payload.type !== 'SKILL_GENERATED') return

        uiStore.showToast('success', `ZhiWei 已为你生成技能：${payload.skillName}`)

        // 后台刷新技能列表，确保用户下次打开"技能"页时看到新增条目；
        // 失败静默，toast 已经告知用户事件发生
        skillStore.fetchSkills().catch(err => {
          logger.warn('Skill 生成事件后刷新列表失败:', err)
        })
      } catch (err) {
        logger.error('Skill 生成事件解析失败:', err)
      }
    })

    eventSource.onopen = () => {
      connected.value = true
    }

    eventSource.onerror = () => {
      connected.value = false
    }
  }

  function disconnect() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
      connected.value = false
    }
  }

  onMounted(() => connect())
  onUnmounted(() => disconnect())

  return { connected, connect, disconnect }
}
