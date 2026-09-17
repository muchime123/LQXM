import { computed, reactive, toRaw, watch, type ComputedRef } from 'vue'
import type { ImportPreview, ImportRow, PackagingModelSummary, VariantMatchCandidate } from '@/api'

export interface MoldImportPreviewTableProps {
  preview: ImportPreview
  filteredRows: ImportRow[]
  packagingModels: PackagingModelSummary[]
  variantOverrides: Record<number, number | null>
  modelOverrides: Record<number, number>
  warningsCollapsed: boolean
}

export interface MoldImportPreviewTableEmit {
  (event: 'override-variant', globalIdx: number, variantId: number | null): void
  (event: 'override-model', globalIdx: number, modelId: number): void
  (event: 'selection-change' | 'select-mold', globalIndexes: number[]): void
}

interface MatchRowState {
  globalIndex: number
  modelId?: number
  candidates: VariantMatchCandidate[]
  selectedId: number | null
  selectedCandidate?: VariantMatchCandidate
  isLoose: boolean
}

const DIFF_FIELD_LABELS: Record<string, string> = {
  length_mm: '长',
  width_mm: '宽',
  top_diameter_mm: '口径',
  height_mm: '高度',
  cross_shape: '形状交叉'
}

export function useMoldImportPreviewTable(
  props: Readonly<MoldImportPreviewTableProps>,
  emit: MoldImportPreviewTableEmit
) {
  const collapsedMolds = reactive(new Set<string>())
  const localVariantOverrides = reactive<Record<number, number | null>>({})

  const globalIndexByRow = computed(() => {
    const indexes = new Map<ImportRow, number>()
    props.preview.rows.forEach((row, index) => indexes.set(toRaw(row), index))
    return indexes
  })

  const groupIndexes = computed(() => {
    const groups = new Map<string, number[]>()
    props.preview.rows.forEach((row, index) => {
      if (!row.moldCode) return
      const indexes = groups.get(row.moldCode) ?? []
      indexes.push(index)
      groups.set(row.moldCode, indexes)
    })
    return groups
  })

  const filteredGroupCounts = computed(() => {
    const counts = new Map<string, number>()
    props.filteredRows.forEach(row => {
      if (row.moldCode) counts.set(row.moldCode, (counts.get(row.moldCode) ?? 0) + 1)
    })
    return counts
  })

  const visibleRows = computed(() => props.filteredRows.filter((row, index, rows) =>
    !row.moldCode || !collapsedMolds.has(row.moldCode) || rows[index - 1]?.moldCode !== row.moldCode
  ))

  function syncLocalOverrides() {
    Object.keys(localVariantOverrides).forEach(key => delete localVariantOverrides[Number(key)])
    props.preview.rows.forEach((row, index) => {
      localVariantOverrides[index] = row.selectedVariantId ?? null
    })
    Object.assign(localVariantOverrides, props.variantOverrides)
  }

  watch([() => props.preview.rows, () => props.variantOverrides], syncLocalOverrides, {
    deep: true,
    immediate: true
  })

  function globalRowIndex(row: ImportRow) {
    const direct = globalIndexByRow.value.get(toRaw(row))
    if (direct != null) return direct
    return props.preview.rows.findIndex(item =>
      item.sourceRowNumber === row.sourceRowNumber
      && item.moldCode === row.moldCode
      && item.heightMm === row.heightMm
      && item.capacityMl === row.capacityMl
    )
  }

  const rowStates: ComputedRef<Map<number, MatchRowState>> = computed(() => {
    const states = new Map<number, MatchRowState>()
    props.preview.rows.forEach((row, globalIndex) => {
      const modelId = props.modelOverrides[globalIndex] ?? props.preview.modelId ?? row.matchedModelId
      const candidates = (row.matchCandidates ?? []).filter(candidate => modelId == null || candidate.modelId === modelId)
      const selectedId = localVariantOverrides[globalIndex] ?? null
      const selectedCandidate = candidates.find(candidate => candidate.variantId === selectedId)
      states.set(globalIndex, {
        globalIndex,
        modelId,
        candidates,
        selectedId,
        selectedCandidate,
        isLoose: selectedCandidate ? isLooseCandidate(selectedCandidate) : candidates.length === 1 && isLooseCandidate(candidates[0])
      })
    })
    return states
  })

  function matchState(row: ImportRow): MatchRowState {
    const globalIndex = globalRowIndex(row)
    return rowStates.value.get(globalIndex) ?? {
      globalIndex,
      candidates: [],
      selectedId: null,
      isLoose: false
    }
  }

  const matchSummary = computed(() => {
    let matched = 0
    let multi = 0
    let none = 0
    rowStates.value.forEach(state => {
      if (state.selectedCandidate) matched++
      else if (state.candidates.length > 1) multi++
      else none++
    })
    return { matched, multi, none }
  })

  function commitOverride(row: ImportRow, variantId: number | null | undefined) {
    const index = globalRowIndex(row)
    if (index >= 0) emit('override-variant', index, variantId ?? null)
  }

  function setModelOverride(row: ImportRow, modelId: number) {
    const index = globalRowIndex(row)
    if (index >= 0) emit('override-model', index, modelId)
  }

  function toggleMold(row: ImportRow) {
    if (!row.moldCode) return
    if (collapsedMolds.has(row.moldCode)) collapsedMolds.delete(row.moldCode)
    else collapsedMolds.add(row.moldCode)
  }

  function selectMold(row: ImportRow) {
    if (!row.moldCode) return
    emit('select-mold', groupIndexes.value.get(row.moldCode) ?? [])
  }

  function moldRowCount(row: ImportRow) {
    return row.moldCode ? filteredGroupCounts.value.get(row.moldCode) ?? 1 : 1
  }

  function spanMethod({ rowIndex, columnIndex }: { rowIndex: number; columnIndex: number }) {
    if (columnIndex < 1 || columnIndex > 6) return [1, 1]
    const current = visibleRows.value[rowIndex]
    if (!current?.moldCode) return [1, 1]
    if (visibleRows.value[rowIndex - 1]?.moldCode === current.moldCode) return [0, 0]
    let count = 1
    while (visibleRows.value[rowIndex + count]?.moldCode === current.moldCode) count++
    return [count, 1]
  }

  function onSelectionChange(rows: ImportRow[]) {
    const selected = new Set(rows.map(globalRowIndex).filter(index => index >= 0))
    emit('selection-change', [...selected])
  }

  return {
    collapsedMolds,
    localVariantOverrides,
    visibleRows,
    matchSummary,
    globalRowIndex,
    matchState,
    commitOverride,
    setModelOverride,
    toggleMold,
    selectMold,
    moldRowCount,
    spanMethod,
    onSelectionChange,
    dispLabel,
    rowClassName,
    formatCandidateLabel,
    formatMatchReason
  }
}

