const fs = require('fs');
const p = 'c:/Users/20676/source/LQXM/frontend/src/views/governance/MatchingCenterView.vue';
let c = fs.readFileSync(p, 'utf8');

// 1) Replace the runAutoMerge function
const OLD_FUNC = [
  \"const autoMerging = ref(false), autoMergeResult = ref<Record<string, any> | null>(null)\",
  \"async function runAutoMerge() {\",
  \"  const ok = await askConfirm({\",
  \"    title: '低置信度候选自动合并',\",
  \"    message: '对 <60% 的低置信度候选，按生产任务分组取最高分自动确认，其余驳回。已有关联的跳过。是否继续？',\",
  \"    confirmText: '执行自动合并',\",
  \"    danger: true\",
  \"  })\",
  \"  if (!ok) return\",
  \"  autoMerging.value = true\",
  \"  autoMergeResult.value = null\",
  \"  try {\",
  \"    const r: any = await autoMergeLowConfidence(60, '于洋洋')\",
  \"    autoMergeResult.value = r\",
  \"    const msg = '自动合并完成：确认 ' + r.autoConfirmed + '，驳回 ' + r.autoRejected + '，跳过 ' + r.skipped + '（共 ' + r.totalProcessed + ' 条，' + r.groups + ' 组）'\",
  \"    notify.success(msg)\",
  \"    await loadCandidates()\",
  \"  } catch (e: any) { notify.error(e?.message || '自动合并失败') }\",
  \"  finally { autoMerging.value = false }\",
  \"}\"
].join('\n');

const NEW_FUNC = [
  \"const autoMergeVisible = ref(false)\",
  \"const autoMerging = ref(false), autoMergeResult = ref<Record<string, any> | null>(null)\",
  \"const autoMergeThreshold = ref(60), autoMergePreview = ref<Record<string, any> | null>(null), autoMergePreviewing = ref(false)\",
  \"\",
  \"function openAutoMergeDialog() {\",
  \"  autoMergeVisible.value = true\",
  \"  autoMergePreview.value = null\",
  \"  autoMergeResult.value = null\",
  \"  autoMergeThreshold.value = 60\",
  \"}\",
  \"\",
  \"async function previewAutoMerge() {\",
  \"  autoMergePreviewing.value = true\",
  \"  autoMergePreview.value = null\",
  \"  try {\",
  \"    const r: any = await autoMergeLowConfidence(autoMergeThreshold.value, '于洋洋', true)\",
  \"    autoMergePreview.value = r\",
  \"  } catch (e: any) { notify.error(e?.message || '预览失败') }\",
  \"  finally { autoMergePreviewing.value = false }\",
  \"}\",
  \"\",
  \"async function executeAutoMerge() {\",
  \"  autoMerging.value = true\",
  \"  autoMergeResult.value = null\",
  \"  try {\",
  \"    const r: any = await autoMergeLowConfidence(autoMergeThreshold.value, '于洋洋', false)\",
  \"    autoMergeResult.value = r\",
  \"    autoMergeVisible.value = false\",
  \"    const msg = '自动合并完成：确认 ' + r.autoConfirmed + '，驳回 ' + r.autoRejected + '，跳过 ' + r.skipped + '（共 ' + r.totalProcessed + ' 条，' + r.groups + ' 组）'\",
  \"    notify.success(msg)\",
  \"    await loadCandidates()\",
  \"  } catch (e: any) { notify.error(e?.message || '自动合并失败') }\",
  \"  finally { autoMerging.value = false }\",
  \"}\"
].join('\n');

if (!c.includes(OLD_FUNC)) { console.error('func not found'); process.exit(1); }
c = c.replace(OLD_FUNC, NEW_FUNC);
console.log('1 func replaced');

// 2) Replace the button
const OLD_BTN = '<el-button size=\"small\" type=\"warning\" plain :loading=\"autoMerging\" @click=\"runAutoMerge\">低置信度自动合并</el-button>';
const NEW_BTN = '<el-button size=\"small\" type=\"warning\" plain @click=\"openAutoMergeDialog\">低置信度自动合并</el-button>';
if (!c.includes(OLD_BTN)) { console.error('btn not found'); process.exit(2); }
c = c.replace(OLD_BTN, NEW_BTN);
console.log('2 btn replaced');

// 3) Add auto-merge button to receipt-candidates batch bar
const OLD_RCPT = '          <span class=\"batch-sep\"></span>\n          <span class=\"batch-count\">已选 <b>{{ selectedReceiptCandidates.length }}</b> 条</span>';
const NEW_RCPT = '          <span class=\"batch-sep\"></span>\n          <el-button size=\"small\" type=\"warning\" plain @click=\"openAutoMergeDialog\">低置信度自动合并</el-button>\n          <span class=\"batch-count\">已选 <b>{{ selectedReceiptCandidates.length }}</b> 条</span>';
if (!c.includes(OLD_RCPT)) { console.error('rcpt bar not found'); process.exit(3); }
c = c.replace(OLD_RCPT, NEW_RCPT);
console.log('3 rcpt bar updated');

