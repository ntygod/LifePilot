import type { StepModel, StepType } from './useWorkflowModel'

/**
 * 畫布節點的分支歸屬
 * - 'root': 頂層步驟（直接在工作流 steps 列表中）
 * - 'then': 條件分支的 thenSteps
 * - 'else': 條件分支的 elseSteps
 * - 'loop': 循環步驟的 body
 * - 'branch-N': 並行步驟的第 N 個分支
 */
export type BranchType = 'root' | 'then' | 'else' | 'loop' | `branch-${number}`

/**
 * 畫布節點（扁平化後的步驟表示）
 */
export interface CanvasNode {
  /** 畫布上的唯一 ID（可能與原始 step.id 不同） */
  canvasId: string
  /** 原始步驟 ID */
  stepId: string
  /** 步驟名稱 */
  name: string
  /** 步驟類型 */
  type: StepType
  /** 歸屬的分支 */
  branch: BranchType
  /** 父級步驟 ID（如果是分支內的步驟） */
  parentStepId: string | null
  /** 依賴於哪些畫布節點（扁平化後的 ID） */
  dependsOn: string[]
  /** 原始步驟對象的引用（用於配置編輯） */
  rawStep: StepModel
}

/**
 * 將嵌套的 steps 轉為扁平的畫布節點列表。
 * 
 * 處理以下類型的嵌套：
 * - condition: thenSteps, elseSteps
 * - loop: body
 * - parallel: branches[]
 * 
 * @param steps 頂層步驟列表（來自工作流的 steps 字段）
 * @returns 扁平的畫布節點數組
 */
export function flattenNestedSteps(steps: StepModel[]): CanvasNode[] {
  const canvasNodes: CanvasNode[] = []
  
  // 遞迴處理嵌套步驟
  function processStep(step: StepModel, branch: BranchType, parentId: string | null, parentDependsOn: string[]) {
    // 為當前步驟生成畫布 ID
    const canvasId = branch === 'root' ? step.id : `${parentId}-${branch}-${step.id}`
    
    // 計算當前步驟的依賴
    // 分支內的第一步默認依賴於父條件節點
    const currentDependsOn: string[] = []
    
    if (branch !== 'root') {
      // 分支內的步驟默認依賴父節點
      if (parentId) {
        currentDependsOn.push(parentId)
      }
    }
    
    // 加上原始的外部依賴（指向其他頂層步驟）
    for (const depId of step.dependsOn) {
      // 需要找到depId對應的畫布ID（如果是頂層步驟，畫布ID就是step.id）
      const depStep = steps.find(s => s.id === depId)
      if (depStep) {
        // 外部依賴仍然指向頂層節點
        currentDependsOn.push(depId)
      }
    }
    
    const node: CanvasNode = {
      canvasId,
      stepId: step.id,
      name: step.name,
      type: step.type,
      branch,
      parentStepId: parentId,
      dependsOn: currentDependsOn,
      rawStep: step,
    }
    
    canvasNodes.push(node)
    
    // 遞迴處理嵌套的步驟
    const config = step.config as any
    
    // 條件分支
    if (step.type === 'condition' && config) {
      if (config.thenSteps && Array.isArray(config.thenSteps)) {
        for (const thenStep of config.thenSteps) {
          processStep(thenStep, 'then', canvasId, [canvasId])
        }
      }
      if (config.elseSteps && Array.isArray(config.elseSteps)) {
        for (const elseStep of config.elseSteps) {
          processStep(elseStep, 'else', canvasId, [canvasId])
        }
      }
    }
    
    // 循環步驟
    if (step.type === 'loop' && config) {
      if (config.body && Array.isArray(config.body)) {
        for (const loopStep of config.body) {
          processStep(loopStep, 'loop', canvasId, [canvasId])
        }
      }
    }
    
    // 並行步驟
    if (step.type === 'parallel' && config) {
      if (config.branches && Array.isArray(config.branches)) {
        config.branches.forEach((branchSteps: StepModel[], branchIndex: number) => {
          const branchType = `branch-${branchIndex}` as BranchType
          for (const branchStep of branchSteps) {
            processStep(branchStep, branchType, canvasId, [canvasId])
          }
        })
      }
    }
  }
  
  // 處理所有頂層步驟
  for (const step of steps) {
    processStep(step, 'root', null, [])
  }
  
  return canvasNodes
}

/**
 * 獲取條件分支節點的出口錨點信息
 */
export interface ConditionExits {
  then: string | null  // Then 分支第一個節點的 canvasId
  else: string | null  // Else 分支第一個節點的 canvasId
}

/**
 * 根據畫布節點列表，獲取每個條件節點的 Then/Else 出口連接目標
 */
export function getConditionExits(canvasNodes: CanvasNode[]): Map<string, ConditionExits> {
  const exitsMap = new Map<string, ConditionExits>()
  
  for (const node of canvasNodes) {
    if (node.type === 'condition') {
      // 查找 then 分支的第一個節點
      const thenNode = canvasNodes.find(n => n.parentStepId === node.canvasId && n.branch === 'then')
      // 查找 else 分支的第一個節點
      const elseNode = canvasNodes.find(n => n.parentStepId === node.canvasId && n.branch === 'else')
      
      exitsMap.set(node.canvasId, {
        then: thenNode?.canvasId ?? null,
        else: elseNode?.canvasId ?? null,
      })
    }
  }
  
  return exitsMap
}
