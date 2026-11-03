import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { sectionsToHtml } from '../src/utils/sectionsToHtml.js'

const escapeHtml = (value) => String(value)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

test('旧版结构化正文保留段落与换行，不压成一段', () => {
  const html = sectionsToHtml([{ title: '问题', body: '第一段\n第二行\n\n第三段' }])
  assert.match(html, /<h2>问题<\/h2>/)
  assert.match(html, /<p>第一段<br \/>第二行<\/p>/)
  assert.match(html, /<p>第三段<\/p>/)
})

test('旧版结构化正文对正文内容转义', () => {
  const html = sectionsToHtml([{ title: '<script>', body: '<img onerror=1> & "x"' }])
  assert.ok(!html.includes('<script>'))
  assert.ok(!html.includes('<img'))
  assert.match(html, /&lt;script&gt;/)
  assert.ok(html.includes(escapeHtml('<img onerror=1> & "x"')))
})

test('空小节不产生空标签', () => {
  assert.equal(sectionsToHtml([]), '')
  assert.equal(sectionsToHtml([{ title: '  ', body: '\n\n' }]), '')
  assert.equal(sectionsToHtml([{ points: ['', '  '] }]), '')
  assert.equal(sectionsToHtml([{ title: '标题', body: '' }]), '<h2>标题</h2>')
})

test('方案文章页的状态、SEO、来源与咨询上下文契约不被回退', async () => {
  const read = (path) => readFile(fileURLToPath(new URL(path, import.meta.url)), 'utf8')
  const [view, viewer, solutions, siteCta, modalState, modal, articleCss] = await Promise.all([
    read('../src/views/SolutionArticle.vue'),
    read('../src/components/article/ArticleViewer.vue'),
    read('../src/views/Solutions.vue'),
    read('../src/components/common/SiteCTA.vue'),
    read('../src/composables/useInquiryModal.js'),
    read('../src/components/common/InquiryFormModal.vue'),
    read('../src/assets/styles/article.css'),
  ])

  /* 加载失败 / 文章未发布 / 正文待补充 三种状态必须分开 */
  assert.match(view, /const error = ref\(null\)/)
  assert.match(view, /const missing = ref\(false\)/)
  assert.match(view, /v-else-if="error"/)
  assert.match(view, /v-else-if="missing"/)
  assert.match(view, /solution-article-body-empty/)
  assert.match(view, /const hasContent = computed/)

  /* 切换文章请求竞态 */
  assert.match(view, /createLatestRequestGuard/)
  assert.match(view, /if \(!requestGuard\.isLatest\(request\)\) return/)

  /* 动态文章 SEO：canonical + 封面 OG */
  assert.match(view, /const canonical = `\$\{SITE_URL\}\/solutions\/\$\{slug\}`/)
  assert.match(view, /ogImage: model\.coverImage \|\| undefined/)
  assert.match(view, /ogType: 'article'/)

  /* 来源场景返回入口 */
  assert.match(view, /const sceneReturn = computed/)
  assert.match(view, /返回当前应用场景方案/)
  assert.match(solutions, /query: \{ scene: activeSceneId \}/)

  /* 咨询携带文章上下文 */
  assert.match(view, /articleTitle: article\.value\?\.title/)
  assert.match(siteCta, /articleTitle: \{ type: String, default: '' \}/)
  assert.match(siteCta, /sceneKey: \{ type: String, default: '' \}/)
  assert.match(modalState, /const articleTitle = ref\(''\)/)
  assert.match(modalState, /const sceneKey = ref\(''\)/)
  assert.match(modal, /&article=\$\{encodeURIComponent\(article\)\}/)
  assert.match(modal, /&sceneKey=\$\{encodeURIComponent\(sceneKeyValue\)\}/)
  assert.match(modal, /modalArticleTitle/)

  /* 阅读时间兼容旧版结构化正文 */
  assert.match(view, /function resolveHtml\(model\)/)
  assert.match(view, /const readingTime = computed/)

  /* 相关阅读按场景/分类优先 */
  assert.match(view, /item\.relatedSceneKeys\.some\(\(key\) => sceneKeys\.has\(key\)\)/)

  /* 目录：唯一 ID、章节深链接、手机端可展开、宽表格横向滚动 */
  assert.match(viewer, /function assignHeadingIds/)
  assert.match(viewer, /function wrapTables/)
  assert.match(viewer, /article-table-scroll/)
  assert.match(viewer, /window\.history\.replaceState/)
  assert.match(viewer, /const tocOpen = ref\(false\)/)
  assert.match(viewer, /contentHtml\.trim\(\)/)

  assert.match(articleCss, /\.article-table-scroll\s*\{/)
  assert.match(articleCss, /\.article-viewer__toc\[open\]/)
  assert.ok(
    !/\.article-viewer__toc\s*\{[^}]*display:\s*none/.test(articleCss),
    '手机端不应再直接隐藏文章目录',
  )
})
