import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * B7-B1c：表单生命周期与异步回填契约。
 *
 * 为什么是源码级而不是挂载式：与本目录其它契约测试同因——在本仓库里新建
 * `src/apps/archive/views/` 下的 `__tests__` 并挂载业务页面，会在收集期触发
 * Vitest 崩溃（根因见 B7-B0：Windows 盘符大小写导致模块双实例）。
 * 行为验证由 `e2e/b7-b1c-form-lifecycle.spec.ts` 在真实浏览器 +
 * 受控延迟 / 乱序响应上承担。
 *
 * 这里钉死的是**守卫契约**，不是具体命名：
 *   1. 异步回填前必须建立「请求身份」；
 *   2. 每个 await 之后都要校验身份（关闭 / 切对象 / 卸载后旧响应必须被丢弃）；
 *   3. 组件卸载必须置失效标记；
 *   4. 带校验规则的弹窗表单，打开时必须清掉上一次的错误态，
 *      且只能用 clearValidate，不能用会覆盖行数据的 resetFields。
 */

const MOLD = '../../../components/mold/MoldFormDialog.vue'
const PACKAGING = '../../../components/packaging/PackagingEditorModal.vue'
const EMPLOYEE = '../../../components/employee/EmployeeDetailPanel.vue'

function read(relative: string): string {
  return readFileSync(resolve(__dirname, relative), 'utf8')
}

/** 取出 `function NAME(...) { ... }` 的源码（按花括号配平，跳过字符串） */
function functionSource(source: string, name: string): string {
  const match = source.match(new RegExp(`function\\s+${name}\\s*\\(`))
  if (!match || match.index === undefined) return ''
  const bodyStart = source.indexOf('{', match.index)
  if (bodyStart < 0) return ''
  let depth = 0
  let inStr: string | null = null
  for (let i = bodyStart; i < source.length; i++) {
    const ch = source[i]
    if (inStr) {
      if (ch === inStr && source[i - 1] !== '\\') inStr = null
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      inStr = ch
      continue
    }
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return source.slice(bodyStart, i + 1)
    }
  }
  return ''
}

/** 取出以 `NAME(async () => {` 开头的代码块源码 */
function hookSource(source: string, name: string): string {
  const match = source.match(new RegExp(`${name}\\((?:async\\s*)?\\(\\)\\s*=>`))
  if (!match || match.index === undefined) return ''
  const bodyStart = source.indexOf('{', match.index)
  if (bodyStart < 0) return ''
  let depth = 0
  let inStr: string | null = null
  for (let i = bodyStart; i < source.length; i++) {
    const ch = source[i]
    if (inStr) {
      if (ch === inStr && source[i - 1] !== '\\') inStr = null
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      inStr = ch
      continue
    }
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) return source.slice(bodyStart, i + 1)
    }
  }
  return ''
}

describe('表单生命周期与异步回填契约（B7-B1c）', () => {
  describe('MoldFormDialog（C1）', () => {
    const source = read(MOLD)

    it('回填前建立请求身份', () => {
      expect(source).toContain('const seq = ++editLoadSeq')
    })

    it('两次 await 之后都校验身份（关闭 / 切模具 / 卸载后旧响应丢弃）', () => {
      const body = functionSource(source, 'loadForEdit')
      expect(body, '未取到 loadForEdit 源码').not.toBe('')
      const checks = [...body.matchAll(/seq\s*!==\s*editLoadSeq/g)].length
      expect(checks, '每次 await 之后都应校验请求身份').toBeGreaterThanOrEqual(2)
    })

    it('组件卸载后使旧请求失效', () => {
      expect(source).toContain('onUnmounted(')
      expect(source).toContain('disposed')
    })

    it('回填不覆盖用户已编辑的字段', () => {
      expect(source).toContain('mergeLoadedMoldForm')
      expect(source).toContain('hasUserEdits')
    })

    it('打开弹窗时清除上一次的校验错误态', () => {
      const body = hookSource(source, 'onMounted')
      expect(body, '未取到 onMounted 源码').not.toBe('')
      expect(body).toContain('clearFormValidation')
    })
  })

  describe('PackagingEditorModal（C2）', () => {
    const source = read(PACKAGING)

    it('回填前记录本次请求针对的 modelId', () => {
      expect(source).toContain('const requestedId = props.modelId')
    })

    it('await 之后校验 modelId 与卸载标记（旧响应丢弃）', () => {
      const body = hookSource(source, 'onMounted')
      expect(body, '未取到 onMounted 源码').not.toBe('')
      expect(body).toMatch(/props\.modelId\s*!==\s*requestedId/)
      expect(body).toContain('disposed')
    })

    it('回填不覆盖用户已编辑的字段', () => {
      expect(source).toContain('mergeLoadedForm')
    })

    it('保存载荷由显式白名单产出，不展开整个 form', () => {
      expect(source).toContain('EDITABLE_MODEL_FIELDS')
      expect(source, 'cleanPayload 不应继续展开整个 form').not.toContain('Object.entries(form)')
    })

    /**
     * 后端 `PackagingService.updateModel(Long id, PackagingModel dto)` 逐字段白名单赋值，
     * 共 22 个业务字段；`defaultImage` 在列 —— 白名单漏掉它会导致更新时被清空。
     */
    it('白名单包含后端会回写的业务字段 defaultImage', () => {
      const block = source.match(/const EDITABLE_MODEL_FIELDS = \[([\s\S]*?)\]\s*as const/)
      expect(block, '未找到 EDITABLE_MODEL_FIELDS 定义').toBeTruthy()
      expect(block![1]).toContain("'defaultImage'")
    })
  })

  describe('EmployeeDetailPanel（C3）', () => {
    const source = read(EMPLOYEE)

    it('新增 / 编辑两个打开入口都清除上一次的校验错误态', () => {
      const add = functionSource(source, 'openAddMapping')
      const edit = functionSource(source, 'openEditMapping')
      expect(add, '未取到 openAddMapping 源码').not.toBe('')
      expect(edit, '未取到 openEditMapping 源码').not.toBe('')
      expect(add).toContain('clearMappingValidation()')
      expect(edit).toContain('clearMappingValidation()')
    })

    it('清错误态用 clearValidate，而非会覆盖行数据的 resetFields', () => {
      expect(source).toMatch(/mappingFormRef\.value\?\.clearValidate\(\)/)
      expect(source).not.toMatch(/mappingFormRef\.value\?\.resetFields\(/)
      expect(source).not.toMatch(/formRef\.value\?\.resetFields\(/)
    })
  })
})