function dispLabel(disposition: ImportRow['disposition']) {
  return { new: '新增', merge: '合并', conflict: '冲突', unverified: '待核实' }[disposition] ?? '未知'
}

function rowClassName({ row }: { row: ImportRow }) {
  return row.disposition === 'unverified' || row.disposition === 'conflict' ? `row-${row.disposition}` : ''
}

function isLooseCandidate(candidate: { diffFields?: string[] }) {
  return candidate.diffFields?.some(field => field.endsWith('~loose')) ?? false
}

function formatDiffFields(fields?: string[]) {
  return (fields ?? []).map(field => {
    const loose = field.endsWith('~loose')
    const key = loose ? field.slice(0, -6) : field
    const label = DIFF_FIELD_LABELS[key] ?? key
    return loose ? `${label}近似` : label
  }).join('、')
}

function formatCandidateLabel(candidate: VariantMatchCandidate) {
  const diffText = formatDiffFields(candidate.diffFields)
  return `${isLooseCandidate(candidate) ? '⚠近似 ' : ''}${candidate.variantName} (${candidate.score}分${diffText ? `，${diffText}` : ''})`
}

function formatMatchReason(row: ImportRow, candidate: VariantMatchCandidate) {
  const looseFields = candidate.diffFields?.filter(field => field.endsWith('~loose')) ?? []
  const shape = row.topDiameterMm
    ? `Excel Ø${row.topDiameterMm} / 候选 Ø${candidate.topDiameterMm || '?'}，差 ${formatDelta(row.topDiameterMm, candidate.topDiameterMm)}mm`
    : `Excel ${row.lengthMm || '?'}×${row.widthMm || '?'} / 候选 ${candidate.lengthMm || '?'}×${candidate.widthMm || '?'}，长宽差 ${formatDelta(row.lengthMm, candidate.lengthMm)}/${formatDelta(row.widthMm, candidate.widthMm)}mm`
  const height = row.heightMm ? `；高度差 ${formatDelta(row.heightMm, candidate.heightMm)}mm` : ''
  const tolerance = looseFields.length ? `；${formatDiffFields(looseFields)}在近似容差内` : '；尺寸在严格容差内'
  const crossSeries = candidate.diffFields?.includes('cross_series') ? '；跨系列候选，需重点核对' : ''
  return `${shape}${height}${tolerance}${crossSeries}`
}

function formatDelta(source?: string, target?: string) {
  const from = Number.parseFloat(source ?? '')
  const to = Number.parseFloat(target ?? '')
  if (!Number.isFinite(from) || !Number.isFinite(to)) return '?'
  const delta = Math.round((to - from) * 1000) / 1000
  return delta > 0 ? `+${delta}` : String(delta)
}
