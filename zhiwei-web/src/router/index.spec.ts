/**
 * 核心路由状态验证。
 *
 * <p>锁定关键入口，防止后续误删。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
import { describe, it, expect } from 'vitest'
import router from './index'

describe('router 核心入口状态', () => {
  it('/scheduled-tasks 路由已注册', () => {
    const route = router.resolve('/scheduled-tasks')
    expect(route.name).toBe('scheduledTasks')
  })

  it('/projects/:id 路由仍然存在', () => {
    const route = router.resolve('/projects/p-123')
    expect(route.name).toBe('projectDetail')
  })
})
