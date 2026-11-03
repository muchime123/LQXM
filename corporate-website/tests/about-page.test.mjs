import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const read = (path) => readFile(fileURLToPath(new URL(path, import.meta.url)), 'utf8')

test('关于页：标题按纯文本渲染，不再输出字面 <br />', async () => {
  const [about, css] = await Promise.all([
    read('../src/views/About.vue'),
    read('../src/assets/styles/about.css'),
  ])

  /* 标题位置用的是 {{ }}，文案里的标签会原样显示成字符 */
  assert.match(about, /const plainText = \(value\) =>/)
  assert.ok(!about.includes('<br />把包材适配'), '默认标题不能再内嵌 HTML 标签')
  assert.match(about, /title: headline\('intro', '从包装设备制造出发，把包材适配一起考虑'\)/)

  /* 标题换行交给 CSS，不用 v-html */
  assert.ok(!about.includes('v-html='), '关于页标题不应使用 v-html')
  assert.match(css, /\.about-profile__copy h2 \{[^}]*max-width: 720px/)
})

test('关于页：制造图廊按稳定标识记录失败，不按渲染下标', async () => {
  const about = await read('../src/views/About.vue')

  assert.match(about, /key: String\(row\.id \?\? row\.image \?\? `row-\$\{index\}`\)/)
  assert.match(about, /filter\(\(item\) => !galleryImageErrors\.has\(item\.key\)\)/)
  assert.match(about, /:key="item\.key"/)
  assert.match(about, /@error="onGalleryImageError\(item\.key\)"/)
  assert.ok(!about.includes('onGalleryImageError(idx)'), '不能再用渲染后的下标记录图片失败')
})

test('关于页：地图等待地址与容器就绪后初始化，且可重入', async () => {
  const about = await read('../src/views/About.vue')

  assert.match(about, /let contactMapAddress = ''/)
  assert.match(about, /if \(!address \|\| !contactMapRef\.value\) return/)
  assert.match(about, /if \(contactMapInstance && contactMapAddress === address\) return/)
  assert.match(about, /contactMapAddress = address/)
  /* 挂载时只尝试一次，随后由 watch 在地址/区块就绪后补建 */
  assert.match(about, /watch\(\s*\(\) => \[contactAddress\.value, sectionEnabled\('contact'\), contactMapRef\.value\]/)
})

test('关于页：Hero 统计口径只使用可核实数据', async () => {
  const about = await read('../src/views/About.vue')

  assert.match(about, /label: '公开设备型号'/)
  assert.match(about, /label: '公开展示资质'/)
  assert.match(about, /label: '公开展示专利'/)
  assert.ok(!about.includes("label: '产品系列'"), '产品数量不能标注为产品系列')
  assert.ok(!about.includes("label: '客户覆盖'"), '客户覆盖范围无可核实来源，不应写死')
})

test('关于页：资质/专利轮播按钮只在内容溢出时出现', async () => {
  const [about, overflow] = await Promise.all([
    read('../src/views/About.vue'),
    read('../src/composables/useScrollOverflow.js'),
  ])

  assert.match(about, /useScrollOverflow\(certificationCarousel\)/)
  assert.match(about, /useScrollOverflow\(patentCarousel\)/)
  assert.match(about, /v-if="certificationOverflowing"/)
  assert.match(about, /v-if="patentOverflowing"/)
  assert.match(overflow, /scrollWidth - el\.clientWidth > 4/)
})

test('关于页：展示数量与统计数量一致', async () => {
  const about = await read('../src/views/About.vue')

  /* 只渲染前 6 项却按整个数组统计，会在证书变多后出现「统计 8 份、只显示 6 份」 */
  assert.ok(!about.includes('.slice(0, 6)'), '证书/专利不应截断渲染')
  assert.match(about, /const certificationCount = certifications\.length/)
  assert.match(about, /const patentCount = patents\.length/)
  assert.match(about, /v-for="cert in certifications"/)
  assert.match(about, /v-for="p in patents"/)
})

test('关于页：原图失效不再指向坏地址', async () => {
  const about = await read('../src/views/About.vue')

  assert.match(about, /const markShowcaseBroken = \(kind, item\) =>/)
  assert.match(about, /@error="markShowcaseBroken\('cert', cert\)"/)
  assert.match(about, /@error="markShowcaseBroken\('patent', p\)"/)
  assert.match(about, /:href="isShowcaseBroken\('cert', cert\) \? undefined : cert\.image"/)
  assert.match(about, /:aria-disabled="isShowcaseBroken\('cert', cert\) \|\| undefined"/)
  assert.ok(!about.includes("e.target.style.display = 'none'"), '隐藏图片后仍可点击坏链接的问题必须一并处理')
})

test('关于页：专利卡片只展示图片与名称，不渲染无来源的档案字段', async () => {
  const [about, data] = await Promise.all([
    read('../src/views/About.vue'),
    read('../src/data/qualifications.js'),
  ])

  /* 后台资质/专利管理已下线，专利号与申请人没有数据来源，不应保留取不到值的分支 */
  assert.ok(!about.includes('patentNo'), '专利号已无来源，模板不应保留')
  assert.ok(!about.includes('p.applicant'), '申请人已无来源，模板不应保留')
  assert.ok(!about.includes('patent-thumb__meta'), '空的档案信息容器不应保留')
  assert.ok(!data.includes('patentNo'))
  assert.ok(!data.includes('applicant'))
  /* 展示所需的最小字段仍在 */
  assert.match(data, /image: '\/images\/patent-map-packaging\.png'/)
  assert.match(about, /\{\{ p\.patentType \}\}/)
})

test('关于页：锚点被关闭时仍有兜底落点', async () => {
  const about = await read('../src/views/About.vue')

  assert.match(about, /async function landOnHash\(\)/)
  assert.match(about, /document\.getElementById\('cta'\)\?\.scrollIntoView/)
  assert.match(about, /watch\(\(\) => route\.hash, landOnHash\)/)
})

test('关于页：已下线业务按分句剔除，不再整段替换', async () => {
  const about = await read('../src/views/About.vue')

  assert.match(about, /const RETIRED_TERMS = \['模具'\]/)
  assert.match(about, /function stripRetiredSegments\(value\)/)
  assert.match(about, /const value = stripRetiredSegments\(company\.value\?\.mainBusiness\)/)
  assert.match(about, /const value = stripRetiredSegments\(company\.value\?\.advantages\)/)
  assert.ok(!about.includes(".includes('模具')"), '关键词判断应集中到 hasRetiredTerm，不要在业务字段里各写一遍')
})