// 4) Add dialog before script tag - need to add a second template block
const OLD_END = '</template>\n\n<script setup lang=\"ts\">';
const DIALOG_HTML = [
  '</template>',
  '',
  '<template>',
  '  <el-dialog v-model=\"autoMergeVisible\" title=\"低置信度候选自动合并\" width=\"520px\" :close-on-click-modal=\"false\">',
  '    <div class=\"auto-merge-dialog-body\">',
  '      <p class=\"merge-desc\">对低于阈值置信度的 PENDING 候选，按生产任务分组取最高分自动确认，其余驳回。已有关联的跳过。</p>',
  '      <div class=\"threshold-row\">',
  '        <span class=\"threshold-label\">置信度阈值：</span>',
  '        <el-slider v-model=\"autoMergeThreshold\" :min=\"10\" :max=\"80\" :step=\"5\" :marks=\"{ 30: \'30%\', 50: \'50%\', 60: \'60%\', 70: \'70%\' }\" style=\"flex:1; margin: 0 12px\" @change=\"autoMergePreview = null\" />',
  '        <span class=\"threshold-value\">< {{ autoMergeThreshold }}%</span>',
  '      </div>',
  '      <div class=\"preview-actions\">',
  '        <el-button :loading=\"autoMergePreviewing\" @click=\"previewAutoMerge\">预览合并结果</el-button>',
  '      </div>',
  '      <div v-if=\"autoMergePreview\" class=\"preview-result\">',
  '        <div class=\"preview-stats\">',
  '          <span class=\"stat stat-confirm\">将确认 {{ autoMergePreview.autoConfirmed }}</span>',
  '          <span class=\"stat stat-reject\">将驳回 {{ autoMergePreview.autoRejected }}</span>',
  '          <span class=\"stat stat-skip\">将跳过 {{ autoMergePreview.skipped }}</span>',
  '          <span class=\"stat stat-total\">共 {{ autoMergePreview.totalProcessed }} 条 / {{ autoMergePreview.groups }} 组</span>',
  '        </div>',
  '        <div v-if=\"autoMergePreview.previewConfirmed?.length\" class=\"preview-section\">',
  '          <b>将确认（前 {{ autoMergePreview.previewConfirmed.length }} 条）</b>',
  '          <div v-for=\"item in autoMergePreview.previewConfirmed.slice(0, 5)\" :key=\"item.id\" class=\"preview-row\">',
  '            候选 #{{ item.id }} · 置信度 {{ item.confidence }}% · 生产 #{{ item.production_order_id }} · 出库 #{{ item.sales_outbound_id }}',
  '          </div>',
  '        </div>',
  '      </div>',
  '    </div>',
  '    <template #footer>',
  '      <el-button @click=\"autoMergeVisible = false\">取消</el-button>',
  '      <el-button type=\"danger\" :loading=\"autoMerging\" :disabled=\"!autoMergePreview\" @click=\"executeAutoMerge\">执行自动合并</el-button>',
  '    </template>',
  '  </el-dialog>',
  '</template>',
  '',
  '<script setup lang=\"ts\">'
].join('\n');

if (!c.includes(OLD_END)) { console.error('end not found'); process.exit(4); }
c = c.replace(OLD_END, DIALOG_HTML);
console.log('4 dialog added');

// 5) Add styles
const OLD_STYLE = '.auto-merge-result { font-size: 12px; color: #64748b; padding: 2px 8px; border-radius: 6px; background: #fef3c7; }';
const NEW_STYLE = '.auto-merge-result { font-size: 12px; color: #64748b; padding: 2px 8px; border-radius: 6px; background: #fef3c7; }\n.auto-merge-dialog-body .merge-desc { color: #64748b; font-size: 13px; line-height: 1.6; margin: 0 0 12px; }\n.auto-merge-dialog-body .threshold-row { display: flex; align-items: center; margin-bottom: 16px; }\n.auto-merge-dialog-body .threshold-label { font-size: 13px; white-space: nowrap; }\n.auto-merge-dialog-body .threshold-value { font-size: 14px; font-weight: 600; color: #e6a23c; min-width: 50px; }\n.auto-merge-dialog-body .preview-actions { margin-bottom: 12px; }\n.auto-merge-dialog-body .preview-result { background: #f8f9fa; border-radius: 8px; padding: 12px; }\n.auto-merge-dialog-body .preview-stats { display: flex; gap: 12px; flex-wrap: wrap; font-size: 13px; margin-bottom: 8px; }\n.auto-merge-dialog-body .stat { padding: 2px 8px; border-radius: 6px; }\n.auto-merge-dialog-body .stat-confirm { background: #d1fae5; color: #065f46; }\n.auto-merge-dialog-body .stat-reject { background: #fee2e2; color: #991b1b; }\n.auto-merge-dialog-body .stat-skip { background: #f3f4f6; color: #4b5563; }\n.auto-merge-dialog-body .stat-total { background: #e0e7ff; color: #3730a3; }\n.auto-merge-dialog-body .preview-section { margin-top: 8px; font-size: 12px; }\n.auto-merge-dialog-body .preview-row { padding: 4px 0; border-bottom: 1px dashed #e5e7eb; color: #6b7280; }';
if (!c.includes(OLD_STYLE)) { console.error('style not found'); process.exit(5); }
c = c.replace(OLD_STYLE, NEW_STYLE);
console.log('5 styles added');

fs.writeFileSync(p, c, 'utf8');
console.log('DONE');
