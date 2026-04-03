/**
 * 错误处理 Composable
 * 提供统一的错误处理逻辑
 */

import { ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ErrorResponse } from '@/types'
import { NetworkError, TimeoutError } from '@/api/client'
import { logger } from '@/utils/logger'

/** 错误处理选项 */
export interface ErrorHandlerOptions {
  /** 是否显示错误提示，默认 true */
  showToast?: boolean
  /** 是否记录错误日志，默认 true */
  logError?: boolean
  /** 404 错误时是否自动导航，默认 false */
  navigateOn404?: boolean
  /** 404 错误时的导航路径，默认 '/extensions/skills' */
  navigatePath?: string
  /** 401 错误时是否自动跳转登录，默认 false */
  redirectOn401?: boolean
  /** 401 错误时的登录路径，默认 '/login' */
  loginPath?: string
}

/**
 * 错误处理 Composable
 */
export function useErrorHandler(options: ErrorHandlerOptions = {}) {
  const router = useRouter()
  const {
    showToast = true,
    logError = true,
    navigateOn404 = false,
    navigatePath = '/extensions/skills',
    redirectOn401 = false,
    loginPath = '/login'
  } = options

  const formError = ref<string | null>(null)
  const fieldErrors = ref<Record<string, string>>({})

  /**
   * 处理 API 错误
   */
  function handleApiError(error: any): string {
    let errorMessage = '操作失败'

    // 处理不同类型的错误
    if (error instanceof NetworkError) {
      errorMessage = '网络连接失败，请检查网络设置'
    } else if (error instanceof TimeoutError) {
      errorMessage = error.message || '请求超时，请稍后重试'
    } else if (error?.code) {
      // ErrorResponse 类型
      const errorResponse = error as ErrorResponse

      switch (errorResponse.code) {
        case 400:
          errorMessage = errorResponse.message || '请求参数错误'
          break
        case 401:
          errorMessage = '未授权访问，请重新登录'
          if (redirectOn401) {
            router.push(loginPath)
          }
          break
        case 403:
          errorMessage = '没有权限访问该资源'
          break
        case 404:
          errorMessage = '资源不存在'
          if (navigateOn404) {
            router.push(navigatePath)
          }
          break
        case 409:
          errorMessage = errorResponse.message || '资源冲突，可能已存在'
          break
        case 422:
          errorMessage = errorResponse.message || '数据验证失败'
          break
        case 500:
        case 502:
        case 503:
        case 504:
          errorMessage = '服务器错误，请稍后重试'
          break
        default:
          errorMessage = errorResponse.message || '操作失败'
      }
    } else if (error?.message) {
      errorMessage = error.message
    }

    // 记录错误日志
    if (logError) {
      logger.error('API Error:', error)
    }

    return errorMessage
  }

  /**
   * 处理表单验证错误
   */
  function handleValidationError(errors: Record<string, string> | string) {
    if (typeof errors === 'string') {
      formError.value = errors
      fieldErrors.value = {}
    } else {
      formError.value = null
      fieldErrors.value = errors
    }
  }

  /**
   * 清除错误
   */
  function clearErrors() {
    formError.value = null
    fieldErrors.value = {}
  }

  /**
   * 设置字段错误
   */
  function setFieldError(field: string, message: string) {
    fieldErrors.value[field] = message
  }

  /**
   * 清除字段错误
   */
  function clearFieldError(field: string) {
    delete fieldErrors.value[field]
  }

  /**
   * 获取字段错误
   */
  function getFieldError(field: string): string | undefined {
    return fieldErrors.value[field]
  }

  /**
   * 检查是否有错误
   */
  function hasErrors(): boolean {
    return formError.value !== null || Object.keys(fieldErrors.value).length > 0
  }

  return {
    formError,
    fieldErrors,
    handleApiError,
    handleValidationError,
    clearErrors,
    setFieldError,
    clearFieldError,
    getFieldError,
    hasErrors
  }
}
