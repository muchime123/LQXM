/**
 * 生产跟单导出（网页版）：把「表头 + 二维单元格值 + 语义色」渲染成一份**可离线打开的 HTML 文档**。
 *
 * 与 xlsx 导出**同一份数据、同一套列定义与分组**，差别只在「呈现」：
 * 网页版能上色、斑马纹、粘性表头、打印分页，所以状态类单元格会渲染成彩色标签；Excel 做不到这些
 * （SheetJS 社区版不写样式），于是「好看」这条路交给网页版。
 *
 * 本文件不认识任何业务概念：`value()` 与 `tone()` 都由调用方给（页面显示函数是唯一口径），
 * 这里只负责把二维数组拼成安全的 HTML。
 */

import type { TrackingColumnSource } from './trackingColumnSources'

/** 语义色，与页面 el-tag 的 type 一一对应；空串/缺省＝不上色。 */
export type Tone = 'danger' | 'warning' | 'success' | 'info'

/**
 * 结构化单元格（10-14 LQ）：把「本来要占一列」的附属信息塞进宿主列，省列＝省纸。三件套：
 * - `text`：主值（支持 \n 换行）
 * - `badge`：主值右侧的内联小标（如数量「1张」）；`badgeShape` 选胶囊（默认）或方标
 * - `sub`：主值**下一行**的次要信息（如任务号下的业务员、车间预计完成下的倒计时）
 */
export interface HtmlCellParts {
  text?: string | number | null
  badge?: string | number | null
  badgeShape?: 'pill' | 'square'
  sub?: string | number | null
}

export type HtmlCell = string | number | null | undefined | HtmlCellParts

/** 一块表格 = 一个车间。`tones` 与 `body` 行列对齐，缺省即全不上色。 */
export interface TrackingHtmlSheet {
  name: string
  headers: string[]
  body: HtmlCell[][]
  tones?: (Tone | '' | null)[][]
  /**
   * 每列的**默认列宽**（px），按列序号与 `headers` 对齐；`null`/缺省的列仍按内容估算。
   * 用于把「内容都一样的窄列」统一收窄（日期列、跟单节点列），让人一页纸多放几列。
   * 只是默认值：文档里的列宽数字框与表头拖拽仍可覆盖，双击把手／「恢复默认」回到这里给的值。
   */
  widths?: (number | null | undefined)[]
  /**
   * 每列的**字段来源**（与 `headers` 按列序号对齐；`null`/缺省＝不着色）。
   * 渲染成表头底色 + 表格上方的图例：氚云同步（蓝）/ 跟单登记（绿）/ 系统计算（橙），
   * 让人在导出件里一眼分清「哪列是氚云给的、哪列是跟单员填的、哪列是算出来的」。
   */
  headerSources?: (TrackingColumnSource | '' | null)[]
}

export interface TrackingHtmlOptions {
  sheets: TrackingHtmlSheet[]
  /** 文档标题（也是首屏大标题）。 */
  title?: string
  generatedAt?: Date
  /** 首屏副标题：口径说明（文案由调用方给，页面是唯一来源）。 */
  summary?: string
  /** 表尾备注。 */
  note?: string
}

