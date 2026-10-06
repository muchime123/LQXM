import { BOX_SHAPES } from '@/utils/boxSize'

/** Shared by the actual task form and the naming-rule trial form. */
export const PRODUCTION_CONFIG_FIELDS = {
  boxShape: { label: '盒子形状', choices: BOX_SHAPES, control: 'radio' },
  boxLengthMm: { label: '盒子长（mm）', placeholder: '较大的一边，最多 2 位小数', maxlength: 20 },
  boxWidthMm: { label: '盒子宽（mm）', placeholder: '较小的一边，最多 2 位小数', maxlength: 20 },
  boxDiameterMm: { label: '盒子直径（mm）', placeholder: '正数，最多 2 位小数', maxlength: 20 },
  boxShapeNote: { label: '盒子形状说明', placeholder: '如：六角形 / 心形', maxlength: 50 },
  boxSizeNote: { label: '尺寸描述', placeholder: '如：对边 120mm', maxlength: 100 },
  boxHeight: { label: '盒子高度', placeholder: '请输入', maxlength: 50 },
  moldArrangement: { label: '模具排列', choices: ['1出1', '1出2', '2出1', '2出2', '1出4', '4出1', '其他'].map(value => ({ value, label: value })), control: 'select' },
  boxMaterial: { label: '盒子材质', placeholder: '请输入', maxlength: 100 },
  voltage: { label: '电压', choices: ['单相110V', '单相220V', '三相220V', '三相380V'].map(value => ({ value, label: value })), control: 'radio' },
  plug: { label: '插头', choices: ['国标', '欧标', '英标', '美标', '其他'].map(value => ({ value, label: value })), control: 'radio' },
  brand: { label: '品牌', choices: ['利强品牌', '中性', '其他'].map(value => ({ value, label: value })), control: 'radio' },
  specificName: { label: '配件名称', maxlength: 200 },
  compatibleModel: { label: '适配型号', maxlength: 200 },
  moldType: { label: '模具类型', maxlength: 200 },
  drawingVersion: { label: '图纸版本', maxlength: 200 },
  partSpecification: { label: '配件规格', maxlength: 200 },
  extraMold: { label: '是否需要额外模具', choices: [{value: 'yes', label: '是'}, {value: 'no', label: '否'}], control: 'radio' },
  configNote: { label: '填写配置说明', control: 'textarea', maxlength: 500 }
} as const

export type ProductionConfigFieldKey = keyof typeof PRODUCTION_CONFIG_FIELDS
export function configurationFieldsForType(kind: string): ProductionConfigFieldKey[] {
  if (kind === 'PART') return ['specificName', 'compatibleModel', 'partSpecification']
  if (kind === 'MOLD') return ['boxShape', 'boxHeight', 'moldArrangement', 'compatibleModel', 'moldType', 'drawingVersion']
  return ['boxShape', 'boxHeight', 'moldArrangement', 'boxMaterial', 'voltage', 'plug', 'brand']
}
export function configurationTrialFields(kind: string, shape: string): ProductionConfigFieldKey[] {
  const fields = configurationFieldsForType(kind)
  if (kind !== 'PART') {
    const dimensions: ProductionConfigFieldKey[] = shape === 'RECT' ? ['boxLengthMm', 'boxWidthMm'] : shape === 'ROUND' ? ['boxDiameterMm'] : shape === 'OTHER' ? ['boxShapeNote', 'boxSizeNote'] : []
    fields.splice(1, 0, ...dimensions)
  }
  return [...fields, 'extraMold', 'configNote']
}
