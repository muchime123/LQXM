<template>
  <section class="guide">
    <div class="guide__head">
      <div>
        <p class="guide__eyebrow">IMPORT WORKFLOW</p>
        <h2>按业务依赖导入，不需要逐张表盲目上传</h2>
        <p>{{ modeDescription }}</p>
      </div>
      <el-segmented v-model="mode" :options="modeOptions" />
      <el-button class="guide__close" link @click="$emit('close')">收起</el-button>
    </div>

    <div class="guide__notice">
      <b>{{ mode === 'rebuild' ? '全量重导前必须先做保护快照' : '日常更新不需要清空历史数据' }}</b>
      <span>{{ mode === 'rebuild' ? '只有准备删除并重新导入全部数据时，才使用“重导准备”；建设期可选旧映射重新评估。' : '系统按稳定业务编号判断新增、更新和无变化，先预览再确认。' }}</span>
      <el-button v-if="mode === 'rebuild'" type="danger" plain size="small" @click="$emit('reset')">打开重导准备</el-button>
    </div>

    <ol class="guide__steps">
      <li v-for="step in activeSteps" :key="step.no" class="guide-step" :class="`guide-step--${step.tone}`">
        <span class="guide-step__no">{{ step.no }}</span>
        <div class="guide-step__body">
          <div class="guide-step__title">
            <b>{{ step.title }}</b>
            <el-tag v-if="step.optional" size="small" type="info" effect="plain">可选</el-tag>
          </div>
          <p>{{ step.description }}</p>
          <div v-if="step.actions?.length" class="guide-step__actions">
            <el-button
              v-for="action in step.actions"
              :key="action.label"
              size="small"
              :type="action.primary ? 'primary' : ''"
              :plain="!action.primary"
              @click="$emit('action', action.action)"
            >{{ action.label }}</el-button>
          </div>
        </div>
      </li>
    </ol>

    <div class="guide__rules">
      <b>统一规则</b>
      <span>同一次重导尽量使用同一种氚云导出方式</span>
      <span>每个文件都要先看新增/更新/无变化，再确认写入</span>
      <span>销售出库是客户、未收款和月度提成的权威来源</span>
      <span>生产工单、收款记录与多订单结清仅归档，不参与核算</span>
      <span>产品库存 = 产品信息 + 仓库明细两张台账的自动快照，导入完成即生效</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

export type H3GuideAction =
  | 'reset' | 'product' | 'stock' | 'dealer' | 'outbound' | 'production' | 'receipt' | 'settlement'
  | 'ledger' | 'customers' | 'commission'

defineEmits<{ close: []; reset: []; action: [action: H3GuideAction] }>()
const mode = ref<'rebuild' | 'daily'>('rebuild')
const modeOptions = [
  { label: '全量重导', value: 'rebuild' },
  { label: '日常更新', value: 'daily' }
]
const modeDescription = computed(() => mode.value === 'rebuild'
  ? '适用于删除旧氚云来源台账后，从统一导出文件重新建立全部数据。'
  : '适用于后续定期补充最新数据；只导入发生变化的数据类型即可。')
