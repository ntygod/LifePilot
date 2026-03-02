import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AgentSummary, AgentDetail } from '@/types'
import { agentApi } from '@/api/client'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<AgentSummary[]>([])
  const currentAgent = ref<AgentDetail | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchAgents(params?: { q?: string; type?: string; status?: string; tags?: string[] }) {
    loading.value = true
    error.value = null
    try {
      agents.value = await agentApi.list(params)
    } catch (e: any) {
      error.value = e.message ?? '加载 Agent 列表失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function fetchAgentDetail(id: string) {
    error.value = null
    try {
      currentAgent.value = await agentApi.get(id)
    } catch (e: any) {
      error.value = e.message ?? '加载 Agent 详情失败'
      throw e
    }
  }

  async function createAgent(data: Partial<AgentDetail>) {
    loading.value = true
    error.value = null
    try {
      const agent = await agentApi.create(data)
      await fetchAgents()
      return agent
    } catch (e: any) {
      error.value = e.message ?? '创建 Agent 失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function updateAgent(id: string, data: Partial<AgentDetail>) {
    loading.value = true
    error.value = null
    try {
      const agent = await agentApi.update(id, data)
      await fetchAgents()
      if (currentAgent.value?.id === id) {
        currentAgent.value = agent
      }
      return agent
    } catch (e: any) {
      error.value = e.message ?? '更新 Agent 失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function deleteAgent(id: string) {
    loading.value = true
    error.value = null
    try {
      await agentApi.delete(id)
      await fetchAgents()
      if (currentAgent.value?.id === id) {
        currentAgent.value = null
      }
    } catch (e: any) {
      error.value = e.message ?? '删除 Agent 失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function enableAgent(id: string) {
    error.value = null
    try {
      await agentApi.enable(id)
      await fetchAgents()
      if (currentAgent.value?.id === id) {
        await fetchAgentDetail(id)
      }
    } catch (e: any) {
      error.value = e.message ?? '启用 Agent 失败'
      throw e
    }
  }

  async function disableAgent(id: string) {
    error.value = null
    try {
      await agentApi.disable(id)
      await fetchAgents()
      if (currentAgent.value?.id === id) {
        await fetchAgentDetail(id)
      }
    } catch (e: any) {
      error.value = e.message ?? '禁用 Agent 失败'
      throw e
    }
  }

  return {
    agents,
    currentAgent,
    loading,
    error,
    fetchAgents,
    fetchAgentDetail,
    createAgent,
    updateAgent,
    deleteAgent,
    enableAgent,
    disableAgent
  }
})
