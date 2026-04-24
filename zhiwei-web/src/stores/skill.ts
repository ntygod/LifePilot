import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SkillSummary, SkillDetail, McpServer, McpTool, SkillInstallation } from '@/types'
import { skillApi, mcpApi } from '@/api/client'

export const useSkillStore = defineStore('skill', () => {
  const skills = ref<SkillSummary[]>([])
  const currentSkill = ref<SkillDetail | null>(null)
  const mcpServers = ref<McpServer[]>([])
  const serverTools = ref<McpTool[]>([])
  const npxAvailable = ref<boolean | null>(null)
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

  async function fetchSkillDetail(name: string) {
    error.value = null
    try {
      currentSkill.value = await skillApi.get(name)
    } catch (e: any) {
      error.value = e.message ?? '加载 Skill 详情失败'
    }
  }

  async function unregisterSkill(name: string) {
    error.value = null
    try {
      await skillApi.unregister(name)
      skills.value = skills.value.filter(s => s.name !== name)
      if (currentSkill.value?.name === name) currentSkill.value = null
    } catch (e: any) {
      error.value = e.message ?? '注销 Skill 失败'
    }
  }

  async function fetchMcpServers(silent = false) {
    if (!silent) loading.value = true
    error.value = null
    try {
      mcpServers.value = await mcpApi.listServers()
    } catch (e: any) {
      error.value = e.message ?? '加载 MCP Server 列表失败'
    } finally {
      if (!silent) loading.value = false
    }
  }

  async function fetchMcpStatus() {
    try {
      const status = await mcpApi.getStatus()
      npxAvailable.value = status.npxAvailable
    } catch {
      npxAvailable.value = null
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

  async function createSkill(data: { skillMdContent: string }) {
    error.value = null
    try {
      const installation = await skillApi.create(data)
      await fetchSkills()
      return installation
    } catch (e: any) {
      error.value = e.message ?? '创建 Skill 失败'
      throw e
    }
  }

  /**
   * 直接更新已有 Skill 的 SKILL.md 原文 —— 走 B.6 新增的 PUT /skills/{name}/markdown 端点。
   * 成功后刷新详情缓存与列表。
   */
  async function updateSkillMarkdown(name: string, content: string) {
    error.value = null
    try {
      await skillApi.updateSkillMarkdown(name, content)
      // Markdown 改动会影响 description/tools 等衍生字段，刷新详情 + 列表保持一致
      if (currentSkill.value?.name === name) {
        await fetchSkillDetail(name)
      }
      await fetchSkills()
    } catch (e: any) {
      error.value = e.message ?? '更新 Skill Markdown 失败'
      throw e
    }
  }

  async function updateSkill(name: string, data: Partial<SkillDetail>) {
    error.value = null
    try {
      const skill = await skillApi.update(name, data)
      await fetchSkills()
      if (currentSkill.value?.name === name) {
        currentSkill.value = skill
      }
      return skill
    } catch (e: any) {
      error.value = e.message ?? '更新 Skill 失败'
      throw e
    }
  }

  async function createMcpServer(data: { name: string; config: any }) {
    error.value = null
    try {
      const server = await mcpApi.createServer(data)
      await fetchMcpServers()
      return server
    } catch (e: any) {
      error.value = e.message ?? '创建 MCP Server 失败'
      throw e
    }
  }

  async function updateMcpServer(name: string, data: { config: any }) {
    error.value = null
    try {
      const server = await mcpApi.updateServer(name, data)
      await fetchMcpServers()
      return server
    } catch (e: any) {
      error.value = e.message ?? '更新 MCP Server 失败'
      throw e
    }
  }

  async function setSkillEnabled(name: string, enabled: boolean) {
    error.value = null
    try {
      await skillApi.setEnabled(name, enabled)
      // 先本地乐观更新，避免列表闪烁
      const existing = skills.value.find(s => s.name === name)
      if (existing) existing.enabled = enabled
      if (currentSkill.value?.name === name) {
        currentSkill.value.enabled = enabled
      }
      await fetchSkills()
    } catch (e: any) {
      error.value = e.message ?? '更新 Skill 启用状态失败'
      throw e
    }
  }

  async function enableSkill(name: string) {
    await setSkillEnabled(name, true)
  }

  async function disableSkill(name: string) {
    await setSkillEnabled(name, false)
  }

  async function importPackage(file: File): Promise<SkillInstallation> {
    error.value = null
    try {
      const installation = await skillApi.importPackage(file)
      await fetchSkills()
      return installation
    } catch (e: any) {
      error.value = e.message ?? '导入 Skill 压缩包失败'
      throw e
    }
  }

  async function installFromMarketplace(marketplaceId: string): Promise<SkillInstallation> {
    error.value = null
    try {
      const installation = await skillApi.installFromMarketplace(marketplaceId)
      await fetchSkills()
      return installation
    } catch (e: any) {
      error.value = e.message ?? '从市场安装 Skill 失败'
      throw e
    }
  }

  async function testSkill(name: string, data: { userMessage: string; context?: Record<string, unknown> }) {
    error.value = null
    try {
      return await skillApi.test(name, data)
    } catch (e: any) {
      error.value = e.message ?? '测试 Skill 失败'
      throw e
    }
  }

  return {
    skills, currentSkill, mcpServers, serverTools, npxAvailable, loading, error,
    fetchSkills, fetchSkillDetail, unregisterSkill,
    fetchMcpServers, fetchMcpStatus, connectServer, disconnectServer, fetchServerTools,
    createSkill, updateSkill, updateSkillMarkdown, createMcpServer, updateMcpServer,
    enableSkill, disableSkill, setSkillEnabled, importPackage, installFromMarketplace, testSkill
  }
})
