import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SkillSummary, SkillDetail, McpServer, McpTool } from '@/types'
import { skillApi, mcpApi } from '@/api/client'

export const useSkillStore = defineStore('skill', () => {
  const skills = ref<SkillSummary[]>([])
  const currentSkill = ref<SkillDetail | null>(null)
  const mcpServers = ref<McpServer[]>([])
  const serverTools = ref<McpTool[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchSkills() {
    loading.value = true
    error.value = null
    try {
      skills.value = await skillApi.list()
    } catch (e: any) {
      error.value = e.message ?? '加载 Skill 列表失败'
    } finally {
      loading.value = false
    }
  }

  async function fetchSkillDetail(id: string) {
    error.value = null
    try {
      currentSkill.value = await skillApi.get(id)
    } catch (e: any) {
      error.value = e.message ?? '加载 Skill 详情失败'
    }
  }

  async function unregisterSkill(id: string) {
    error.value = null
    try {
      await skillApi.unregister(id)
      skills.value = skills.value.filter(s => s.id !== id)
      if (currentSkill.value?.id === id) currentSkill.value = null
    } catch (e: any) {
      error.value = e.message ?? '注销 Skill 失败'
    }
  }

  async function fetchMcpServers() {
    loading.value = true
    error.value = null
    try {
      mcpServers.value = await mcpApi.listServers()
    } catch (e: any) {
      error.value = e.message ?? '加载 MCP Server 列表失败'
    } finally {
      loading.value = false
    }
  }

  async function connectServer(name: string) {
    error.value = null
    try {
      await mcpApi.connect(name)
      await fetchMcpServers()
    } catch (e: any) {
      error.value = e.message ?? '连接 MCP Server 失败'
    }
  }

  async function disconnectServer(name: string) {
    error.value = null
    try {
      await mcpApi.disconnect(name)
      await fetchMcpServers()
    } catch (e: any) {
      error.value = e.message ?? '断开 MCP Server 失败'
    }
  }

  async function fetchServerTools(name: string) {
    error.value = null
    try {
      serverTools.value = await mcpApi.listTools(name)
    } catch (e: any) {
      error.value = e.message ?? '加载工具列表失败'
    }
  }

  return {
    skills, currentSkill, mcpServers, serverTools, loading, error,
    fetchSkills, fetchSkillDetail, unregisterSkill,
    fetchMcpServers, connectServer, disconnectServer, fetchServerTools
  }
})
