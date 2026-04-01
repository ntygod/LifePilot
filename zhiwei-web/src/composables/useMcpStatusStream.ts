import { onMounted, onUnmounted, ref } from 'vue'
import { getApiOrigin } from '@/api/config'
import { useSkillStore } from '@/stores/skill'
import type { McpStatusSnapshot, McpStatusChange } from '@/types'

/**
 * MCP Server 状态 SSE 实时订阅 composable。
 *
 * 建立 EventSource 连接到 /api/mcp/servers/status-stream，
 * 监听 mcp-status-snapshot 和 mcp-status-change 事件，
 * 自动更新 skillStore.mcpServers 中对应 Server 的状态。
 * 组件卸载时自动关闭连接。
 */
export function useMcpStatusStream() {
  const connected = ref(false)
  let eventSource: EventSource | null = null

  function connect() {
    if (eventSource) return

    eventSource = new EventSource(`${getApiOrigin()}/api/mcp/servers/status-stream`)
    const skillStore = useSkillStore()

    eventSource.addEventListener('mcp-status-snapshot', (e: MessageEvent) => {
      try {
        const snapshot: McpStatusSnapshot = JSON.parse(e.data)
        for (const serverStatus of snapshot.servers) {
          const server = skillStore.mcpServers.find(s => s.name === serverStatus.serverName)
          if (server) {
            server.state = serverStatus.state
            server.connectedSince = serverStatus.connectedSince
            server.lastError = serverStatus.lastError
          }
        }
        connected.value = true
      } catch (err) {
        console.error('MCP 状态快照解析失败:', err)
      }
    })

    eventSource.addEventListener('mcp-status-change', (e: MessageEvent) => {
      try {
        const change: McpStatusChange = JSON.parse(e.data)
        const server = skillStore.mcpServers.find(s => s.name === change.serverName)
        if (server) {
          server.state = change.newState
          server.connectedSince = change.newState === 'CONNECTED' ? change.timestamp : undefined
          server.lastError = change.error
        }
      } catch (err) {
        console.error('MCP 状态变化解析失败:', err)
      }
    })

    eventSource.onerror = () => {
      connected.value = false
    }

    eventSource.onopen = () => {
      connected.value = true
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
