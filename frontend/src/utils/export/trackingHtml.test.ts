import { describe, expect, it } from 'vitest'
import { buildTrackingHtml, buildTrackingHtmlBlob, trackingHtmlFileName, trackingStamp, type TrackingHtmlSheet } from './trackingHtml'

const SAMPLE: TrackingHtmlSheet[] = [
  {
    name: '封口机车间',
    headers: ['任务号', '生产交付结果', '备料'],
    body: [
      ['SCGD20260006047', '逾期完工 3 天', '2026-10-08'],
      ['SCGD20260006048', '按期完工', '']
    ],
    tones: [['', 'danger', ''], ['', 'success', '']]
  },
  { name: '全自动气调机车间', headers: ['任务号', '画图'], body: [['SCGD20260005101', '2026-09-12']] }
]

describe('生产跟单导出：网页(HTML)组装', () => {
  it('工具条容器必须闭合：正文(.paper)不得嵌进 .screen-bar（否则打印随 .no-print 一起隐藏，整页空白）', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    // 2026-10-23 实测回归：screen-bar 少一个 </div> 时，<div class="paper"> 会被浏览器
    // 解析进 .screen-bar.no-print 里——屏幕一切正常，打印预览全白。
    expect(html).toContain('</div>\n<div class="paper" id="paper">')
    // div 开闭数量守恒（防止同类嵌套错再犯）
    const opens = (html.match(/<div[\s>]/g) || []).length
    const closes = (html.match(/<\/div>/g) || []).length
    expect(closes).toBe(opens)
  })

  it('每个车间一块，块内表头与每行单元格按顺序落位', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    const sections = html.split('<section class="sheet">').slice(1)
    expect(sections).toHaveLength(2)
    expect(sections[0]).toContain('<h2>封口机车间<span class="count">2 条</span><span class="doc-info">')
    // 表头文字后面挂拖拽把手，data-col 与列序号一一对应
    expect(sections[0]).toContain('<th>任务号<span class="col-resize" data-col="0"')
    expect(sections[0]).toContain('<th>生产交付结果<span class="col-resize" data-col="1"')
    expect(sections[0]).toContain('<th>备料<span class="col-resize" data-col="2"')
    expect(sections[0]).toContain('SCGD20260006047')
    expect(sections[0]).toContain('<td>2026-10-08</td>')
    expect(sections[1]).toContain('<h2>全自动气调机车间<span class="count">1 条</span>')
  })

  it('单元格里的 HTML 特殊字符被转义（数据不能撑坏结构）', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A & B', headers: ['备注'], body: [['<script>alert(1)</script>'], ['"引号" & 尖括号 < >']] }]
    })
    expect(html).not.toContain('<script>alert(1)</script>')
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(html).toContain('&quot;引号&quot; &amp; 尖括号 &lt; &gt;')
    expect(html).toContain('<h2>A &amp; B<span')
  })

  it('语义色渲染成对应配色标签，无色的仍是普通单元格', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    expect(html).toContain('<span class="tag t-danger">逾期完工 3 天</span>')
    expect(html).toContain('<span class="tag t-success">按期完工</span>')
    // 无色的任务号仍是普通单元格（不是标签）
    expect(html).toContain('<td title="SCGD20260006047">SCGD20260006047</td>')
  })

  it('空子表保留表头并给出「没有数据」，colspan 与列数一致', () => {
    const html = buildTrackingHtml({ sheets: [{ name: '称重机车间', headers: ['任务号', '备注'], body: [] }] })
    expect(html).toContain('<th>任务号<span class="col-resize"')
    expect(html).toContain('<th>备注<span class="col-resize"')
    expect(html).toContain('colspan="2"')
    expect(html).toContain('当前筛选下本车间没有数据')
    expect(html).toContain('<span class="count">0 条</span>')
  })

  it('字段来源：表头按类着底色，表格上方出图例，列设置里挂圆点', () => {
    const html = buildTrackingHtml({
      sheets: [{
        name: '封口机车间',
        headers: ['任务号', '跟单备注', '生产交付结果'],
        body: [['SCGD1', '备注', '按期完工']],
        headerSources: ['h3', 'manual', 'derived']
      }]
    })
    expect(html).toContain('<th class="src-h3">任务号')
    expect(html).toContain('<th class="src-manual">跟单备注')
    expect(html).toContain('<th class="src-derived">生产交付结果')
    expect(html).toContain('字段来源：')
    expect(html).toContain('<span class="src-chip s-h3">氚云同步 · 只读</span>')
    expect(html).toContain('<span class="src-chip s-manual">跟单登记 · 人工填写</span>')
    expect(html).toContain('<span class="src-chip s-derived">系统计算 · 只读</span>')
    expect(html).toContain('<i class="src-dot src-h3"></i>')
    expect(html).toContain('<i class="src-dot src-manual"></i>')
    expect(html).toContain('<i class="src-dot src-derived"></i>')
  })

  it('没给字段来源的表保持原样：无着色表头、无图例', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    // 样式表里本来就有 .src-legend 规则，这里只认 HTML 标记，不认类名字符串。
    expect(html).not.toContain('<div class="src-legend">')
    expect(html).not.toContain('th class="src-')
    expect(html).not.toContain('<i class="src-dot')
  })

  it('headerSources 缺项/空串的列不着色，其余照常（与列数不对齐时不炸）', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['X', 'Y', 'Z'], body: [['1', '2', '3']], headerSources: ['h3', ''] }]
    })
    expect(html).toContain('<th class="src-h3">X')
    expect(html).toContain('<th>Y<span class="col-resize"')
    expect(html).toContain('<th>Z<span class="col-resize"')
    expect(html).toContain('字段来源：')
  })

  it('空值写空单元格，不写 "null" / "undefined"', () => {
    const html = buildTrackingHtml({ sheets: [{ name: 'A', headers: ['X', 'Y'], body: [[null, undefined]] }] })
    // 只看表格区：脚本里的 `var drag = null` 是合法代码，别把它算成「渲染出了 null」
    const section = html.split('<section class="sheet">')[1].split('</section>')[0]
    expect(section).not.toContain('null')
    expect(section).not.toContain('undefined')
    expect(html).toContain('<tr><td></td><td></td></tr>')
  })

  it('长文本挂 title 提示，短文本不挂（避免属性噪音）', () => {
    const html = buildTrackingHtml({ sheets: [{ name: 'A', headers: ['备注'], body: [['这是一段超过十二个字的跟单备注'], ['短']] }] })
    expect(html).toContain('title="这是一段超过十二个字的跟单备注"')
    expect(html).toContain('<td title="这是一段超过十二个字的跟单备注">')
    expect(html).toContain('<td>短</td>')
  })

  it('单元格换行落成 <br>（产品列「名称+规格」两行的排版意图），转义仍在换行前做', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['产品'], body: [['按钮自动卷膜\n232*201mm / 一而出'], ['<b>x</b>\n<y>']] }]
    })
    expect(html).toContain('<td title="按钮自动卷膜\n232*201mm / 一而出">按钮自动卷膜<br>232*201mm / 一而出</td>')
    expect(html).toContain('<td>&lt;b&gt;x&lt;/b&gt;<br>&lt;y&gt;</td>')
  })

  it('多行单元格的列宽按最长一行估算，不被次行拉宽', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['产品'], body: [['AAAAAAAAAAAA\nBBBB']] }]
    })
    // 最长行 12 个半角单位 → 12*7+30 = 114px；若按整格（含 \n）估会到 135px。
    expect(html).toContain('<col style="width:114px">')
  })

  it('结构化单元格：主值 + 行内徽标 + 下一行副信息（省列用），空徽标/空副行不留空壳', () => {
    const html = buildTrackingHtml({
      sheets: [{
        name: 'A',
        headers: ['任务号 / 业务员', '产品 / 数量'],
        body: [
          [{ text: '6098', sub: '李文静' }, { text: '按钮自动卷膜', sub: '232*201mm / 一出一', badge: '1张' }],
          [{ text: '6099', sub: '' }, { text: '气动式卷膜', badge: '' }],
          // 方标形状可切；徽标与副行里的特殊字符照样转义
          [{ text: '<x>', sub: 'a & b' }, { text: 'y', badge: '<2张>', badgeShape: 'square' }]
        ]
      }]
    })
    const section = html.split('<section class="sheet">')[1]
    expect(section).toContain('<td><div class="cell-main"><span class="cell-text">6098</span></div><div class="cell-sub">李文静</div></td>')
    expect(section).toContain('<span class="cell-badge">1张</span>')
    expect(section).toContain('<div class="cell-sub">232*201mm / 一出一</div>')
    // 空值不渲染：6099 那行只有主值，没有空的 badge / sub 壳
    expect(section).toContain('<td><div class="cell-main"><span class="cell-text">6099</span></div></td>')
    expect(section).toContain('<span class="cell-badge is-square">&lt;2张&gt;</span>')
    expect(section).toContain('<div class="cell-sub">a &amp; b</div>')
    expect(section).not.toContain('cell-badge"></span>')
  })

  it('结构化单元格的列宽：徽标与主值同行累加，副行单独估宽不被拉宽', () => {
    // 主值 4 个半角 + 徽标「1张」(3) + 间距 3 = 10 单位 → 10*7+30 = 100px；副行更短不参与
    const badge = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['产品'], body: [[{ text: 'abcd', badge: '1张', sub: 'x' }]] }]
    })
    expect(badge).toContain('<col style="width:100px">')
    // 副行更长时按副行估：6 个半角 → 6*7+30 = 72px
    const sub = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['产品'], body: [[{ text: 'a', sub: 'abcdef' }]] }]
    })
    expect(sub).toContain('<col style="width:72px">')
  })

  it('自包含：样式内联、没有任何外部资源引用（离线可打开）', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE, summary: '按当前筛选导出', note: '备注一行' })
    expect(html.startsWith('<!DOCTYPE html>')).toBe(true)
    expect(html).toContain('<meta charset="utf-8">')
    expect(html).toContain('<style>')
    expect(html).not.toMatch(/(?:src|href)\s*=\s*"https?:/i)
    expect(html).not.toContain('<link')
    expect(html).toContain('按当前筛选导出')
    expect(html).toContain('备注一行')
  })

  it('文档级信息并入每块表头右侧的读数位，不再单独占标题区（省打印纸面）', () => {
    const html = buildTrackingHtml({
      sheets: SAMPLE,
      summary: '数据范围为全部生产任务',
      generatedAt: new Date(2026, 9, 12, 9, 15)
    })
    // 旧的标题区整块撤掉：大标题、meta 行、蓝色说明条都不进纸面
    expect(html).not.toContain('<h1>')
    expect(html).not.toContain('doc-head')
    expect(html).not.toContain('class="summary"')
    expect(html).toContain('<title>生产跟单 · 2026-10-12 09:15</title>')
    // 信息并入每块表头行右侧（doc-info），口径说明跟在后面
    const infos = [...html.matchAll(/<span class="doc-info">([^<]*)<\/span>/g)].map(match => match[1])
    expect(infos).toHaveLength(2)
    expect(infos[0]).toBe('生成时间 2026-10-12 09:15 · 共 3 条 / 2 个车间 · 数据范围为全部生产任务')
    // 打印按钮挪到纸外工具条，打印时随 .no-print 一起隐藏
    expect(html).toContain('<div class="screen-bar no-print">')
    expect(html).not.toContain('header class="doc-head"')
  })

  it('一个子表都没有时直接报错', () => {
    expect(() => buildTrackingHtml({ sheets: [] })).toThrow('没有可导出的数据')
  })

  it('Blob 的类型是 html + utf-8，内容可回读', async () => {
    const blob = buildTrackingHtmlBlob({ sheets: SAMPLE })
    expect(blob.type).toBe('text/html;charset=utf-8')
    expect(await blob.text()).toContain('封口机车间')
  })

  it('文件名带本地时间戳且为 .html，时间戳格式 YYYY-MM-DD HH:mm', () => {
    const at = new Date(2026, 9, 12, 9, 5)
    expect(trackingHtmlFileName(at)).toBe('生产跟单_20261012_0905.html')
    expect(trackingStamp(at)).toBe('2026-10-12 09:05')
  })

  it('列宽固定：每个子表输出 colgroup（列数与表头一致），表格用 fixed 布局且不再按内容撑宽', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    const sections = html.split('<section class="sheet">').slice(1)
    for (const section of sections) {
      const cols = section.match(/<col style="width:\d+px">/g) || []
      expect(cols).toHaveLength(section.match(/<th>/g)?.length || 0)
    }
    expect(html).toContain('table-layout:fixed')
    // 表格规则里不再有 nowrap（标签 .tag 的 nowrap 是允许的：标签文本都很短）
    expect(html).not.toMatch(/table\{[^}]*white-space/)
  })

  it('列宽钳位：短列给最小宽，超长文本列钳到上限（靠折行展示，不拉宽整表）', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['数量', '跟单备注'], body: [['1', '长'.repeat(200)]] }]
    })
    expect(html).toContain('<col style="width:64px">')
    expect(html).toContain('<col style="width:260px">')
  })

  it('显式默认列宽：指定列用指定值（日期 60 / 带副行 90），未指定的列仍按内容估算', () => {
    const html = buildTrackingHtml({
      sheets: [{
        name: 'A',
        headers: ['生产任务单创建时间', '跟单备注', '备料'],
        body: [['10-08', '一段较长的跟单备注内容', '10-09']],
        // 第 0、2 列指定 60/90；第 1 列 null ＝不设默认，按内容估算
        widths: [60, null, 90]
      }]
    })
    const cols = [...html.matchAll(/<col style="width:(\d+)px">/g)].map(match => match[1])
    expect(cols[0]).toBe('60')
    expect(cols[2]).toBe('90')
    // 备注列没指默认宽：「一段较长的跟单备注内容」11 个全角 = 22 单位 → 22*7+30 = 184px
    expect(cols[1]).toBe('184')
    // 列宽数字框的 defaultValue 同步为指定值 ⇒ 「恢复默认 / 双击把手」回到这里给的宽度
    expect(html).toContain('value="60" aria-label="生产任务单创建时间列宽度"')
    expect(html).toContain('value="90" aria-label="备料列宽度"')
  })

  it('显式默认列宽越界时钳到 48–500 px（与列宽数字框的可用区间一致，保证回填不失真）', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['A', 'B'], body: [['1', '2']], widths: [10, 9999] }]
    })
    const cols = [...html.matchAll(/<col style="width:(\d+)px">/g)].map(match => match[1])
    expect(cols).toEqual(['48', '500'])
  })

  it('widths 比列数短（或整段缺省）时不报错，缺的列按内容估算', () => {
    const short = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['跟单节点', '备注'], body: [['10-08', 'x']], widths: [60] }]
    })
    const cols = [...short.matchAll(/<col style="width:(\d+)px">/g)].map(match => match[1])
    expect(cols[0]).toBe('60')
    // 「备注」表头 4 单位 → 50px，小于最小宽 ⇒ 抬到 64
    expect(cols[1]).toBe('64')
    // 不传 widths 的旧调用方完全不受影响
    const none = buildTrackingHtml({ sheets: [{ name: 'A', headers: ['数量'], body: [['1']] }] })
    expect(none).toContain('<col style="width:64px">')
  })

  it('导出文件可隐藏列、修改列宽和字号，并恢复默认打印设置', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    const doc = new DOMParser().parseFromString(html, 'text/html')
    const script = doc.querySelector('script')?.textContent || ''
    new Function('document', 'window', 'alert', script)(doc, window, () => {})
    const section = doc.querySelector('section.sheet')!
    const controls = section.querySelectorAll('.column-control')
    const first = controls[0].querySelector('input[type=checkbox]') as HTMLInputElement
    first.checked = false
    first.dispatchEvent(new Event('change', { bubbles: true }))
    expect((section.querySelector('thead th') as HTMLElement).style.display).toBe('none')
    expect((section.querySelector('tbody td') as HTMLElement).style.display).toBe('none')

    const width = controls[1].querySelector('input[type=number]') as HTMLInputElement
    width.value = '155'
    width.dispatchEvent(new Event('input', { bubbles: true }))
    expect((section.querySelectorAll('col')[1] as HTMLElement).style.width).toBe('155px')
    const font = doc.getElementById('font-size') as HTMLInputElement
    font.value = '16'
    font.dispatchEvent(new Event('input', { bubbles: true }))
    expect(doc.documentElement.style.getPropertyValue('--table-font-size')).toBe('16px')

    doc.getElementById('reset-print')!.click()
    expect(first.checked).toBe(true)
    expect(width.value).toBe(width.defaultValue)
    expect(font.value).toBe('12')
  })

  it('导出默认即编辑态：body 挂 is-editing、纸张预览不勾选，纸框与边界预警结构仍在', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    // 默认编辑（10-21 LQ）：不是 preview-on，编辑按钮初始就是「完成编辑」亮起态
    expect(html).toContain('<body class="is-editing">')
    expect(html).not.toContain('<body class="preview-on">')
    expect(html).toContain('<label class="toggle"><input id="paper-preview" type="checkbox">纸张预览</label>')
    expect(html).toContain('<button class="btn is-on" id="toggle-edit" type="button">完成编辑</button>')
    expect(html).toContain('<div class="screen-bar no-print">')
    expect(html).toContain('<div class="paper" id="paper">')
    // 边界预警层：右边距斜线阴影 + 红色竖线标「可打印边界」+ 标签
    expect(html).toContain('<div class="paper-guides no-print" aria-hidden="true">')
    expect(html).toContain('<div class="paper-margin"></div>')
    expect(html).toContain('<div class="paper-edge" id="paper-edge"><span class="edge-label" id="edge-label">')
    expect(html).toContain('.paper-edge{position:absolute;top:0;bottom:0;right:10mm;')
    expect(html).toContain('.paper.is-overflow .paper-edge{border-right:2px solid')
    expect(html).toContain('<div class="paper-content">')
    expect(html).toContain('<div id="drag-guide" class="drag-guide no-print" aria-hidden="true"></div>')
    // 纸面尺寸是物理单位 mm（A4 横向 297×210），10mm 页边距由纸内衬的内边距给
    expect(html).toContain('.paper{position:relative;margin:0 auto;width:297mm;min-height:210mm')
    expect(html).toContain('.paper-content{padding:10mm}')
    // 纸型默认 A4 横向，@page 规则同步
    expect(html).toContain('<option value="A4" selected>A4 横向</option>')
    expect(html).toContain('@page{size:A4 landscape;margin:10mm}')
    expect(html).toContain('<div class="print-toolbar" aria-label="打印设置">')
  })

  it('纸张预览：屏幕缩放系数与打印用同一个（所见即所印），关掉预览则退出纸面模式', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    const doc = new DOMParser().parseFromString(html, 'text/html')
    new Function('document', 'window', 'alert', doc.querySelector('script')!.textContent || '')(doc, window, () => {})
    const table = doc.querySelector('section.sheet table') as HTMLElement
    expect(table.dataset.zoom).toBe(table.style.getPropertyValue('--print-zoom'))
    expect(table.style.getPropertyValue('--print-zoom')).not.toBe('')
    const metrics = doc.querySelector('section.sheet .metrics')!
    expect(metrics.textContent).toContain('列宽合计')
    expect(metrics.textContent).toContain('纸面可打印')

    const preview = doc.getElementById('paper-preview') as HTMLInputElement
    expect(preview.checked).toBe(false)
    expect(doc.body.classList.contains('preview-on')).toBe(false)
    // 勾「纸张预览」自动退出编辑进入纸面样式（10-21 LQ）
    preview.checked = true
    preview.dispatchEvent(new Event('change', { bubbles: true }))
    expect(doc.body.classList.contains('preview-on')).toBe(true)
    expect(doc.body.classList.contains('is-editing')).toBe(false)
    // 取消勾选只回到长页面，不自动回到编辑：想改字要自己点「开启编辑」
    preview.checked = false
    preview.dispatchEvent(new Event('change', { bubbles: true }))
    expect(doc.body.classList.contains('preview-on')).toBe(false)
    expect(doc.body.classList.contains('is-editing')).toBe(false)
  })

  it('宽度读数：列宽合计超纸面时报自动缩放，窄表报「刚好一页宽」', () => {
    const run = (headers: string[]) => {
      const html = buildTrackingHtml({ sheets: [{ name: 'A', headers, body: [headers.map(() => '1')] }] })
      const doc = new DOMParser().parseFromString(html, 'text/html')
      new Function('document', 'window', 'alert', doc.querySelector('script')!.textContent || '')(doc, window, () => {})
      return doc.querySelector('.metrics')!.textContent || ''
    }
    // 20 列 × 最小宽 64px 已超 A4 纸面可打印宽度（1047px）
    expect(run(Array.from({ length: 20 }, (_, index) => `列${index}`))).toContain('自动缩放')
    expect(run(['任务号', '备注'])).toContain('刚好一页宽')
    // 60 列连 0.3 的最小缩放都救不回来 → 必须如实报「超出纸面」，不能假装适配了
    expect(run(Array.from({ length: 60 }, (_, index) => `列${index}`))).toContain('超出纸面')
  })

  it('超界预警线：有车间超出可打印边界时纸面挂 is-overflow，标签点名「右侧会被切掉」', () => {
    const run = (headers: string[]) => {
      const html = buildTrackingHtml({ sheets: [{ name: 'A', headers, body: [headers.map(() => '1')] }] })
      const doc = new DOMParser().parseFromString(html, 'text/html')
      new Function('document', 'window', 'alert', doc.querySelector('script')!.textContent || '')(doc, window, () => {})
      // 预警线只画在纸面预览里（长页面模式下隐藏）：勾上「纸张预览」再断言
      const preview = doc.getElementById('paper-preview') as HTMLInputElement
      preview.checked = true
      preview.dispatchEvent(new Event('change', { bubbles: true }))
      return doc
    }
    // 60 列：缩到最小 30% 仍超 A4 纸面 → 边界线进入告警态
    const overflow = run(Array.from({ length: 60 }, (_, index) => `列${index}`))
    expect(overflow.getElementById('paper')!.classList.contains('is-overflow')).toBe(true)
    expect(overflow.getElementById('edge-label')!.textContent).toContain('可打印边界')
    expect(overflow.getElementById('edge-label')!.textContent).toContain('右侧打印会被切掉')
    // 窄表在纸内：不告警，标签只报纸型尺寸
    const fits = run(['任务号', '备注'])
    expect(fits.getElementById('paper')!.classList.contains('is-overflow')).toBe(false)
    expect(fits.getElementById('edge-label')!.textContent).toBe('297×210 可打印边界')
  })

  it('表头把手可拖动改列宽：与数字框联动，并钳在 48–500 px', () => {
    const html = buildTrackingHtml({ sheets: [{ name: 'A', headers: ['任务号', '备注'], body: [['SCGD1', 'x']] }] })
    const doc = new DOMParser().parseFromString(html, 'text/html')
    new Function('document', 'window', 'alert', doc.querySelector('script')!.textContent || '')(doc, window, () => {})
    const section = doc.querySelector('section.sheet')!
    const table = section.querySelector('table') as HTMLElement
    // 窄表不缩放 ⇒ 屏幕位移＝列宽增量，算式可精确断言
    expect(Number(table.dataset.zoom)).toBe(1)
    const handle = section.querySelector('.col-resize[data-col="1"]') as HTMLElement
    const input = section.querySelectorAll('.column-control')[1].querySelector('input[type=number]') as HTMLInputElement
    const start = Number(input.value)

    const pointer = (type: string, clientX: number) => {
      const event = new Event(type, { bubbles: true }) as Event & { clientX: number; pointerId: number }
      event.clientX = clientX
      event.pointerId = 1
      return event
    }
    handle.dispatchEvent(pointer('pointerdown', 300))
    doc.dispatchEvent(pointer('pointermove', 400))
    expect(input.value).toBe(String(start + 100))
    expect((section.querySelectorAll('col')[1] as HTMLElement).style.width).toBe(`${start + 100}px`)

    doc.dispatchEvent(pointer('pointermove', 9000))
    expect(input.value).toBe('500')
    doc.dispatchEvent(pointer('pointermove', -9000))
    expect(input.value).toBe('48')
    doc.dispatchEvent(pointer('pointerup', -9000))
    // 抬指后不再跟随指针
    doc.dispatchEvent(pointer('pointermove', 800))
    expect(input.value).toBe('48')
  })

  it('工具条四组排布：视图/纸张/编辑/打印分组，打印是主按钮，提示语独立成第二行', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    // 分组顺序与组名（梳理谁在哪组：观感调版归视图，介质与预览归纸张，改字与落盘归编辑）
    const groups = [...html.matchAll(/<div class="tool-group"><span class="group-name">([^<]+)<\/span>/g)].map(m => m[1])
    expect(groups).toEqual(['视图', '纸张', '编辑'])
    expect(html.indexOf('group-name">纸张')).toBeGreaterThan(html.indexOf('group-name">视图'))
    expect(html.indexOf('group-name">编辑')).toBeGreaterThan(html.indexOf('group-name">纸张'))
    // 打印主操作在最后一个组里，且是 primary 实底按钮（跟次要按钮拉开层级）
    const lastGroup = html.slice(html.lastIndexOf('<div class="tool-group">'))
    expect(lastGroup).toContain('<button class="btn primary" type="button" onclick="window.print()">打印 / 另存为 PDF</button>')
    expect(lastGroup).not.toContain('id="toggle-edit"')
    // 提示语按「改字 / 调版 / 落盘」三段分句，独立占一整行（flex-basis:100%）
    expect(html).toContain('.print-tip{flex-basis:100%;')
    expect(html).toContain('<b>改字</b>')
    expect(html).toContain('<b>调版</b>')
    expect(html).toContain('<b>落盘</b>')
  })

  it('页面内编辑：打开即是编辑态（数据格可改、改字挂黄底、空表提示格不可编），「完成编辑」退出后可再开', () => {
    const html = buildTrackingHtml({
      sheets: [{ name: 'A', headers: ['任务号', '备注'], body: [['SCGD1', 'x']] }, { name: 'B', headers: ['任务号'], body: [] }]
    })
    const doc = new DOMParser().parseFromString(html, 'text/html')
    new Function('document', 'window', 'alert', doc.querySelector('script')!.textContent || '')(doc, window, () => {})
    const editBtn = doc.getElementById('toggle-edit')!
    // 脚本装载后（不点任何按钮）就已经是编辑态：数据格全部可改，「保存修改副本」按钮也在
    expect(doc.body.classList.contains('is-editing')).toBe(true)
    expect(editBtn.textContent).toBe('完成编辑')
    expect(editBtn.classList.contains('is-on')).toBe(true)
    expect(doc.getElementById('save-copy')).toBeTruthy()
    const cells = doc.querySelectorAll('tbody td:not(.empty)') as NodeListOf<HTMLElement>
    for (const cell of cells) expect(cell.contentEditable).toBe('true')
    // 空子表的「没有数据」格不进编辑
    expect((doc.querySelector('td.empty') as HTMLElement).contentEditable).toBe('false')

    // 改字 ⇒ 该格挂 edited（黄底标记的落点）
    const first = cells[0] as HTMLElement
    first.textContent = 'SCGD-已改'
    first.dispatchEvent(new Event('input', { bubbles: true }))
    expect(first.classList.contains('edited')).toBe(true)

    // 点「完成编辑」退出：恢复只读，之后即可直接打印；edited 标记保留，再开编辑仍可辨认
    editBtn.click()
    expect(doc.body.classList.contains('is-editing')).toBe(false)
    expect(editBtn.textContent).toBe('开启编辑')
    for (const cell of cells) expect(cell.contentEditable).toBe('false')
    expect(first.classList.contains('edited')).toBe(true)

    editBtn.click()
    expect(doc.body.classList.contains('is-editing')).toBe(true)
    expect(editBtn.textContent).toBe('完成编辑')
    for (const cell of cells) expect(cell.contentEditable).toBe('true')
  })

  it('保存修改副本：序列化当前 DOM（含编辑内容），并摘掉 edited 标记再落盘', () => {
    // jsdom 里拦截全局 Blob / a.click 成本高，这里钉住脚本的三个关键动作：
    // ① 克隆整个文档再序列化（浏览器 Ctrl+S 存不到页面上的编辑，必须靠这段落盘）
    // ② 落盘前摘掉 edited 标记（黄底不进新文件） ③ 走 a[download] 触发下载。
    const html = buildTrackingHtml({ sheets: SAMPLE })
    const script = html.slice(html.indexOf('<script>') + 8, html.indexOf('</script>'))
    expect(script).toContain('cloneNode(true)')
    expect(script).toContain("td.edited")
    expect(script).toContain("classList.remove('edited')")
    expect(script).toContain('outerHTML')
    expect(script).toContain('link.download')
    expect(script).toContain('new Blob')
  })

  it('编辑痕迹不上纸：@media print 里 edited 退回白底（内容在、颜色标记不打印）', () => {
    const html = buildTrackingHtml({ sheets: SAMPLE })
    const printBlock = html.slice(html.indexOf('@media print'))
    expect(printBlock).toContain('td.edited{background:transparent!important;box-shadow:none}')
    expect(html).toContain('td.edited{background:#fffaeb!important;box-shadow:inset 2px 0 0 #d08a2d}')
  })
})
