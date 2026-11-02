import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * B7-B2b-1：权限入口门控的源码级契约测试。
 *
 * 用途：把三个修复对象的「权限表达」固定成断言，防止后续改动被回退。
 *
 * 这些断言只约束**前端权限表达是否一致**，
 * **不**声称隐藏按钮构成安全边界 —— 后端 `PermissionInterceptor`
 * 对每个写接口独立强制授权，那才是真正的边界。
 *
 * 为何用源码断言而非浏览器：本批两个列表页（`MediaAccountView` /
 * `ProductCatalogView`）在 `script setup` 顶层 `await` 拉取列表与筛选选项，
 * 隔离夹具下挂载链路依赖较多，首轮驱动失败（详见 B7-B2b-1 报告 §9）。
 * 这里先用源码契约兜住口径，浏览器用例下一批补齐。
 */

const ROOT = join(process.cwd(), 'src')

function read(relPath: string): string {
  return readFileSync(join(ROOT, relPath), 'utf8')
}

describe('PermissionEntryGates · EmployeeDetailPanel', () => {
  const src = read('components/employee/EmployeeDetailPanel.vue')

  it('canManage 默认值为 false（fail-closed），不得默认放开写操作', () => {
    // 定位 withDefaults 的默认值对象，断言 canManage 为 false
    expect(src).toMatch(/withDefaults\(\s*defineProps</)
    expect(src).toMatch(/\{\s*canManage:\s*false\s*,\s*filterOptions:\s*null\s*\}/)
    expect(src).not.toMatch(/canManage:\s*true/)
  })

  it('头部写操作「编辑档案 / 删除离职」由 canManage 门控', () => {
    expect(src).toMatch(/<el-button\s+v-if="canManage"[^>]*>\s*编辑档案/)
    expect(src).toMatch(/<el-button\s+v-if="canManage"[^>]*>\s*删除 \/ 离职/)
  })

  it('身份映射「新增映射」与操作列由 canManage 门控', () => {
    expect(src).toMatch(/<el-button\s+v-if="canManage"[^>]*@click="openAddMapping"/)
    expect(src).toMatch(/<el-table-column\s+v-if="canManage"\s+label="操作"/)
  })

  it('写操作函数内部仍有 canManage 二次校验（不依赖单一开关）', () => {
    expect(src).toMatch(/if \(!detail\.value \|\| !props\.canManage\) return/)
  })
})

describe('PermissionEntryGates · 父级显式传参', () => {
  const roster = read('apps/archive/views/system/EmployeeRosterView.vue')

  it('EmployeeRosterView 显式传入 :can-manage，且口径为 system.employee.manage', () => {
    expect(roster).toMatch(/:can-manage="canManageEmployee"/)
    expect(roster).toMatch(/const canManageEmployee = computed\(\(\) => can\('system\.employee\.manage'\)\)/)
  })
})

describe('PermissionEntryGates · 行级操作与顶部同口径', () => {
  const media = read('apps/archive/views/marketing/MediaAccountView.vue')

  it('行级写按钮（＋更新 / 编辑 / 删除）由 canManageMedia 门控', () => {
    expect(media).toMatch(/<button\s+v-if="canManageMedia"[^>]*@click="quickAddUpdate\(row\)"/)
    expect(media).toMatch(/<button\s+v-if="canManageMedia"[^>]*@click="openEditor\(row\)"/)
    expect(media).toMatch(/<button\s+v-if="canManageMedia"[^>]*@click="removeAccount\(row\)"/)
  })

  it('「详情」是读操作，不门控 —— 只读用户仍可查看', () => {
    expect(media).toMatch(/<button type="button" class="row-btn" @click="openDetail\(row\)">详情<\/button>/)
  })

  it('canManageMedia 口径为 media.manage（与后端 Controller 注解一致）', () => {
    expect(media).toMatch(/const canManageMedia = computed\(\(\) => can\('media\.manage'\)\)/)
  })
})

describe('PermissionEntryGates · 批量入口按权限暴露', () => {
  const catalog = read('apps/archive/views/product/ProductCatalogView.vue')

  it('batch-enabled 绑定 canManageProduct，不得写常量', () => {
    expect(catalog).toMatch(/:batch-enabled="canManageProduct"/)
    // 排除仍写成无值属性的形态
    expect(catalog).not.toMatch(/^\s*batch-enabled\s*$/m)
  })

  it('行级「删除产品」与顶部同口径（product.manage）', () => {
    expect(catalog).toMatch(/<button\s+v-if="canManageProduct"[^>]*@click="handleTitleMenuRemove"/)
    expect(catalog).toMatch(/const canManageProduct = computed\(\(\) => can\('product\.manage'\)\)/)
  })
})

// ============================================================
// 正确范式回归：不得误伤已有正确实现
// ============================================================

describe('PermissionEntryGates · 既有正确范式不回归', () => {
  it('CustomerView 仍绑定 :batch-enabled="canManageCustomer"', () => {
    expect(read('apps/archive/views/customer/CustomerView.vue')).toMatch(
      /:batch-enabled="canManageCustomer"/
    )
  })

  it('OfficeAssetView 仍绑定 :batch-enabled="canManageOfficeAsset"（B6-2 试点成果）', () => {
    expect(read('apps/archive/views/product/OfficeAssetView.vue')).toMatch(
      /:batch-enabled="canManageOfficeAsset"/
    )
  })

  it('ManufacturingTaskConfigPanel 的权限 prop 仍是必填无默认值（正确范式）', () => {
    const src = read('components/production/ManufacturingTaskConfigPanel.vue')
    expect(src).toMatch(/canManageBaseline:\s*boolean/)
    expect(src).not.toMatch(/canManageBaseline\?:/)
  })
})
