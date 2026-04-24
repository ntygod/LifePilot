/**
 * 项目（Project）状态管理 —— Plan 1 Task 17。
 *
 * <p>沿用项目既有的 setup-store 风格（见 {@code workflow.ts}）：
 * <ul>
 *   <li>通过 {@code ref} 暴露 {@code projects / loading / error} 状态；</li>
 *   <li>异步方法捕获后端错误写入 {@code error}，失败时 {@code throw} 以便视图层处理；</li>
 *   <li>创建成功后置顶到列表（与"按创建时间倒序"的后端默认保持一致）；</li>
 *   <li>更新 / 删除仅在列表中就地替换 / 移除，避免全量刷新。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  CreateProjectRequest,
  ProjectDto,
  UpdateProjectRequest,
} from '@/api/project'
import * as projectApi from '@/api/project'

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ProjectDto[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 拉取项目列表并保存 */
  async function fetchProjects(): Promise<ProjectDto[]> {
    loading.value = true
    error.value = null
    try {
      const items = await projectApi.listProjects()
      projects.value = items
      return items
    } catch (e: any) {
      error.value = e?.message ?? '加载项目列表失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  /** 创建项目 —— 成功后追加到列表开头（与后端倒序排序保持一致） */
  async function createProject(req: CreateProjectRequest): Promise<ProjectDto> {
    error.value = null
    try {
      const created = await projectApi.createProject(req)
      projects.value = [created, ...projects.value]
      return created
    } catch (e: any) {
      error.value = e?.message ?? '创建项目失败'
      throw e
    }
  }

  /** 更新项目 —— 成功后就地替换列表对应项 */
  async function updateProject(
    id: string,
    req: UpdateProjectRequest,
  ): Promise<ProjectDto> {
    error.value = null
    try {
      const updated = await projectApi.updateProject(id, req)
      const index = projects.value.findIndex(p => p.id === id)
      if (index !== -1) {
        const next = [...projects.value]
        next[index] = updated
        projects.value = next
      }
      return updated
    } catch (e: any) {
      error.value = e?.message ?? '更新项目失败'
      throw e
    }
  }

  /** 删除项目 —— 成功后从列表移除（后端负责级联清理记忆与会话） */
  async function deleteProject(id: string): Promise<void> {
    error.value = null
    try {
      await projectApi.deleteProject(id)
      projects.value = projects.value.filter(p => p.id !== id)
    } catch (e: any) {
      error.value = e?.message ?? '删除项目失败'
      throw e
    }
  }

  return {
    projects,
    loading,
    error,
    fetchProjects,
    createProject,
    updateProject,
    deleteProject,
  }
})