/** HTML 文本转义：单元格里的 < > & " 一律转义，避免导出文件被数据撑坏结构。 */
function esc(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function pad(value: number): string { return String(value).padStart(2, '0') }

/** 生成时间戳：YYYY-MM-DD HH:mm（本地时间，不带时区，文件里给人看的）。 */
export function trackingStamp(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
    + ` ${pad(now.getHours())}:${pad(now.getMinutes())}`
}

/**
 * 列宽策略（LQ 10-12 定）：**固定列宽 + 超长换行**，不按内容撑宽。
 * 每列按「表头与全部单元格的估算显示宽度」取最大值，钳在 [最小宽, 上限宽] 内，
 * 落成 `<colgroup>` 的固定 px；配合 `table-layout:fixed`，超长文本在列内折行而不是拉宽整表。
 */
const COL_MIN_PX = 64
const COL_MAX_PX = 260
/**
 * 显式默认列宽（`sheet.widths`）的可用区间，与文档里列宽数字框的 min/max 一致 ——
 * 这样「恢复默认 / 双击把手」回填的值一定能被那个输入框如实表示（60px 这类窄列不会被抬回 64）。
 */
const COL_FIXED_MIN_PX = 48
const COL_FIXED_MAX_PX = 500
/** 估算系数：12.5px 字号下，1 个半角字符 ≈ 7px，1 个全角/CJK 字符 ≈ 14px。 */
const PX_PER_UNIT = 7
const CELL_PADDING_PX = 22

/** 字符串显示宽度估算：全角（code point > 0xFF）算 2 个半角单位。 */
function textUnits(text: string): number {
  let units = 0
  for (const ch of text) units += (ch.codePointAt(0) || 0) > 0xff ? 2 : 1
  return units
}

/** 单元格显示宽度估算（单位＝半角字符数）：多行取最长一行；徽标与主值同行，算进同一行。 */
function cellUnits(cell: HtmlCell): number {
  if (cell == null) return 0
  if (typeof cell !== 'object') {
    let units = 0
    for (const part of String(cell).split('\n')) units = Math.max(units, textUnits(part))
    return units
  }
  let units = 0
  for (const part of [cell.text, cell.sub]) {
    if (part == null) continue
    for (const line of String(part).split('\n')) units = Math.max(units, textUnits(line))
  }
  if (cell.badge != null) {
    const textUnitsMax = String(cell.text ?? '').split('\n').reduce((max, line) => Math.max(max, textUnits(line)), 0)
    // 主值 + 徽标（+ 两者间距与徽标内边距，估 3 个单位）＝这一行实际占宽
    units = Math.max(units, textUnitsMax + textUnits(String(cell.badge)) + 3)
  }
  return units
}

function columnWidths(sheet: TrackingHtmlSheet): number[] {
  return sheet.headers.map((header, index) => {
    // ① 调用方显式给了默认列宽就用它（日期列/节点列统一收窄），钳在数字框可用区间内。
    const fixed = sheet.widths?.[index]
    if (typeof fixed === 'number' && Number.isFinite(fixed)) {
      return Math.round(Math.min(COL_FIXED_MAX_PX, Math.max(COL_FIXED_MIN_PX, fixed)))
    }
    // ② 没给就按表头与全部单元格估算，钳在 [最小宽, 上限宽]。
    let units = textUnits(header)
    for (const line of sheet.body) {
      // 多行单元格（如「产品」列 名称+规格 两行、任务号下挂业务员）按最长一行估宽，不被次行拉宽。
      units = Math.max(units, cellUnits(line[index]))
    }
    return Math.round(Math.min(COL_MAX_PX, Math.max(COL_MIN_PX, units * PX_PER_UNIT + CELL_PADDING_PX)))
  })
}

function renderColgroup(widths: number[]): string {
  return `<colgroup>${widths.map(width => `<col style="width:${width}px">`).join('')}</colgroup>`
}

/** 来源 → CSS 类名：`src-h3` / `src-manual` / `src-derived`；空值返回空串（不着色）。 */
function sourceClass(source: TrackingColumnSource | '' | null | undefined): string {
  return source ? `src-${source}` : ''
}

/**
 * 字段来源图例：三枚小色签，出现在每个表格上方（打印保留——纸上也要能认来源）。
 * 只有当 sheet 至少给了一列来源时才渲染。
 */
function renderSourceLegend(sources: (TrackingColumnSource | '' | null)[]): string {
  if (!sources.some(Boolean)) return ''
  return '<div class="src-legend">字段来源：'
    + '<span class="src-chip s-h3">氚云同步 · 只读</span>'
    + '<span class="src-chip s-manual">跟单登记 · 人工填写</span>'
    + '<span class="src-chip s-derived">系统计算 · 只读</span>'
    + '</div>'
}

function renderColumnEditor(sheet: TrackingHtmlSheet, widths: number[]): string {
  const sources = sheet.headerSources || []
  const controls = sheet.headers.map((header, index) => {
    // 来源圆点只在有来源时渲染：无来源的列不留空壳节点。
    const dot = sources[index] ? `<i class="src-dot ${sourceClass(sources[index])}"></i>` : ''
    return `<div class="column-control" data-col="${index}">`
      + `<label>${dot}<input type="checkbox" checked aria-label="显示${esc(header)}列">${esc(header)}</label>`
      + `<label class="width-label">宽度 <input type="number" min="48" max="500" step="5" value="${widths[index]}" aria-label="${esc(header)}列宽度"> px</label>`
      + `</div>`
  }).join('')
  return `<details class="column-editor no-print"><summary>列设置 · 隐藏列 / 精确列宽</summary>`
    + `<div class="column-controls">${controls}</div>`
    + `<p>圆点颜色＝字段来源（与表头底色一致）。取消勾选的列不会打印。列宽可直接<b>拖动表头右侧的竖线</b>调整，也可在这里输入 48–500 px 精确微调；两者联动。</p></details>`
}

/** 单元格文本 → HTML：先转义再落换行（顺序不能反，否则 <br> 会被一起转义掉）。 */
function cellLines(value: string | number | null | undefined): string {
  return esc(value == null ? '' : String(value)).replace(/\n/g, '<br>')
}

function renderCell(cell: HtmlCell, tone?: Tone | '' | null): string {
  if (cell != null && typeof cell === 'object') return renderPartsCell(cell, tone)
  const text = cell == null ? '' : String(cell)
  // 长文本挂 title，鼠标悬停能看全；列宽是固定的，超长靠折行展示而不是拉宽列。
  const title = text.length > 12 ? ` title="${esc(text)}"` : ''
  if (tone) return `<td><span class="tag t-${tone}"${title}>${cellLines(text)}</span></td>`
  return `<td${title}>${cellLines(text)}</td>`
}

/**
 * 结构化单元格：主值（可带语义色标签）+ 右侧徽标 + 下一行副信息。
 * 副信息不挂 title（内容短，且同一格里两个 title 会互相抢悬停）。
 */
function renderPartsCell(parts: HtmlCellParts, tone?: Tone | '' | null): string {
  const text = parts.text == null ? '' : String(parts.text)
  const title = text.length > 12 ? ` title="${esc(text)}"` : ''
  const main = tone
    ? `<span class="tag t-${tone}"${title}>${cellLines(parts.text)}</span>`
    : `<span class="cell-text"${title}>${cellLines(parts.text)}</span>`
  // 徽标（数量「1张」这类）：默认胶囊，可切方标；空值不渲染，避免留下空壳撑高行。
  const badge = parts.badge == null || parts.badge === ''
    ? ''
    : `<span class="cell-badge${parts.badgeShape === 'square' ? ' is-square' : ''}">${esc(String(parts.badge))}</span>`
  const sub = parts.sub == null || parts.sub === ''
    ? ''
    : `<div class="cell-sub">${cellLines(parts.sub)}</div>`
  return `<td><div class="cell-main">${main}${badge}</div>${sub}</td>`
}

function renderSheet(sheet: TrackingHtmlSheet, docInfo: string, summary?: string): string {
  const widths = columnWidths(sheet)
  const sources = sheet.headerSources || []
  // 每个表头右侧挂一条拖拽把手：宽度调整的主入口（列设置里的数字框退化成精确微调）。
  // 有来源的列表头按类着底色（与图例/列设置圆点同色）。
  const head = sheet.headers.map((header, index) => {
    const cls = sourceClass(sources[index])
    return `<th${cls ? ` class="${cls}"` : ''}>${esc(header)}<span class="col-resize" data-col="${index}" title="拖动调整「${esc(header)}」列宽"></span></th>`
  }).join('')
  const rows = sheet.body
    .map((line, index) => `<tr>${line.map((cell, column) => renderCell(cell, sheet.tones?.[index]?.[column])).join('')}</tr>`)
    .join('')
  // 空子表也把表头留着：让人看出「这个车间没有数据」，而不是以为导出坏了。
  const empty = `<tr><td class="empty" colspan="${Math.max(sheet.headers.length, 1)}">当前筛选下本车间没有数据</td></tr>`
  // metrics 是脚本回填的「列宽合计 / 纸张可打印宽度 / 缩放」，让人一眼看出够不够一页宽。
  // docInfo（生成时间/总条数/口径说明）并到同一行右侧：文档级信息不再单独占一块标题区吃纸面。
  return `<section class="sheet">`
    + `<h2>${esc(sheet.name)}<span class="count">${sheet.body.length} 条</span>`
    + `<span class="doc-info">${docInfo}${summary ? ` · ${esc(summary)}` : ''}</span><span class="metrics"></span></h2>`
    + renderSourceLegend(sources)
    + renderColumnEditor(sheet, widths)
    + `<div class="table-wrap"><table>${renderColgroup(widths)}<thead><tr>${head}</tr></thead>`
    + `<tbody>${sheet.body.length ? rows : empty}</tbody></table></div>`
    + `</section>`
}

/**
 * 渲染整份 HTML 文档（**自包含**：样式内联、无外部依赖、无网络请求）。
 * 结构：标题栏（可打印按钮在打印时隐藏）→ 说明 → 每个车间一块 → 备注。
 */
export function buildTrackingHtml(options: TrackingHtmlOptions): string {
  const sheets = options.sheets
  if (!sheets.length) throw new Error('没有可导出的数据')
  const title = options.title || '生产跟单'
  const at = trackingStamp(options.generatedAt)
  const totalRows = sheets.reduce((sum, sheet) => sum + sheet.body.length, 0)
  const parts: string[] = []
  parts.push('<!DOCTYPE html>')
  parts.push('<html lang="zh-CN">')
  parts.push('<head>')
  parts.push('<meta charset="utf-8">')
  parts.push('<meta name="viewport" content="width=device-width,initial-scale=1">')
  parts.push(`<title>${esc(title)} · ${esc(at)}</title>`)
  parts.push(`<style>${STYLE}</style>`)
  parts.push('</head>')
  // 10-21 LQ：导出打开**默认即编辑态**（跟单员拿到手先改字），纸张预览默认不勾选；
  // 勾上「纸张预览」会自动退出编辑进入纸面样式，点「完成编辑」退出后可直接打印。
  parts.push('<body class="is-editing">')
  // 工具条在纸外面：纸面上只留「会真正打印出来的东西」，这样纸面所见＝打印所得。
  parts.push('<div class="screen-bar no-print">')
  // 工具条四组（10-21 梳理）：视图（表格观感）/ 纸张（打印介质）/ 编辑（改字落盘）/ 打印（主操作）。
  // 组间竖线分隔、组名小字点题；提示语独立成第二行，不再跟控件挤在一起。
  parts.push('<div class="print-toolbar" aria-label="打印设置">')
  parts.push('<div class="tool-group"><span class="group-name">视图</span>'
    + '<label>表格字号 <input id="font-size" type="range" min="9" max="18" value="12"><output id="font-size-value">12 px</output></label>'
    + '<label>行间距 <input id="cell-padding" type="range" min="3" max="14" value="7"><output id="cell-padding-value">7 px</output></label>'
    + '</div>')
  parts.push('<div class="tool-group"><span class="group-name">纸张</span>'
    + '<label>纸张 <select id="paper-size"><option value="A4" selected>A4 横向</option><option value="A3">A3 横向</option></select></label>'
    + '<label>打印缩放 <select id="print-scale"><option value="auto">自动适配纸张</option><option value="1">100%</option><option value="0.85">85%</option><option value="0.7">70%</option></select></label>'
    + '<label class="toggle"><input id="paper-preview" type="checkbox">纸张预览</label>'
    + '<button class="btn" id="reset-print" type="button">恢复默认</button>'
    + '</div>')
  parts.push('<div class="tool-group"><span class="group-name">编辑</span>'
    + '<button class="btn is-on" id="toggle-edit" type="button">完成编辑</button>'
    + '<button class="btn" id="save-copy" type="button">保存修改副本</button>'
    + '</div>')
  parts.push('<div class="tool-group">'
    + '<button class="btn primary" type="button" onclick="window.print()">打印 / 另存为 PDF</button>'
    + '</div>')
  parts.push('<span class="print-tip">'
    + '<b>改字</b>：打开文件直接点格子编辑，黄底＝改过（打印不带色）；点「完成编辑」退出后即可打印，勾「纸张预览」自动退出编辑进入纸面样式。'
    + '<b>调版</b>：拖表头右侧竖线调列宽；红竖线＝可打印边界（线右侧打印会被切掉），红横线＝分页位置。'
    + '<b>落盘</b>：改动要点「保存修改副本」才进文件（浏览器 Ctrl+S 存不到页面上的编辑）。'
    + '</span>')
  parts.push('</div>')
  parts.push('<div class="paper" id="paper">')
  // 边界预警层：右侧 10mm 页边距打斜线阴影 + 红色竖虚线标出「可打印边界」；
  // 有内容超界时由脚本把纸挂上 is-overflow，线变实线并点亮警示带。
  parts.push('<div class="paper-guides no-print" aria-hidden="true">'
    + '<div class="paper-margin"></div>'
    + '<div class="paper-edge" id="paper-edge"><span class="edge-label" id="edge-label">可打印边界</span></div>'
    + '</div>')
  parts.push('<div class="paper-content">')
  // 文档级信息（生成时间/总条数/口径说明）不再单独占标题区：并入每块表头右侧的读数位，
  // 打印时省下整整两三行纸面（10-14 LQ：那块「生产跟单 + 生成时间 + 蓝色说明」不要了）。
  const docInfo = `生成时间 ${esc(at)} · 共 ${totalRows} 条 / ${sheets.length} 个车间`
  for (const sheet of sheets) parts.push(renderSheet(sheet, docInfo, options.summary))
  if (options.note) parts.push(`<footer class="note">${esc(options.note)}</footer>`)
  parts.push('</div>')
  parts.push('</div>')
  parts.push('<div id="drag-guide" class="drag-guide no-print" aria-hidden="true"></div>')
  parts.push(`<style id="paper-rule">@page{size:A4 landscape;margin:10mm}</style>`)
  parts.push(`<script>${SCRIPT}</script>`)
  parts.push('</body>')
  parts.push('</html>')
  return parts.join('\n')
}

/** 交给 `downloadBlob` 触发的 Blob（utf-8，无 BOM —— 文档里已声明 charset）。 */
export function buildTrackingHtmlBlob(options: TrackingHtmlOptions): Blob {
  return new Blob([buildTrackingHtml(options)], { type: 'text/html;charset=utf-8' })
}

/** 导出文件名：生产跟单_YYYYMMDD_HHmm.html（本地时间）。 */
export function trackingHtmlFileName(now: Date = new Date()): string {
  return `生产跟单_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `_${pad(now.getHours())}${pad(now.getMinutes())}.html`
}

/** 文件内的交互脚本只操作本文件 DOM，不请求网络；用户调整后直接打印当前视图。 */
const SCRIPT = String.raw`
(function(){
  var font = document.getElementById('font-size');
  var padding = document.getElementById('cell-padding');
  var paper = document.getElementById('paper-size');
  var scale = document.getElementById('print-scale');
  var preview = document.getElementById('paper-preview');
  var paperBox = document.getElementById('paper');
  var paperInner = paperBox.querySelector('.paper-content');
  var screenBar = document.querySelector('.screen-bar');
  var guideLine = document.getElementById('drag-guide');
  var rule = document.getElementById('paper-rule');
  var edgeLabel = document.getElementById('edge-label');
  var sections = Array.prototype.slice.call(document.querySelectorAll('section.sheet'));
  // 纸张尺寸用 mm（物理单位）交给 CSS 换算：96dpi 下 A4 横向 297mm≈1122px，A3 横向 420mm≈1587px。
  var PAPER_MM = { A4: { w: 297, h: 210 }, A3: { w: 420, h: 297 } };
  // 可打印宽度（px）= 纸宽 - 左右各 10mm 页边距：A4 277mm≈1047px，A3 400mm≈1511px。
  var PAPER_PX = { A4: 1047, A3: 1511 };

  function availableWidth(){
    // 纸张预览开着时用实测纸面「内容宽度」（clientWidth 含内衬的 10mm 页边距，要扣掉），
    // 保证「预览宽度＝打印宽度」；否则回落到常量。
    if(preview.checked && paperInner && paperInner.clientWidth){
      var view = document.defaultView;
      var cs = view ? view.getComputedStyle(paperInner) : null;
      var pad = cs ? (parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight) || 0) : 0;
      var width = paperInner.clientWidth - pad;
      if(width > 0) return width;
    }
    return PAPER_PX[paper.value] || PAPER_PX.A4;
  }

  function applyPaper(){
    var size = PAPER_MM[paper.value] || PAPER_MM.A4;
    if(preview.checked){
      paperBox.style.width = size.w + 'mm';
      paperBox.style.minHeight = size.h + 'mm';
      // 每页内容高 = 纸高 - 上下页边距(10mm*2)，供分页参考线定位。
      paperBox.style.setProperty('--page-inner-height', (size.h - 20) + 'mm');
      if(screenBar) screenBar.style.maxWidth = size.w + 'mm';
    } else {
      paperBox.style.width = '';
      paperBox.style.minHeight = '';
      if(screenBar) screenBar.style.maxWidth = '';
    }
    rule.textContent = '@page{size:' + paper.value + ' landscape;margin:10mm}';
  }

  function applySheet(section){
    var table = section.querySelector('table');
    var controls = section.querySelectorAll('.column-control');
    var cols = table.querySelectorAll('col');
    var headings = table.querySelectorAll('thead th');
    var rows = table.querySelectorAll('tbody tr');
    var total = 0;
    var visibleCount = 0;
    Array.prototype.forEach.call(controls, function(control, index){
      var visible = control.querySelector('input[type=checkbox]').checked;
      var widthInput = control.querySelector('input[type=number]');
      var width = Math.min(500, Math.max(48, Number(widthInput.value) || Number(widthInput.defaultValue)));
      cols[index].style.width = width + 'px';
      cols[index].style.display = visible ? '' : 'none';
      headings[index].style.display = visible ? '' : 'none';
      Array.prototype.forEach.call(rows, function(row){
        if(row.cells[index] && !row.cells[index].classList.contains('empty')) row.cells[index].style.display = visible ? '' : 'none';
      });
      if(visible){ total += width; visibleCount++; }
    });
    var empty = table.querySelector('td.empty');
    if(empty) empty.colSpan = Math.max(1, visibleCount);
    table.style.width = (total || 1) + 'px';
    var available = availableWidth();
    // 自动适配只缩不放（放大打印没意义）；手动档按用户选的系数。
    var factor = scale.value === 'auto' ? Math.min(1, available / (total || 1)) : Number(scale.value);
    factor = Math.max(0.3, Math.min(3, factor || 1));
    // --print-zoom 是打印时的缩放（@media print 用）；预览时把同一系数用在屏幕上，做到所见即所印。
    table.style.setProperty('--print-zoom', String(factor));
    table.dataset.zoom = String(factor);
    table.style.zoom = preview.checked ? String(factor) : '';
    // 把手长在表里，会被 zoom 一起缩小到抓不住：按 1/zoom 放大补回，屏幕上始终 ≈ 11px。
    table.style.setProperty('--handle-scale', String(preview.checked ? Math.min(4, 1 / factor) : 1));
    // 按缩放后的实际打印宽度判断是否超界（缩放救不回来的部分才算真超界）
    var overflow = total * factor - available;
    var metrics = section.querySelector('.metrics');
    // 超界状态落到 section 上，供边界预警线汇总（见 updateEdge）
    section.setAttribute('data-overflow', overflow > 1 ? '1' : '');
    updateEdge();
    if(metrics){
      var text;
      if(overflow > 1) text = '超出纸面 ' + Math.round(overflow) + ' px，右侧列会被切掉'
        + (scale.value === 'auto' ? '（已缩到最小 ' + Math.round(factor * 100) + '%，请隐藏列或收窄列宽）' : '');
      else if(factor < 0.999) text = '自动缩放 ' + Math.round(factor * 100) + '%';
      else text = '刚好一页宽';
      metrics.textContent = '列宽合计 ' + total + ' px · 纸面可打印 ' + Math.round(available) + ' px · ' + text;
    }
  }

  // 可打印边界预警：纸右侧 10mm 处的红色竖线就是打印范围的右边界；
  // 任一车间超界时纸面挂 is-overflow（线变实线 + 警示带），标签同步点名。
  function updateEdge(){
    if(!edgeLabel) return;
    var anyOverflow = !!document.querySelector('section.sheet[data-overflow="1"]');
    var size = PAPER_MM[paper.value] || PAPER_MM.A4;
    paperBox.classList.toggle('is-overflow', anyOverflow && preview.checked);
    edgeLabel.textContent = size.w + '×' + size.h + ' 可打印边界' + (anyOverflow ? ' · 右侧打印会被切掉' : '');
  }

  function applyAll(){
    document.documentElement.style.setProperty('--table-font-size', font.value + 'px');
    document.documentElement.style.setProperty('--cell-padding-y', padding.value + 'px');
    document.body.classList.toggle('preview-on', preview.checked);
    document.getElementById('font-size-value').textContent = font.value + ' px';
    document.getElementById('cell-padding-value').textContent = padding.value + ' px';
    applyPaper();
    sections.forEach(applySheet);
  }

  // —— 表头拖动调列宽：把手在 th 右边界，拖到哪列宽到哪；与数字框双向联动 ——
  var drag = null;
  function columnInput(section, index){
    return section.querySelector('.column-control[data-col="' + index + '"] input[type=number]');
  }
  function placeGuide(){
    if(!drag) return;
    var thRect = drag.th.getBoundingClientRect();
    var tableRect = drag.table.getBoundingClientRect();
    guideLine.style.left = Math.round(thRect.right) + 'px';
    guideLine.style.top = Math.round(tableRect.top) + 'px';
    guideLine.style.height = Math.round(tableRect.height) + 'px';
  }
  function onDown(event){
    var handle = event.currentTarget;
    var section = handle.closest('section.sheet');
    if(!section) return;
    var table = section.querySelector('table');
    var th = handle.parentNode;
    var index = Number(handle.getAttribute('data-col'));
    var input = columnInput(section, index);
    if(!input || !th) return;
    event.preventDefault();
    drag = {
      section: section,
      table: table,
      th: th,
      handle: handle,
      input: input,
      startX: event.clientX,
      // 起点宽度取数字框当前值（列宽真值，CSS px）：比量 boundingClientRect 准——那个已被 zoom 缩过、还夹着边框。
      startWidth: Math.min(500, Math.max(48, Number(input.value) || 64)),
      // 预览时表格带 zoom，屏幕 1px ≠ 表内 1px；位移要除以缩放系数才是列宽增量。
      zoom: Number(table.dataset.zoom) || 1
    };
    handle.classList.add('is-active');
    document.body.classList.add('is-resizing');
    if(handle.setPointerCapture && event.pointerId != null){
      try { handle.setPointerCapture(event.pointerId); } catch (e) {}
    }
    guideLine.style.display = 'block';
    placeGuide();
  }
  function onMove(event){
    if(!drag) return;
    var width = Math.round(drag.startWidth + (event.clientX - drag.startX) / (drag.zoom || 1));
    width = Math.min(500, Math.max(48, width));
    if(String(width) === drag.input.value) return;
    drag.input.value = String(width);
    applySheet(drag.section);
    placeGuide();
  }
  function onUp(){
    if(!drag) return;
    if(drag.handle) drag.handle.classList.remove('is-active');
    drag = null;
    document.body.classList.remove('is-resizing');
    guideLine.style.display = 'none';
  }

  sections.forEach(function(section){
    section.querySelector('.column-controls').addEventListener('change', function(event){
      var input = event.target;
      if(input.type === 'checkbox' && !section.querySelector('.column-control input[type=checkbox]:checked')){
        input.checked = true;
        alert('请至少保留一列');
      }
      applySheet(section);
    });
    section.querySelector('.column-controls').addEventListener('input', function(event){
      if(event.target.type === 'number') applySheet(section);
    });
  });
  Array.prototype.forEach.call(document.querySelectorAll('.col-resize'), function(handle){
    handle.addEventListener('pointerdown', onDown);
    // 拖把手时别顺手把表头文字选上。
    handle.addEventListener('mousedown', function(event){ event.preventDefault(); });
    // 双击把手＝该列宽度回到导出时的默认值。
    handle.addEventListener('dblclick', function(){
      var section = handle.closest('section.sheet');
      if(!section) return;
      var input = columnInput(section, Number(handle.getAttribute('data-col')));
      if(input){ input.value = input.defaultValue; applySheet(section); }
    });
  });
  document.addEventListener('pointermove', onMove);
  document.addEventListener('pointerup', onUp);
  document.addEventListener('pointercancel', onUp);
  [font, padding, paper, scale].forEach(function(input){ input.addEventListener('input', applyAll); });
  paper.addEventListener('change', applyAll);
  // 勾「纸张预览」＝要看打印样子了，自动退出编辑（10-21 LQ）；取消勾选不自动回到编辑，想改字再点「开启编辑」。
  preview.addEventListener('change', function(){
    if(preview.checked && document.body.classList.contains('is-editing')){
      document.body.classList.remove('is-editing');
      applyEdit();
    }
    applyAll();
  });
  document.getElementById('reset-print').addEventListener('click', function(){
    // 「恢复默认」回到导出打开时的样子：编辑态、不勾纸张预览（10-21 LQ）
    font.value = '12'; padding.value = '7'; paper.value = 'A4'; scale.value = 'auto'; preview.checked = false;
    sections.forEach(function(section){
      Array.prototype.forEach.call(section.querySelectorAll('.column-control'), function(control){
        control.querySelector('input[type=checkbox]').checked = true;
        var width = control.querySelector('input[type=number]');
        width.value = width.defaultValue;
      });
    });
    document.body.classList.add('is-editing');
    applyEdit();
    applyAll();
  });

  // —— 页面内编辑（10-21 LQ）：导出件不只是看的，跟单员要能直接在上面改字 ——
  // contenteditable 挂在 td 上：结构（徽标/副行/标签配色）不会被改坏，改的只是文本。
  // 默认即编辑态（body 已带 is-editing），这里在装载时把各格的 contentEditable 属性同步上。
  var editBtn = document.getElementById('toggle-edit');
  function applyEdit(){
    var on = document.body.classList.contains('is-editing');
    editBtn.textContent = on ? '完成编辑' : '开启编辑';
    editBtn.classList.toggle('is-on', on);
    Array.prototype.forEach.call(document.querySelectorAll('tbody td'), function(td){
      if(td.classList.contains('empty')){ td.contentEditable = 'false'; return; }
      td.contentEditable = on ? 'true' : 'false';
    });
  }
  editBtn.addEventListener('click', function(){
    document.body.classList.toggle('is-editing');
    applyEdit();
  });
  // 改过的格子挂 edited 标记：屏幕黄底 + 左侧色条，打印时自动退成白底（见样式里的 @media print）
  document.addEventListener('input', function(event){
    var target = event.target;
    var td = target && target.closest ? target.closest('tbody td') : null;
    if(td && !td.classList.contains('empty')) td.classList.add('edited');
  });
  // 编辑态下粘贴一律落纯文本：外部富文本（网页/Word）带进来的样式会把导出件撑花
  document.addEventListener('paste', function(event){
    if(!document.body.classList.contains('is-editing')) return;
    var text = event.clipboardData ? event.clipboardData.getData('text/plain') : null;
    if(text == null) return;
    event.preventDefault();
    document.execCommand('insertText', false, text);
  });
  // 「保存修改副本」：浏览器 Ctrl+S 存的是打开时的原始源码，页面上的编辑不会进去——
  // 必须把当前 DOM 序列化落盘，改动才算真的保住。改过的格子先摘掉黄底标记再存。
  document.getElementById('save-copy').addEventListener('click', function(){
    var clone = document.documentElement.cloneNode(true);
    Array.prototype.forEach.call(clone.querySelectorAll('td.edited'), function(td){ td.classList.remove('edited'); });
    var blob = new Blob(['<!DOCTYPE html>\n' + clone.outerHTML], { type: 'text/html;charset=utf-8' });
    var link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = document.title.replace(/[\\/:*?"<>|]/g, '_').replace(' · ', '_') + '.html';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(function(){ URL.revokeObjectURL(link.href); }, 1000);
  });

  window.addEventListener('beforeprint', applyAll);
  applyEdit();
  applyAll();
})();`

/** 自包含样式：浅色、可打印（A3 横向、表头逐页重复、行不跨页断开）。 */
const STYLE = `
:root{color-scheme:light}
*{box-sizing:border-box}
body{margin:0;padding:26px 30px 54px;background:#f1f3f7;color:#1f2937;
  font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei","PingFang SC","Hiragino Sans GB",sans-serif}
.btn{border:1px solid #cbd5e1;background:#fff;color:#334155;border-radius:8px;padding:7px 14px;font-size:13px;cursor:pointer}
.btn:hover{background:#f8fafc;border-color:#94a3b8}
.btn:focus-visible{outline:2px solid #0f766e;outline-offset:2px}
/* 主操作（打印 / 另存为 PDF）：teal 实底，跟次要按钮拉开层级 */
.btn.primary{background:#0f766e;border-color:#0f766e;color:#fff;font-weight:600}
.btn.primary:hover{background:#0c5f58;border-color:#0c5f58}
.print-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:10px 0;margin:0 0 16px;padding:12px 14px;background:#fff;border:1px solid #dce5e3;border-radius:9px}
/* 工具条四组（视图/纸张/编辑/打印）：竖线分隔 + 组名点题，提示语独立成第二行 */
.tool-group{display:flex;align-items:center;flex-wrap:wrap;gap:10px 14px;padding:0 16px;border-left:1px solid #e8edf3}
.tool-group:first-child{border-left:none;padding-left:0}
.group-name{font-size:11px;font-weight:700;letter-spacing:2px;color:#98a2b3;user-select:none}
.print-toolbar label{display:flex;align-items:center;gap:7px;font-size:12px;font-weight:600;color:#475467}
.print-toolbar input[type=range]{width:90px;accent-color:#0f766e}
.print-toolbar output{min-width:35px;font-weight:400}
.print-toolbar select,.column-control input[type=number]{border:1px solid #cbd5e1;border-radius:5px;background:#fff;color:#334155;padding:4px 5px}
.print-tip{flex-basis:100%;margin-top:2px;padding-top:9px;border-top:1px dashed #e8edf3;color:#667085;font-size:12px;line-height:1.7}
.print-tip b{color:#475467;font-weight:600}
section.sheet{background:#fff;border:1px solid #e3e8ef;border-radius:10px;box-shadow:0 1px 2px rgba(16,24,40,.05);padding:14px 16px 16px;margin-bottom:18px}
section.sheet>h2{margin:0 0 10px;font-size:16px;color:#0f172a;display:flex;align-items:baseline;gap:8px;flex-wrap:wrap}
.count{font-size:12.5px;font-weight:400;color:#64748b;background:#f1f5f9;border-radius:999px;padding:1px 9px}
/* 文档级信息（生成时间/总条数/口径说明）：并到表头行右侧，小字灰排，打印时保留（归档有用） */
.doc-info{margin-left:auto;font-size:12px;font-weight:400;color:#6b7280}
.column-editor{margin:0 0 10px;border:1px solid #e3e8ef;border-radius:7px;padding:7px 10px;font-size:12px}
.column-editor summary{cursor:pointer;color:#0f766e;font-weight:600}
.column-controls{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:6px 12px;margin-top:10px;max-height:230px;overflow:auto}
.column-control{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:5px 7px;background:#f8fafc;border-radius:5px}
.column-control label{display:flex;align-items:center;gap:5px;min-width:0}
.column-control label:first-child{overflow-wrap:anywhere}
.column-control input[type=checkbox]{accent-color:#0f766e}
.column-control input[type=number]{width:58px}
.column-editor p{margin:8px 0 2px;color:#667085}
.column-editor b{color:#0f766e}
/* 每块表头右侧的「列宽合计 / 纸面宽度 / 缩放」读数：一眼看出够不够一页宽 */
.metrics{margin-left:auto;font-size:12px;font-weight:400;color:#475467;background:#f8fafc;border:1px solid #eef2f7;border-radius:999px;padding:1px 10px}
/* 表头拖拽把手：贴在列右边界，hover/拖动时显形；宽度按 1/zoom 放大，缩放后仍抓得住 */
th .col-resize{position:absolute;top:0;right:calc(-5px * var(--handle-scale,1));width:calc(11px * var(--handle-scale,1));
  height:100%;cursor:col-resize;z-index:2;touch-action:none}
th .col-resize::after{content:"";position:absolute;top:16%;bottom:16%;left:calc(5px * var(--handle-scale,1));
  width:calc(1.5px * var(--handle-scale,1));background:transparent}
th .col-resize:hover::after,th .col-resize.is-active::after{background:#0f766e}
th .col-resize.is-active{background:rgba(15,118,110,.08)}
body.is-resizing,body.is-resizing *{cursor:col-resize!important;user-select:none}
.drag-guide{position:fixed;display:none;width:1px;background:#0f766e;box-shadow:0 0 0 1px rgba(15,118,110,.3);z-index:99;pointer-events:none}
.table-wrap{overflow:auto;border:1px solid #e3e8ef;border-radius:8px;max-height:78vh}
/* —— 纸张预览：按物理尺寸画一张纸，纸面所见＝打印所得 —— */
.screen-bar{margin:0 auto 14px}
.paper{position:relative;margin:0 auto;width:297mm;min-height:210mm;background:#fff;border:1px solid #dde3ea;
  border-radius:6px;box-shadow:0 3px 14px rgba(16,24,40,.09)}
.paper-content{padding:10mm}
/* 分页参考线：每「页内容高」（纸高 - 上下页边距）画一条，起点落在上页边距处。
   z-index 要压过粘性表头（z-index:1），滚动时红线始终盖在表上。 */
.paper-guides{position:absolute;inset:0;pointer-events:none;border-radius:6px;z-index:6;background-repeat:repeat-y;
  background-position:0 10mm;
  background-image:repeating-linear-gradient(to bottom,transparent 0,transparent calc(var(--page-inner-height,190mm) - 1.5px),rgba(244,63,94,.45) calc(var(--page-inner-height,190mm) - 1.5px),rgba(244,63,94,.45) var(--page-inner-height,190mm))}
/* 可打印边界预警：右侧 10mm 页边距打斜线阴影（这段纸肯定不印内容）；
   红色竖虚线 = 打印范围的右边界。有内容超界时线变实线并向右拖一条渐隐警示带。 */
.paper-margin{position:absolute;top:0;right:0;bottom:0;width:10mm;
  background:repeating-linear-gradient(45deg,rgba(244,63,94,.07) 0 6px,transparent 6px 12px)}
.paper-edge{position:absolute;top:0;bottom:0;right:10mm;border-right:2px dashed rgba(244,63,94,.55)}
.paper.is-overflow .paper-edge{border-right:2px solid #f43f5e}
.paper-edge::after{content:"";display:none;position:absolute;top:0;bottom:0;left:2px;width:64px;
  background:linear-gradient(to right,rgba(244,63,94,.16),rgba(244,63,94,0))}
.paper.is-overflow .paper-edge::after{display:block}
.edge-label{position:absolute;top:5px;right:calc(10mm + 8px);font-size:11px;line-height:1.5;color:#be123c;
  background:rgba(255,241,242,.94);border:1px solid rgba(244,63,94,.4);border-radius:999px;padding:0 9px;white-space:nowrap}
/* 预览模式下表格不再内部滚动/加框：纸面就是预览，嵌套滚动条只会误判页宽 */
body.preview-on .table-wrap{max-height:none;overflow:visible;border:none;border-radius:0}
body.preview-on section.sheet{box-shadow:none;border-color:#e8edf3}
/* 关掉纸张预览＝回到普通长页面（1:1 尺寸，不套纸框） */
body:not(.preview-on) .paper{width:auto;min-height:0;background:transparent;border:none;border-radius:0;box-shadow:none}
body:not(.preview-on) .paper-content{padding:0}
body:not(.preview-on) .paper-guides{display:none}
/* 固定列宽（colgroup 定宽）+ 超长折行：列宽不随内容撑开，显示效果稳定。 */
table{border-collapse:separate;border-spacing:0;width:100%;font-size:var(--table-font-size,12px);table-layout:fixed;
  overflow-wrap:break-word;word-break:break-word}
thead th{position:sticky;top:0;z-index:1;background:#eef2f7;color:#334155;font-weight:600;text-align:left;
  padding:var(--cell-padding-y,7px) 10px;border-bottom:1px solid #dbe1ea;border-right:1px solid #e6eaf1;overflow-wrap:break-word}
/* 字段来源三分色（LQ 10-15）：蓝＝氚云同步只读、绿＝跟单登记人工、橙＝系统计算只读。
   底色选得很浅，黑白打印也能靠列设置里的圆点深浅分辨。 */
thead th.src-h3{background:#e3eefb}
thead th.src-manual{background:#e2f4e8}
thead th.src-derived{background:#fdefd8}
/* 图例：紧跟表头行下方，打印保留（纸上也要能认来源） */
.src-legend{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:-2px 0 10px;font-size:11.5px;color:#475467}
.src-legend .src-chip{display:inline-flex;align-items:center;border-radius:999px;padding:0 9px;line-height:1.7;border:1px solid}
.src-chip.s-h3{color:#1d5fa8;background:#eaf2fc;border-color:#b9d4f2}
.src-chip.s-manual{color:#1c7a44;background:#e7f6ec;border-color:#b4e0c4}
.src-chip.s-derived{color:#9a6414;background:#fdf3e2;border-color:#ecd3a4}
/* 列设置里的来源圆点：与表头底色同色系，深一档便于在白底上辨认 */
.src-dot{display:inline-block;width:8px;height:8px;border-radius:2px;flex:none;background:#cbd5e1}
.src-dot.s-h3{background:#3b82c4}
.src-dot.s-manual{background:#2f9e63}
.src-dot.s-derived{background:#d08a2d}
tbody td{padding:var(--cell-padding-y,7px) 10px;border-bottom:1px solid #eef1f5;border-right:1px solid #f2f5f9;vertical-align:top}
tbody tr:nth-child(even){background:#fafbfd}
tbody tr:hover{background:#f2f7ff}
tbody tr:last-child td{border-bottom:none}
th:last-child,td:last-child{border-right:none}
.tag{display:inline-block;padding:1px 8px;border-radius:999px;font-size:inherit;line-height:1.5;border:1px solid transparent;white-space:normal}
/* 结构化单元格：主值 + 徽标同行，副信息换行（业务员挂任务号下、倒计时挂预计完成下） */
.cell-main{display:flex;align-items:baseline;gap:5px;flex-wrap:wrap;min-width:0}
.cell-text{min-width:0;overflow-wrap:break-word}
.cell-badge{display:inline-block;flex:none;border-radius:999px;background:#eef2f7;color:#334155;border:1px solid #dbe1ea;
  font-size:11px;line-height:1.45;padding:0 6px;white-space:nowrap}
.cell-badge.is-square{border-radius:3px}
.cell-sub{color:#64748b;font-size:11.5px;line-height:1.45;margin-top:1px}
.t-danger{background:#fef3f2;color:#b42318;border-color:#fecdca}
.t-warning{background:#fffaeb;color:#b54708;border-color:#fedf89}
.t-success{background:#ecfdf3;color:#027a48;border-color:#abefc6}
.t-info{background:#eff8ff;color:#175cd3;border-color:#b2ddff}
td.empty{text-align:center;color:#98a2b3;padding:18px 10px}
/* —— 页面内编辑：编辑态给虚线框提示「可改」；改过的格子黄底+左色条（只上屏，打印退白） —— */
body.is-editing tbody td{outline:1px dashed #94a3b8;outline-offset:-1px;cursor:text}
body.is-editing tbody td:focus{outline:2px solid #0f766e;outline-offset:-1px;background:#f2f7ff}
td.edited{background:#fffaeb!important;box-shadow:inset 2px 0 0 #d08a2d}
.btn.is-on{border-color:#0f766e;background:#ecfdf3;color:#027a48;font-weight:600}
footer.note{margin-top:4px;color:#6b7280;font-size:12px}
@page{size:A4 landscape;margin:10mm}
@media print{
  body{background:#fff;padding:0}
  .no-print{display:none!important}
  /* 打印时纸框退化成普通留白：真正的页边距由 @page margin 给，纸框只服务屏幕预览 */
  .paper{width:auto;min-height:0;border:none;border-radius:0;box-shadow:none;margin:0}
  .paper-content{padding:0}
  .paper-guides,.drag-guide,th .col-resize{display:none}
  /* 列宽读数是屏幕调版用的辅助信息，打印到纸上纯属噪音 */
  .metrics{display:none}
  section.sheet{box-shadow:none;border-radius:0;border-left:none;border-right:none;margin-bottom:10px}
  .table-wrap{overflow:visible;max-height:none;border-radius:0}
  table{zoom:var(--print-zoom,1)}
  thead{display:table-header-group}
  tr{page-break-inside:avoid}
  /* 编辑痕迹不上纸：改过的格子打印时退回白底（内容已在，颜色标记只服务屏幕核对） */
  td.edited{background:transparent!important;box-shadow:none}
}
`.trim()
