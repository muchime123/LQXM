# 利强包装设备资料管理系统 - UI 规范

> 版本：v1.0  
> 更新日期：2026-07-27

---

## 一、设计原则

1. **克制优先**：只使用设计令牌中定义的颜色、间距、圆角、阴影，不硬编码值
2. **三段式布局**：弹窗 / 全屏工作区 / 页面都遵循 Header → Body → Footer 的结构
3. **统一交互**：相同模式的组件（确认、预览、加载、空状态）只保留一套实现
4. **渐进式密度**：列表页高密度，编辑页中密度，详情页低密度

---

## 二、颜色体系

所有颜色通过 CSS 变量使用，定义在 `frontend/src/styles/01-css-design-tokens.css`。

### 品牌色

| 变量 | 用途 |
|------|------|
| `--color-primary` | 主按钮、链接、选中态、重点强调 |
| `--color-primary-bg` | eyebrow 徽章、主色浅背景 |
| `--color-primary-ring` | focus 环 |

### 语义色

| 变量 | 用途 |
|------|------|
| `--color-success` | 成功状态、通过 |
| `--color-warning` | 警告、待处理 |
| `--color-danger` | 错误、删除、危险操作 |
| `--color-info` | 信息提示 |

### 中性色（文字）

| 变量 | 用途 |
|------|------|
| `--color-text-heading` | 标题、重要数据 |
| `--color-text-strong` | 正文、标签 |
| `--color-text-muted` | 次要文字、说明 |
| `--color-text-faint` | 占位符、禁用态 |

### 表面色

| 变量 | 用途 |
|------|------|
| `--color-surface` | 页面、弹窗等主背景 |
| `--color-surface-alt` | 次级背景（工具栏、侧边栏） |
| `--color-surface-hover` | hover 态背景 |
| `--color-border` | 边框 |
| `--color-overlay` | 遮罩层 |

---

## 三、间距

使用 4px 基准网格。

| 变量 | 像素值 | 用途 |
|------|--------|------|
| `--space-xs` | 4px | 元素内部微间距 |
| `--space-sm` | 8px | 小间距、紧凑模式 |
| `--space-sm-md` | 12px | 按钮内 padding、小卡片间距 |
| `--space-md` | 16px | 标准间距 |
| `--space-md-lg` | 20px | 弹窗 header 间距 |
| `--space-lg` | 24px | 区块间距 |
| `--space-lg-xl` | 32px | 大区块间距 |
| `--space-xl` | 40px | 页面级外边距 |
| `--space-2xl` | 48px | 大屏留白 |
| `--space-3xl` | 64px | 大卡片内边距 |

---

## 四、圆角

| 变量 | 像素值 | 用途 |
|------|--------|------|
| `--radius-sm` | 4px | 图片、小标签 |
| `--radius-md` | 6px | 小按钮、输入框 |
| `--radius-lg` | 8px | 标准卡片、按钮 |
| `--radius-xl` | 12px | 大卡片、弹窗内容区 |
| `--radius-2xl` | 16px | 大弹窗、工作台 |
| `--radius-pill` | 9999px | 胶囊、badge |

---

## 五、阴影

| 变量 | 用途 |
|------|------|
| `--shadow-sm` | 输入框 focus、小卡片 |
| `--shadow-md` | 标准卡片、下拉 |
| `--shadow-lg` | 悬浮卡片 |
| `--shadow-xl` | 弹窗 |

---

## 六、字体

| 变量 | 像素值 | 用途 |
|------|--------|------|
| `--fs-xxs` | 11px | badge、辅助信息 |
| `--fs-xs` | 12px | 说明、次要文字 |
| `--fs-sm` | 13px | 正文、表格 |
| `--fs-base` | 14px | 正文（强调） |
| `--fs-md` | 15px | 弹窗标题 |
| `--fs-lg` | 16px | 区块标题 |
| `--fs-xl` | 18px | 页面标题 |
| `--fs-2xl` | 20px | 大标题 |
| `--fs-4xl` | 28px | 数据大屏 |

字重：
- `--fw-medium` (500)：次要标签
- `--fw-semibold` (600)：正文加粗、按钮
- `--fw-bold` (700)：重要数据
- `--fw-extrabold` (800)：标题

---

## 七、弹窗体系（LqDialog）

所有弹窗统一使用 `LqDialog` 组件，基于 Element Plus `el-dialog` 封装。

### 组件位置

```
frontend/src/components/common/
├── LqDialog.vue          # 基础弹窗
├── LqConfirmDialog.vue   # 确认弹窗（删除/警告/提示）
├── LqFullscreen.vue      # 全屏工作区（大型操作）
└── LqPreview.vue         # 统一预览（图片/文档/资源）
```

### 尺寸规范

| size | 宽度 | 适用场景 |
|------|------|----------|
| sm | 420px | 简单确认、删除、提示 |
| md | 720px | 普通编辑、表单、基础资料 |
| lg | 1100px | 复杂业务、表格、多区域编辑 |
| xl | min(1400px, 90vw) | 大型工作台 |

也可以通过 `width` prop 自定义覆盖。

### 模式（mode）

