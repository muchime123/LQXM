/**
 * LqTable 泛型类型契约（编译期）
 *
 * 这个文件**不是** Vitest 用例（文件名不含 .test/.spec，不会被 vitest 收集），
 * 它只被 `npm run typecheck`（vue-tsc --noEmit）检查。
 *
 * 背景：LqTable 使用 `<script setup lang="ts" generic="T extends Record<PropertyKey, any> = any">`，
 * 使 `data` 的元素类型推导到行事件载荷与 rowClassName 参数上。这是 B1 阶段那条
 * `it.skip` 编译期登记项的转正形态。
 *
 * ## 重要限制（已实测）
 * TypeScript **无法**在 `h(Component, props)` 调用中为泛型组件推导类型参数：
 * 此处 `T` 会退化为约束 `Record<PropertyKey, any>` 而非 `AssetRow`。
 * 因此本文件只能校验「结构兼容」类契约，**不能**校验推导结果。
 *
 * ## 推导在哪里被验证
 * vue-tsc 对 `.vue` 模板会正确推导：已实测在模板中写
 * `@row-click="row => row.definitelyNotAField"` 时，vue-tsc 报
 * `Property 'definitelyNotAField' does not exist on type 'AssetRow'`，
 * 证明 `row` 确实被推导为 `AssetRow`。
 * 因此推导能力的守护者是：**13 个真实调用方 + npm run typecheck 全绿**。
 *
 * 维护约定：若下列任一段无法通过类型检查，应当修复 LqTable，而不是放宽这里的断言。
 */
import { defineComponent, h } from 'vue'
import LqTable from '../LqTable.vue'

interface AssetRow {
  id: number
  name: string
}

// ① 行事件载荷接受具体的行类型（泛型存在时两者结构兼容）
export const RowEventPayloadAcceptsRowType = defineComponent({
  setup() {
    const rows: AssetRow[] = []

    return () =>
      h(LqTable, {
        data: rows,
        onRowClick: (row: AssetRow) => {
          void row.id
          void row.name
        },
        onRowDblclick: (row: AssetRow) => {
          void row.name
        },
        onSelectionChange: (selection: AssetRow[]) => {
          void selection.length
        }
      })
  }
})

// ② rowClassName 的行参数结构兼容
// 注：h() 场景下 T 退化为约束类型，因此这里只能校验结构，不能校验推导（见文件头说明）。
export const RowClassNameStructureIsCompatible = defineComponent({
  setup() {
    const rows: AssetRow[] = []

    return () =>
      h(LqTable, {
        data: rows,
        rowClassName: ({ row }: { row: Record<PropertyKey, any>; rowIndex: number }) =>
          row.id > 0 ? 'ok' : ''
      })
  }
})

// ③ 未推导出具体类型时退化为 any，与改造前一致（现有调用方零改动的前提）
export const FallsBackToAny = defineComponent({
  setup() {
    const rows: any[] = []

    return () =>
      h(LqTable, {
        data: rows,
        onRowClick: (row: any) => {
          void row
        }
      })
  }
})

// ④ 空态扩展 props 必须存在且取值受限
export const EmptyStatePropsAreTyped = defineComponent({
  setup() {
    return () =>
      h(LqTable, {
        data: [] as AssetRow[],
        emptyText: '没有资产',
        emptyHint: '点击右上角新增',
        emptySize: 'sm' as const,
        emptyIcon: '📄'
      })
  }
})

// 供 eslint 与 vue-tsc 确认这些组件确实被使用（它们只用于类型校验）
export const GenericContractFixtures = [
  RowEventPayloadAcceptsRowType,
  RowClassNameStructureIsCompatible,
  FallsBackToAny,
  EmptyStatePropsAreTyped
]
