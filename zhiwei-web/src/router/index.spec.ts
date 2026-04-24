/**
 * Plan 2+3 路由状态验证。
 *
 * <p>锁定关键路由变更，防止后续误重新注册：
 * <ul>
 *   <li>/scheduled-tasks 存在（Plan 2 Task A5）</li>
 *   <li>/datastores 和 /datastores/:id 已下架（Plan 3 Task B2，组件文件保留但路由不挂载）</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-04-23
 */
import { describe, it, expect } from 'vitest'
import router from './index'

describe('router Plan 2+3 状态', () => {
  it('/scheduled-tasks 路由已注册（Plan 2 Task A5）', () => {
    const route = router.resolve('/scheduled-tasks')
    expect(route.name).toBe('scheduledTasks')
  })

  it('/datastores 路由已下架（Plan 3 Task B2）', () => {
    const route = router.resolve('/datastores')
    // 命中的不应是 datastores 命名路由
    expect(route.name).not.toBe('datastores')
  })

  it('/datastores/:id 路由已下架（Plan 3 Task B2）', () => {
    const route = router.resolve('/datastores/ds-abc')
    expect(route.name).not.toBe('datastoreDetail')
  })

  it('/projects/:id 路由仍然存在（Plan 1 Task 20）', () => {
    const route = router.resolve('/projects/p-123')
    expect(route.name).toBe('projectDetail')
  })
})