| mode | footer | 适用场景 |
|------|--------|----------|
| `detail` | 默认隐藏 | 查看详情、只读预览 |
| `form` | 默认显示（保存/取消） | 编辑表单 |
| `workspace` | 显示，内容区 padding 减小 | 大型操作、复杂工作台 |

### 三段式结构

```
┌─────────────────────────────────────┐
│ Header  (eyebrow + title + subtitle + actions + close) │
├─────────────────────────────────────┤
│ Body    (内容区域，默认可滚动)           │
│         scrollable=false 时不自产滚动     │
├─────────────────────────────────────┤
│ Footer  (操作按钮区，form 模式默认显示)   │
└─────────────────────────────────────┘
```

### 关键 Props

| Prop | 类型 | 默认 | 说明 |
|------|------|------|------|
| `v-model` | boolean | false | 显示状态 |
| `title` | string | '' | 主标题 |
| `subtitle` | string | '' | 副标题/说明 |
| `eyebrow` | string | '' | 顶部小标签徽章 |
| `size` | sm/md/lg/xl | md | 预设尺寸 |
| `width` | string | '' | 自定义宽度 |
| `mode` | detail/form/workspace | form | 弹窗模式 |
| `dirty` | boolean | false | 脏状态，eyebrow 显示"已修改" |
| `loading` | boolean | false | 加载中，按钮禁用 |
| `scrollable` | boolean | true | body 是否可滚动 |
| `showClose` | boolean | true | 右上角关闭按钮 |
| `closeOnClickModal` | boolean | false | 点击遮罩关闭 |
| `confirmText` | string | '保存' | 确认按钮文案 |
| `cancelText` | string | '取消' | 取消按钮文案 |
| `beforeClose` | function | - | 关闭前拦截 |

### Events

| Event | 参数 | 说明 |
|-------|------|------|
| `update:modelValue` | value | 显示状态变化 |
| `close` | - | 关闭时触发 |
| `cancel` | - | 点击取消 |
| `confirm` | - | 点击确认 |

### 滚动规则

- **简单内容弹窗**：`scrollable=true`（默认），外层统一滚动
- **内部有双列/侧边栏布局的复杂弹窗**（如 ProductEditorModal、PackagingEditorModal）：`scrollable=false`，由内容区自己控制滚动
- 设置 `scrollable=false` 时，确保内容区内部有合适的 `overflow-y: auto` 和 `min-height: 0`

### 确认弹窗（LqConfirmDialog）

**使用方式**：

```ts
import { askConfirm } from '@/composables/ui/useConfirmDialog'

const ok = await askConfirm({
  title: '确认删除',
  message: '删除后无法恢复，是否继续？',
  danger: true,
  confirmText: '删除',
  cancelText: '取消',
})
```

**类型**：
- `danger`：红色警告图标（删除、危险操作）
- `warning`：黄色警告图标
- `info`：蓝色信息图标（默认）

全局唯一实例挂载在 `App.vue` 中，无需每个页面单独引入组件。

---

## 八、全屏工作区（LqFullscreen）

用于说明书生成、批量数据处理等大型操作，不适宜用弹窗承载。

```vue
<LqFullscreen
  v-model="visible"
  title="批量资料整理"
  subtitle="检查数据并执行批量处理"
>
  工作区内容
  <template #footer>
    <button @click="close">关闭</button>
    <button class="primary" @click="generate">生成</button>
  </template>
</LqFullscreen>
```

---

## 九、预览组件（LqPreview）

用于图片、文档、资源的全屏预览。

### 能力

- 内置图片缩放（0.2x~3x）与旋转
- 画廊模式（上/下一张 + 计数器）
- 支持键盘操作（ESC 关闭、← → 翻页）
- 支持自定义内容 slot
- 自动识别文件类型，集成 FilePreviewBody

### Props

| Prop | 类型 | 默认 | 说明 |
|------|------|------|------|
| `v-model` | boolean | false | 显示状态 |
| `title` | string | '预览' | 标题 |
| `subtitle` | string | '' | 副标题（文件名等） |
| `url` | string | '' | 文件 URL |
| `fileName` | string | '' | 文件名，用于类型识别 |
| `galleryIndex` | number | 0 | 画廊索引 |
| `galleryCount` | number | 0 | 画廊总数，>1 时显示导航 |
| `showImageToolbar` | boolean | 自动 | 图片工具栏，图片类型自动显示 |

### Events

| Event | 说明 |
|-------|------|
| `prev` | 上一张 |
| `next` | 下一张 |
| `close` | 关闭 |

### Slots

| 名称 | 说明 |
|------|------|
| 默认 | 自定义内容，不传时使用 FilePreviewBody |
| `actions` | header 操作区（下载、删除等） |
| `footer` | 底部操作区 |

---

## 十、页面布局规范

### 列表页（ListPageLayout）

```
┌─────────────────────────────────────┐
│ Toolbar  (搜索 + 筛选 + 操作按钮)        │
├─────────────────────────────────────┤
│                                    │
│          Data Table / Grid          │
│                                    │
├─────────────────────────────────────┤
│ Pagination                            │
└─────────────────────────────────────┘
```

