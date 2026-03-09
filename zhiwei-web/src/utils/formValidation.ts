/**
 * 表单验证工具
 * 提供统一的表单验证错误处理机制
 */

/** 字段验证规则 */
export interface ValidationRule {
  /** 验证函数，返回错误消息或 null */
  validator: (value: any) => string | null
  /** 是否必填 */
  required?: boolean
}

/** 表单验证规则集合 */
export type ValidationRules<T extends Record<string, any>> = {
  [K in keyof T]?: ValidationRule | ValidationRule[]
}

/** 验证错误集合 */
export type ValidationErrors<T extends Record<string, any>> = {
  [K in keyof T]?: string
}

/** 表单级错误 */
export interface FormError {
  message: string
  code?: string
}

/**
 * 验证单个字段
 */
export function validateField<T extends Record<string, any>>(
  value: any,
  rules: ValidationRule | ValidationRule[]
): string | null {
  const ruleArray = Array.isArray(rules) ? rules : [rules]

  for (const rule of ruleArray) {
    // 检查必填
    if (rule.required && (value === null || value === undefined || value === '')) {
      return '此字段为必填项'
    }

    // 如果值为空且不是必填，跳过其他验证
    if (!rule.required && (value === null || value === undefined || value === '')) {
      continue
    }

    // 执行验证器
    const error = rule.validator(value)
    if (error) {
      return error
    }
  }

  return null
}

/**
 * 验证整个表单
 */
export function validateForm<T extends Record<string, any>>(
  data: T,
  rules: ValidationRules<T>
): ValidationErrors<T> {
  const errors: ValidationErrors<T> = {}

  for (const [field, fieldRules] of Object.entries(rules)) {
    if (!fieldRules) continue

    const value = data[field as keyof T]
    const error = validateField(value, fieldRules)
    if (error) {
      errors[field as keyof T] = error
    }
  }

  return errors
}

/**
 * 常用验证规则
 */
export const commonRules = {
  /** 必填 */
  required: (message: string = '此字段为必填项'): ValidationRule => ({
    required: true,
    validator: (value: any) => {
      if (value === null || value === undefined || value === '') {
        return message
      }
      return null
    }
  }),

  /** 字符串最小长度 */
  minLength: (min: number, message?: string): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && value.length < min) {
        return message || `长度不能少于 ${min} 个字符`
      }
      return null
    }
  }),

  /** 字符串最大长度 */
  maxLength: (max: number, message?: string): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && value.length > max) {
        return message || `长度不能超过 ${max} 个字符`
      }
      return null
    }
  }),

  /** 正则表达式 */
  pattern: (regex: RegExp, message: string): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && !regex.test(value)) {
        return message
      }
      return null
    }
  }),

  /** 邮箱格式 */
  email: (message: string = '请输入有效的邮箱地址'): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && value) {
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
        if (!emailRegex.test(value)) {
          return message
        }
      }
      return null
    }
  }),

  /** URL 格式 */
  url: (message: string = '请输入有效的 URL'): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && value) {
        try {
          new URL(value)
        } catch {
          return message
        }
      }
      return null
    }
  }),

  /** 数字范围 */
  range: (min: number, max: number, message?: string): ValidationRule => ({
    validator: (value: any) => {
      const num = Number(value)
      if (isNaN(num) || num < min || num > max) {
        return message || `值必须在 ${min} 到 ${max} 之间`
      }
      return null
    }
  }),

  /** 正整数 */
  positiveInteger: (message: string = '请输入正整数'): ValidationRule => ({
    validator: (value: any) => {
      const num = Number(value)
      if (isNaN(num) || num <= 0 || !Number.isInteger(num)) {
        return message
      }
      return null
    }
  }),

  /** JSON 格式 */
  json: (message: string = '请输入有效的 JSON 格式'): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && value.trim()) {
        try {
          JSON.parse(value)
        } catch {
          return message
        }
      }
      return null
    }
  }),

  /** ID 格式（小写字母、数字、连字符、下划线） */
  idFormat: (message: string = 'ID 只能包含小写字母、数字、连字符和下划线'): ValidationRule => ({
    validator: (value: any) => {
      if (typeof value === 'string' && value) {
        if (!/^[a-z0-9-_]+$/.test(value)) {
          return message
        }
      }
      return null
    }
  })
}