const importAction = (label: string, action: H3GuideAction, primary = false) => ({ label, action, primary })
// 全量重导 4 步：只保留「当前业务真正依赖」的路径。
// 生产工单、收款记录、多订单结清仅归档、不参与核算 —— 不进主流程，
// 需要时从导入中心的归档组进入。「库存对账」入口已下线：正式库存账现阶段无业务消费者，
// ERP 生产模块建设时恢复。
/** 指引步骤（显式声明，避免从数组字面量推断出「某些步骤没有 optional」这类脆弱类型） */
export interface H3GuideStep {
  no: number
  title: string
  tone: string
  description: string
  /** 标记为「可选」步骤（卡片右上角标签） */
  optional?: boolean
  actions: Array<{ label: string; action: H3GuideAction; primary: boolean }>
}
const rebuildSteps: H3GuideStep[] = [
  { no: 1, title: '清理旧来源', tone: 'guard', description: '选择保留或重新评估身份映射，创建保护快照后输入令牌执行来源重置。客户、员工和产品主档不会删除；已有提成核算版本引用来源时禁止重置。', actions: [importAction('重导准备', 'reset', true)] },
  { no: 2, title: '导入核心与基础数据', tone: 'core', description: '先导入产品信息与仓库明细（库存快照的两份来源，经销商按需），再导入销售出库——它是客户、金额、回款、未收款与提成的权威来源。', actions: [importAction('产品信息', 'product'), importAction('仓库明细', 'stock'), importAction('经销商信息', 'dealer'), importAction('导入销售出库', 'outbound', true)] },
  // 2026-10-06 下线「销售出库关联」工作台后，本步降为纯说明步骤（关联由导入流程自动完成，无人工入口）。
  { no: 3, title: '系统自动建立关联', tone: 'auto', description: '销售出库确认后自动识别客户、员工、产品与仓库，并刷新收款状态与利润结果。可以确定的关联不需要人工介入。', actions: [] },
  { no: 4, title: '处理剩余异常并核对结果', tone: 'check', description: '只处理系统无法确定的少量异常（缺客户、缺员工、退换货性质），然后核对未收款与月度提成。确认一致后本次重导完成。', actions: [importAction('查看历史台账', 'ledger'), importAction('月度提成核算', 'commission', true)] }
]
const dailySteps: H3GuideStep[] = [
  { no: 1, title: '导入最新销售出库', tone: 'core', description: '通常只需要更新销售出库。建议持续使用同一种导出方式，并在预览中重点检查“更新”记录。', actions: [importAction('导入销售出库', 'outbound', true)] },
  { no: 2, title: '按变化补充其他数据', tone: 'fact', optional: true, description: '只有产品、仓库或经销商发生变化时才上传对应文件，不要求每次全部导入。生产工单、收款记录与多订单结清仅在补历史时从「历史数据导入」归档组进入。', actions: [importAction('产品信息', 'product'), importAction('仓库明细', 'stock'), importAction('经销商信息', 'dealer')] },
  { no: 3, title: '处理异常并完成核对', tone: 'check', description: '检查批次错误与出库更新明细，然后核对本期未收款与月度提成。', actions: [importAction('月度提成核算', 'commission', true)] }
]
const activeSteps = computed(() => mode.value === 'rebuild' ? rebuildSteps : dailySteps)
</script>

<style scoped>
.guide { margin-bottom: 18px; padding: 20px; border: 1px solid var(--el-border-color-light); border-radius: 14px; background: var(--el-bg-color); box-shadow: var(--el-box-shadow-light); }
.guide__head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
.guide__close { flex: 0 0 auto; }
.guide__eyebrow { margin: 0 0 4px; color: var(--el-color-primary); font-size: 11px; font-weight: 700; letter-spacing: .12em; }
.guide h2 { margin: 0; font-size: 19px; }
.guide__head p:last-child { margin: 7px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.guide__notice { display: flex; align-items: center; gap: 12px; margin-top: 16px; padding: 10px 12px; border-radius: 9px; background: var(--el-fill-color-light); font-size: 13px; }
.guide__notice span { flex: 1; color: var(--el-text-color-secondary); }
.guide__steps { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 16px 0 0; padding: 0; list-style: none; }
.guide-step { display: flex; gap: 10px; min-height: 130px; padding: 13px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-blank); }
.guide-step__no { display: grid; place-items: center; width: 25px; height: 25px; flex: 0 0 auto; border-radius: 50%; color: #fff; background: var(--el-color-primary); font-size: 12px; font-weight: 700; }
.guide-step__body { min-width: 0; }
.guide-step__title { display: flex; gap: 6px; align-items: center; }
.guide-step__title b { font-size: 14px; }
.guide-step p { margin: 7px 0 10px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.55; }
.guide-step__actions { display: flex; flex-wrap: wrap; gap: 6px; }
.guide-step__actions :deep(.el-button + .el-button) { margin-left: 0; }
.guide-step--core { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.guide-step--guard .guide-step__no { background: var(--el-color-danger); }
.guide-step--optional { border-style: dashed; }
.guide__rules { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); color: var(--el-text-color-secondary); font-size: 12px; }
.guide__rules b { color: var(--el-text-color-primary); }
.guide__rules span::before { content: '✓'; margin-right: 5px; color: var(--el-color-success); }
@media (max-width: 1280px) { .guide__steps { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 720px) { .guide__head, .guide__notice { align-items: stretch; flex-direction: column; } .guide__steps { grid-template-columns: 1fr; } }
</style>