- 顶部工具栏高度：约 52px
- 表格使用标准行高 40px
- 分页右对齐

### 详情页

- 标题区 eyebrow + title + description
- 内容区分组展示，每组有 header
- 标签页（Tabs）用于多个信息维度

---

## 十一、按钮规范

### 类型

| 类型 | 类名 | 适用场景 |
|------|------|----------|
| 主要 | `type="primary"` / `.primary` | 主要操作（保存、新建、确认） |
| 普通 | 默认 | 次要操作（编辑、查看） |
| 危险 | `type="danger"` / `.danger` | 删除等不可逆操作 |
| 纯文本 | plain | 链接级操作 |

### 尺寸

| 尺寸 | 高度 | 适用场景 |
|------|------|----------|
| sm | 28px | 行内操作、工具栏 |
| md | 34px | 弹窗 footer 按钮 |
| lg | 40px | 表单主要按钮 |

### 位置规则

- 弹窗 footer：右对齐，"取消"在左，"保存/确认"在右
- 表单底部：右对齐或左对齐，保持全项目一致
- 危险操作（删除）：永远在最右侧或最下侧，与主按钮有视觉间距

---

## 十二、状态展示

### 加载中（LoadingState）

用于：数据加载、提交中等需要等待的场景。

### 空状态（EmptyState）

用于：无数据、搜索无结果、筛选为空。

- 必须有说明文字
- 有操作引导时提供主按钮（如"新建产品"）

### 数据状态（DataState）

组合：loading / empty / error / content 四种状态的统一封装。

---

## 十三、表单规范

### 字段布局

- 单列：简单表单（少于 5 个字段）
- 双列：标准编辑表单
- 三列 / 四列：密集数据录入（如产品参数）
- 使用 CSS 变量 `--field-cols` 动态控制列数

### 标签

- 位置：字段上方
- 必填字段用红色 `*` 标记
- 标签字重：semibold (600)

### 输入框

- 高度：40px（标准）
- 边框：1px solid `--color-border`
- 圆角：`--radius-lg`
- focus 态：边框变主色 + 3px 主色 ring

### 校验错误

- 边框变红
- 下方显示错误提示文字
- 必填项为空时触发表单级校验

---

## 十四、响应式断点

| 断点 | 宽度 | 说明 |
|------|------|------|
| sm | 760px | 手机 / 小屏 |
| md | 980px | 平板 / 窄屏笔记本 |
| lg | 1280px | 标准桌面 |
| xl | 1600px | 大屏 / 外接显示器 |

### 小屏适配策略

- 弹窗：宽度 96vw，最大 90vh
- 表单字段：4 列 → 2 列 → 1 列
- 侧边栏：隐藏或折叠
- 工具栏：操作按钮折行或收起

---

## 十五、Z-Index 层级

| 层级 | z-index | 组件 |
|------|---------|------|
| 页面内容 | 1 | 常规内容 |
| 悬浮元素 | 100 | 下拉菜单、tooltip |
| 弹窗 | 1200 | LqDialog |
| 预览 | 2000 | LqPreview |
| 全屏工作区 | 1500 | LqFullscreen |
| 全局消息 | 3000 | notify / toast |

---

## 十六、图标规范

- 优先使用内联 SVG，确保颜色可控
- 尺寸统一：14px（小按钮）/ 16px（标准）/ 20px（大按钮）
- 颜色通过 `currentColor` 继承父元素文字色

---

## 十七、组件索引

### 公共组件（common）

| 组件 | 用途 |
|------|------|
| LqDialog | 统一弹窗 |
| LqConfirmDialog | 确认弹窗 |
| LqFullscreen | 全屏工作区 |
| LqPreview | 统一预览 |
| ListPageLayout | 列表页布局 |
| DataState | 数据状态包装 |
| EmptyState | 空状态 |
| LoadingState | 加载状态 |
| StatusTag | 状态标签 |
| AssetPreviewModal | 旧版文件预览（待迁移） |
| FilePreviewBody | 文件预览内容渲染器 |
| MarkdownEditor | Markdown 编辑器 |
| SourceTrace | 溯源信息展示 |

### 使用入口

```ts
import { LqDialog, LqConfirmDialog, LqFullscreen, LqPreview } from '@/components/common'
```

---

## 十八、不推荐的写法

### ❌ 不要直接用 el-dialog

```vue
<!-- 不推荐 -->
<el-dialog v-model="visible" title="编辑">
  ...
</el-dialog>
```

用 `<LqDialog>` 替代。

### ❌ 不要自己写遮罩 + Teleport 弹窗

```js
// 不推荐：自己实现 Teleport、ESC、滚动锁定
```

用 `<LqDialog>` 或 `<LqFullscreen>` 替代。

### ❌ 不要硬编码颜色值

```css
/* 不推荐 */
background: #fff;
color: #333;
```

用 CSS 变量替代。

### ❌ 不要在全局 CSS 中用 :deep()

```css
/* 不推荐：:deep() 只在 scoped style 中有效 */
.foo :deep(.el-dialog__body) { ... }
```

全局 CSS 直接写后代选择器即可。
