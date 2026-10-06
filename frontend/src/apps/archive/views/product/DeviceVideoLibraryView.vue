<template>
  <main class="video-library">
    <header class="video-library__header">
      <div>
        <span class="eyebrow">销售与案例素材</span>
        <h1>设备视频库</h1>
        <p>按产品分类、产品、机型、用途、技术特征和展示参数组合查找设备视频。</p>
      </div>
      <div class="header-actions">
        <div class="mode-switch">
          <button
            type="button"
            :class="{ active: mode === 'browse' }"
            @click="switchMode('browse')"
          >
            查找模式
          </button>
          <button
            type="button"
            :class="{ active: mode === 'organize' }"
            @click="switchMode('organize')"
          >
            整理模式
          </button>
        </div>
        <button type="button" :disabled="dupLoading" @click="openDuplicateCheck">重复检测</button>
        <button type="button" :disabled="mediaInfoLoading || coverBackfillLoading" @click="extractMediaInfo">
          {{ mediaInfoLoading ? '提取中…' : '提取时长' }}
        </button>
        <button type="button" :disabled="coverBackfillLoading || mediaInfoLoading" @click="generateMissingCovers">
          {{ coverBackfillLoading ? '生成中…' : '补齐封面' }}
        </button>
        <button type="button" :disabled="videos.length === 0 || inferenceApplying" @click="openInferenceReview">
          {{ inferenceApplying ? '识别中…' : '智能补全全库' }}
        </button>
        <div class="result-count"><strong>{{ pagination.pageInfo.total }}</strong><span>条视频</span></div>
      </div>
    </header>

    <section class="video-filters" aria-label="视频筛选">
      <div class="filter-row">
        <input
          v-model.trim="filters.keyword"
          type="search"
          class="filter-keyword"
          placeholder="搜索标题、产品、用途、机型或展示参数，例如：豆腐 一出四"
          @keyup.enter="reload"
        />
        <select v-model="filters.productCategory" @change="onProductCategoryChange">
          <option value="">全部产品分类</option>
          <option v-for="item in productCategoryOptions" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model.number="filters.modelId" @change="onProductChange">
          <option value="">全部产品</option>
          <option v-for="item in filteredProducts" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <select v-model.number="filters.machineTierId" :disabled="!filters.modelId">
          <option value="">全部适用机型</option>
          <option v-for="tier in tierOptions" :key="tier.id" :value="tier.id">{{ tierLabel(tier) }}</option>
        </select>
        <select v-model="filters.category">
          <option value="">全部视频分类</option>
          <option value="device_video">设备视频</option>
          <option value="operation_video">操作视频</option>
        </select>
        <select v-model="filters.application">
          <option value="">全部适用对象</option>
          <optgroup v-for="group in applicationGroups" :key="group.label" :label="group.label">
            <option v-for="child in group.children" :key="child" :value="child">{{ child }}</option>
          </optgroup>
          <option v-for="item in standaloneApplications" :key="item" :value="item">{{ item }}</option>
        </select>
      </div>
      <div class="filter-row">
        <select v-model="filters.contentType">
          <option value="">全部视频类型</option>
          <option v-for="item in contentTypeOptions" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="filters.featureTag">
          <option value="">全部设备/工艺特征</option>
          <option v-for="item in featureOptions" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="filters.demonstrationTag">
          <option value="">全部展示参数</option>
          <option v-for="item in demonstrationOptions" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="filters.usageChannel">
          <option value="">全部使用渠道</option>
          <option v-for="item in usageChannelOptions" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="filters.contentStatus">
          <option value="">全部内容状态</option>
          <option v-for="item in contentStatusOptions" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="filters.scope">
          <option value="">全部适用范围</option>
          <option value="ALL">产品通用（已确认）</option>
          <option value="SELECTED">指定机型</option>
          <option value="UNREVIEWED">尚未确认范围</option>
        </select>
        <select v-model="filters.hasCover">
          <option value="">封面不限</option>
          <option value="yes">有封面</option>
          <option value="no">无封面</option>
        </select>
        <button type="button" class="primary" @click="reload">查询</button>
        <button type="button" @click="resetFilters">重置</button>
      </div>
      <div class="quick-chips">
        <button
type="button"
class="chip"
:class="{ active: filters.incomplete }"
@click="toggleIncomplete">
          待整理（存在必填信息缺失）
        </button>
        <button
          type="button"
          class="chip"
          :class="{ active: filters.hasCover === 'no' }"
          @click="setHasCover(filters.hasCover === 'no' ? '' : 'no')"
        >
          缺少封面
        </button>
        <button
          type="button"
          class="chip"
          :class="{ active: filters.scope === 'UNREVIEWED' }"
          @click="setScope(filters.scope === 'UNREVIEWED' ? '' : 'UNREVIEWED')"
        >
          待确认范围
        </button>
        <button
          type="button"
          class="chip chip-favorite"
          :class="{ active: filters.favorite }"
          @click="toggleFavoriteFilter"
        >
          ★ 只看收藏
        </button>
        <span v-if="activeFilterCount > 0" class="active-hint">已启用 {{ activeFilterCount }} 个筛选条件</span>
      </div>
    </section>

    <DeviceVideoGovernanceSummary
      :summary="governanceSummary"
      :active-missing-item="filters.missingItem"
      @select-missing="setMissingItem"
    />

    <section v-if="loading" class="video-state">正在加载设备视频…</section>
    <section v-else-if="videos.length === 0" class="video-state">
      <strong>没有找到匹配的视频</strong>
      <span>可减少筛选条件，或使用「智能补全全库」整理历史视频的检索信息。</span>
    </section>
    <section v-else-if="mode === 'organize'" class="workbench">
      <div class="batch-bar">
        <label class="batch-select-all">
          <input
            type="checkbox"
            :checked="allPageSelected"
            :disabled="batchApplying"
            @change="toggleSelectAllPage"
          />
          本页全选
        </label>
        <span class="batch-count">已选 {{ selectedIds.size }} 条</span>
        <span class="batch-divider" aria-hidden="true"></span>
        <select v-model="batchChannel" :disabled="!selectedIds.size || batchApplying">
          <option value="">批量设置渠道…</option>
          <option v-for="item in usageChannelOptions" :key="'bc' + item" :value="item">{{ item }}</option>
        </select>
        <button
          type="button"
          :disabled="!batchChannel || !selectedIds.size || batchApplying"
          @click="applyBatchChannel"
        >
          应用渠道
        </button>
        <select v-model="batchStatus" :disabled="!selectedIds.size || batchApplying">
          <option value="">批量设置状态…</option>
          <option v-for="item in contentStatusOptions" :key="'bs' + item" :value="item">{{ item }}</option>
        </select>
        <button
          type="button"
          :disabled="!batchStatus || !selectedIds.size || batchApplying"
          @click="applyBatchStatus"
        >
          应用状态
        </button>
        <input
          v-model.trim="batchApplicationInput"
          placeholder="追加用途，逗号分隔"
          :disabled="!selectedIds.size || batchApplying"
        />
        <button
          type="button"
          :disabled="!batchApplicationInput || !selectedIds.size || batchApplying"
          @click="applyBatchAppendTags('application')"
        >
          追加用途
        </button>
        <input
          v-model.trim="batchFeatureInput"
          placeholder="追加特征，逗号分隔"
          :disabled="!selectedIds.size || batchApplying"
        />
        <button
          type="button"
          :disabled="!batchFeatureInput || !selectedIds.size || batchApplying"
          @click="applyBatchAppendTags('feature')"
        >
          追加特征
        </button>
        <button
          type="button"
          class="confirm-scope"
          :disabled="!selectedIds.size || batchApplying"
          title="清空机型绑定并标记为产品通用（所有机型适用）"
          @click="confirmProductGeneral"
        >
          确认产品通用
        </button>
        <button type="button" :disabled="!selectedIds.size" @click="clearSelection">清除选择</button>
      </div>
      <div class="table-wrap">
        <table class="workbench-table">
          <thead>
            <tr>
              <th class="col-check" aria-label="选择"></th>
              <th class="col-title">视频</th>
              <th>产品</th>
              <th>用途</th>
              <th>类型</th>
              <th>特征</th>
              <th>展示参数</th>
              <th>渠道</th>
              <th>状态</th>
              <th>时长</th>
              <th>范围</th>
              <th>完整度</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="video in videos"
              :key="video.id"
              :class="{ selected: selectedIds.has(video.id) }"
            >
              <td class="col-check">
                <input
                  type="checkbox"
                  :checked="selectedIds.has(video.id)"
                  :disabled="batchApplying"
                  @change="toggleSelect(video.id)"
                />
              </td>
              <td class="col-title">
                <span class="title-line">
                  <span class="title-text" :title="video.videoTitle ? video.fileName : displayTitle(video)">
                    {{ displayTitle(video) }}
                  </span>
                  <span
                    v-if="fileNameConflict(video)"
                    class="conflict-dot"
                    title="文件名疑似拼接错误（识别到多个冲突用途），请人工核对"
                  >!</span>
                </span>
                <small class="file-name">{{ video.fileName }}</small>
              </td>
              <td>{{ video.modelName }}</td>
              <td>{{ splitTags(video.applicationTags).join('、') || '—' }}</td>
              <td>{{ video.videoContentType || '—' }}</td>
              <td>{{ splitTags(video.featureTags).join('、') || '—' }}</td>
              <td>{{ splitTags(video.demonstrationTags).join('、') || '—' }}</td>
              <td>{{ splitTags(video.videoUsageChannels).join('、') || '—' }}</td>
              <td>{{ video.videoContentStatus || '—' }}</td>
              <td class="col-duration">
                <template v-if="video.videoDurationSeconds">{{ formatDuration(video.videoDurationSeconds) }}</template>
                <template v-else>—</template>
                <small v-if="video.videoWidth && video.videoHeight" class="resolution-text">
                  {{ video.videoWidth }}×{{ video.videoHeight }}
                </small>
              </td>
              <td>
                <span class="scope-badge" :class="'scope-' + scopeOf(video)">{{ scopeText(video) }}</span>
              </td>
              <td>
                <span class="complete-badge" :class="completenessOf(video).ok ? 'ok' : 'missing'">
                  {{ completenessOf(video).text }}
                </span>
              </td>
              <td class="col-actions">
                <button
                  type="button"
                  class="favorite-star"
                  :class="{ on: video.videoFavorite }"
                  :title="video.videoFavorite ? '取消收藏' : '收藏'"
                  @click="toggleFavorite(video)"
                >★</button>
                <button type="button" @click="openEdit(video)">整理</button>
                <button type="button" @click="previewById(video.id)">预览</button>
                <button type="button" @click="openFolder(video)">文件夹</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-else class="video-grid">
      <article v-for="(video, index) in videos" :key="video.id" class="video-card">
        <button type="button" class="video-cover" @click="preview(index)">
          <img
            v-if="video.coverFileName && !broken.has(video.id)"
            :src="thumbnailUrl(video)"
            :alt="displayTitle(video)"
            loading="lazy"
            @error="markBroken(video.id)"
          />
          <span v-else class="video-placeholder"><b>▶</b>点击预览视频</span>
          <span class="badge badge-category">{{ video.category === 'operation_video' ? '操作视频' : '设备视频' }}</span>
          <span class="badge badge-scope" :class="'scope-' + scopeOf(video)">{{ scopeText(video) }}</span>
          <span v-if="video.videoDurationSeconds" class="badge badge-duration">{{ formatDuration(video.videoDurationSeconds) }}</span>
        </button>
        <div class="video-card__body">
          <h3 class="video-title" :title="video.videoTitle ? video.fileName : displayTitle(video)">
            <button
              type="button"
              class="favorite-star"
              :class="{ on: video.videoFavorite }"
              :title="video.videoFavorite ? '取消收藏' : '收藏'"
              @click.stop="toggleFavorite(video)"
            >★</button>
            {{ displayTitle(video) }}
          </h3>
          <p class="video-product">
            <strong>{{ video.modelName }}</strong>
            <span v-if="video.modelCode" class="model-code">{{ video.modelCode }}</span>
          </p>
          <div class="chips applications">
            <span v-for="tag in splitTags(video.applicationTags)" :key="tag">{{ tag }}</span>
            <em v-if="!splitTags(video.applicationTags).length">未设置用途</em>
          </div>
          <p v-if="metaLine(video)" class="meta-line">{{ metaLine(video) }}</p>
          <p class="scope-line" :class="{ warn: scopeOf(video) === 'UNREVIEWED' }">{{ scopeLine(video) }}</p>
          <div class="card-footer">
            <div class="footer-info">
              <span class="status-badge" :class="statusClass(video)">{{ video.videoContentType || '未分类' }}</span>
              <span class="status-dot">·</span>
              <span class="status-badge status-state" :class="'state-' + (video.videoContentStatus || '未设置')">{{ video.videoContentStatus || '未设置' }}</span>
              <span class="completeness-badge" :class="{ complete: isVideoComplete(video) }" :title="completenessHint(video)">
                {{ completenessBadgeText(video) }}
              </span>
            </div>
            <div class="row-actions">
              <button type="button" class="action-btn primary" @click="preview(index)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>
                预览
              </button>
              <button type="button" class="action-btn" @click="openFolder(video)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                文件夹
              </button>
              <button type="button" class="action-btn" @click.stop="toggleCardMenu(video, $event)">
                更多
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
              </button>
            </div>
          </div>
        </div>
      </article>
    </section>

    <footer class="pagination">
      <LqPagination
:pagination="pagination"
:page-sizes="[12, 24, 48, 96]"
:disabled="loading"
hide-on-single-page
@change="loadVideos" />
    </footer>

    <!-- 卡片「更多」菜单：Teleport 到 body + fixed，避免被卡片 overflow 裁剪 -->
    <Teleport to="body">
      <div
v-if="cardMenuVideo"
class="card-menu-panel"
:style="cardMenuStyle"
@click.stop>
        <button type="button" @click="openEdit(cardMenuVideo)">快速整理</button>
        <button type="button" @click="runCardAutoCover(cardMenuVideo)">自动封面</button>
        <button type="button" @click="toggleFavorite(cardMenuVideo)">{{ cardMenuVideo.videoFavorite ? '取消收藏' : '收藏' }}</button>
        <button type="button" @click="openAddToCollection(cardMenuVideo)">加入客户清单</button>
        <button type="button" @click="openProduct(cardMenuVideo)">所属产品</button>
        <button type="button" @click="copyFileName(cardMenuVideo)">复制文件名</button>
      </div>
    </Teleport>

    <LqDialog
v-model="editVisible"
title="整理视频检索信息"
size="sm"
mode="form"
confirm-text="保存"
@confirm="saveEdit">
      <div v-if="editing" class="quick-edit">
        <p class="edit-context">{{ editing.modelName }} · 原文件名：{{ editing.fileName }}</p>
        <div class="inline-field">
          <input v-model="editForm.videoTitle" placeholder="默认由文件名清洗生成" />
          <button type="button" @click="regenerateTitle">从文件名生成</button>
        </div>
        <button type="button" class="infer-button" @click="inferEditingFileName">根据中文文件名识别</button>
        <label><span>普通内容标签</span><input v-model="editForm.contentTags" placeholder="设备正面、细节特写" /></label>
        <label><span>适用对象</span><input v-model="editForm.applicationTags" placeholder="豆腐、酸奶、预制菜" /></label>
        <div class="chips suggestions">
          <button
v-for="tag in applicationSuggestions"
:key="tag"
type="button"
@click="toggleApplication(tag)">{{ tag }}</button>
        </div>
        <label><span>视频类型</span>
          <select v-model="editForm.videoContentType">
            <option value="">未设置</option>
            <option v-for="item in contentTypeOptions" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
        <label><span>设备/工艺特征</span><input v-model="editForm.featureTags" placeholder="按钮自动、卷膜、气动" /></label>
        <label><span>视频展示参数</span><input v-model="editForm.demonstrationTags" placeholder="一出四、175×130mm" /></label>
        <label><span>使用渠道</span><input v-model="editForm.videoUsageChannels" placeholder="可发客户、销售演示、官网可用" /></label>
        <div class="chips suggestions">
          <button
v-for="tag in usageChannelOptions"
:key="tag"
type="button"
@click="toggleUsageChannel(tag)">{{ tag }}</button>
        </div>
        <label><span>内容状态</span>
          <select v-model="editForm.videoContentStatus">
            <option v-for="item in contentStatusOptions" :key="item" :value="item">{{ item }}</option>
          </select>
        </label>
        <div class="scope-confirm">
          <span>适用范围确认</span>
          <label class="radio-row">
            <input v-model="editScopeConfirmed" type="radio" :value="false" />
            <span>尚未确认</span>
          </label>
          <label class="radio-row">
            <input v-model="editScopeConfirmed" type="radio" :value="true" />
            <span>确认适用于整个产品（所有机型通用）</span>
          </label>
          <p class="edit-hint">如需绑定到指定机型，请在所属产品的素材管理中调整机型关系。</p>
        </div>
        <p v-if="editingHints.length" class="spec-hints">识别到展示参数：{{ editingHints.join('、') }}。这些信息只用于检索，不会修改产品配置。</p>
      </div>
    </LqDialog>

    <LqDialog v-model="collectionVisible" title="加入客户视频清单" size="sm" mode="form" :show-footer="false">
      <div class="collection-picker">
        <p v-if="collectionVideo" class="edit-context">{{ displayTitle(collectionVideo) }}</p>
        <button
          v-for="collection in collections"
          :key="collection.id"
          type="button"
          class="collection-option"
          @click="addCurrentVideoToCollection(collection.id)"
        >
          <strong>{{ collection.name }}</strong><span>{{ collection.itemCount }} 个视频</span>
        </button>
        <div class="collection-create">
          <input v-model.trim="newCollectionName" placeholder="新建清单，例如：酸奶客户方案" @keyup.enter="createAndAddCollection" />
          <button type="button" :disabled="!newCollectionName" @click="createAndAddCollection">新建并加入</button>
        </div>
      </div>
    </LqDialog>

    <LqDialog
      v-model="inferenceVisible"
      title="确认全库文件名识别结果"
      size="lg"
      mode="form"
      confirm-text="应用所选结果"
      :confirm-disabled="inferenceApplying || selectedInferenceCount === 0"
      @confirm="applyInferenceReview"
    >
      <div class="inference-review">
        <p>仅补充识别到的用途、类型、特征和展示参数；已有标签会保留，不自动修改机型关系。高置信度默认勾选，中置信度请人工确认，冲突项不自动应用。</p>
        <p v-if="inferenceSummary" class="inference-summary">{{ inferenceSummary }}</p>
        <label
v-for="item in inferenceRows"
:key="item.video.id"
class="inference-row"
:class="'row-' + item.inferred.confidence">
          <input v-model="item.selected" type="checkbox" :disabled="!item.hasResult || item.inferred.confidence === 'conflict'" />
          <span class="confidence-badge" :class="'confidence-' + item.inferred.confidence">{{ confidenceText(item.inferred.confidence) }}</span>
          <span class="inference-main">
            <b>{{ displayTitle(item.video) }}</b>
            <small>{{ item.video.modelName }} · {{ item.video.fileName }}</small>
            <small v-if="item.inferred.confidence === 'conflict'" class="conflict-text">
              疑似拼接错误：{{ item.inferred.conflictedApplications.join('、') }} —— 请手动核对后再录入
            </small>
            <small v-else>
              用途：{{ item.inferred.applicationTags.join('、') || '未识别' }}　类型：{{ item.inferred.videoContentType || '未识别' }}　特征：{{ item.inferred.featureTags.join('、') || '未识别' }}
            </small>
            <small v-if="item.inferred.specificationHints.length && item.inferred.confidence !== 'conflict'" class="hint-text">
              展示参数：{{ item.inferred.specificationHints.join('、') }}
            </small>
          </span>
        </label>
      </div>
    </LqDialog>

    <LqDialog
      v-model="dupVisible"
      title="重复视频检测"
      size="lg"
      mode="form"
      :show-confirm="false"
      cancel-text="关闭"
    >
      <div class="dup-review">
        <p class="dup-toolbar">
          <button type="button" :disabled="dupLoading" @click="refreshDuplicates(true)">
            {{ dupLoading ? '检测中…' : '重新计算哈希并检测' }}
          </button>
          <span>
            精确重复 = 文件内容完全一致（SHA-256）；疑似重复 = 清洗后标题相同。系统仅提示分组，不会自动删除。
          </span>
        </p>
        <p v-if="dupLoading && !dupResult" class="dup-empty">正在计算全库视频哈希，大文件可能需要一些时间…</p>
        <section
          v-else-if="dupResult && dupResult.exactGroups.length === 0 && dupResult.suspectedGroups.length === 0"
          class="dup-empty"
        >
          未发现精确或疑似重复的视频。
        </section>
        <template v-if="dupResult?.exactGroups.length">
          <h4>精确重复 · {{ dupResult.exactGroups.length }} 组</h4>
          <div v-for="(group, gi) in dupResult.exactGroups" :key="'e' + gi" class="dup-group danger">
            <p class="dup-group-title">组 {{ gi + 1 }}：内容完全一致的 {{ group.length }} 个文件</p>
            <div v-for="item in group" :key="item.id" class="dup-row">
              <span class="dup-main">
                <b>{{ item.videoTitle || item.fileName }}</b>
                <small>{{ item.modelName }} · {{ item.fileName }}</small>
              </span>
              <div class="row-actions">
                <button type="button" @click="openProductById(item.modelId)">所属产品</button>
                <button type="button" @click="openFolderById(item)">文件夹</button>
              </div>
            </div>
          </div>
        </template>
        <template v-if="dupResult?.suspectedGroups.length">
          <h4>疑似重复 · {{ dupResult.suspectedGroups.length }} 组</h4>
          <div v-for="(group, gi) in dupResult.suspectedGroups" :key="'s' + gi" class="dup-group warn">
            <p class="dup-group-title">组 {{ gi + 1 }}：清洗后标题相同的 {{ group.length }} 个文件（可能是同一素材的多版本）</p>
            <div v-for="item in group" :key="item.id" class="dup-row">
              <span class="dup-main">
                <b>{{ item.videoTitle || item.fileName }}</b>
                <small>{{ item.modelName }} · {{ item.fileName }}</small>
              </span>
              <div class="row-actions">
                <button type="button" @click="openProductById(item.modelId)">所属产品</button>
                <button type="button" @click="openFolderById(item)">文件夹</button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </LqDialog>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import LqDialog from '@/components/common/LqDialog.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import DeviceVideoGovernanceSummary from '@/components/product/DeviceVideoGovernanceSummary.vue'
import {
  fetchCatalogVideos,
  fetchCatalogVideoFilterOptions,
  fetchVideoGovernanceSummary,
  updateCatalogAssetMetadata,
  updateCatalogAssetMetadataBatch,
  backfillVideoHashes,
  fetchVideoDuplicates,
  backfillVideoMediaInfo,
  autoCoverVideo,
  backfillVideoAutoCovers,
  setPersonalVideoFavorite,
  recordVideoView,
  fetchVideoCollections,
  createVideoCollection,
  addVideoToCollection,
  type VideoAssetSearchParams,
  type CatalogAssetBatchMetadataPayload,
  type VideoDuplicatesResult,
  type VideoDuplicateGroup,
  type VideoGovernanceSummary,
  type VideoCollection
} from '@/api/catalog/assets'
import { fetchCatalogModelsSimple } from '@/api/catalog/models'
import { fetchMachineTiers, type CatalogMachineTier } from '@/api/catalog/machineTiers'
import { fileAssetThumbnailUrlById } from '@/api/file/fileAssetUrls'
import { openFileAssetFolderByOptions, fileAssetOpenFolderMessage } from '@/utils/ui/fileAssetUrls'
import { notify } from '@/composables/ui/useToast'
import {
  VIDEO_APPLICATION_SUGGESTIONS,
  VIDEO_APPLICATION_GROUPS,
  VIDEO_CONTENT_TYPES,
  VIDEO_USAGE_CHANNELS,
  VIDEO_CONTENT_STATUSES,
  expandApplicationTerm
} from '@/constants/videoMetadata'
import {
  inferVideoMetadataFromFileName,
  mergeInferredTags,
  cleanVideoTitle,
  displayVideoTitle as displayTitle,
  type InferredVideoMetadata,
  type InferredVideoConfidence
} from '@/utils/catalog/videoFilenameMetadata'
import { useListPagination } from '@/composables/list/useListPagination'
import { useVideoGovernanceDisplay } from '@/composables/catalog/useVideoGovernanceDisplay'
import { usePreviewAsset } from '@/composables/ui/useAppBridge'

interface VideoAsset {
  id: number
  modelId: number
  modelName: string
  modelCode?: string
  equipmentCategory?: string
  machineTierIds?: number[]
  machineTierLabels?: string[]
  category: string
  fileName: string
  contentTags?: string
  applicationTags?: string
  videoContentType?: string
  videoUsageChannels?: string
  videoContentStatus?: string
  featureTags?: string
  demonstrationTags?: string
  videoTitle?: string
  machineScopeConfirmed?: boolean
  videoDurationSeconds?: number | null
  videoWidth?: number | null
  videoHeight?: number | null
  videoFavorite?: boolean
  coverFileName?: string
  complete?: boolean
  missingItems?: string[]
  allowedNextStatuses?: string[]
}

interface ModelSimpleRowLike {
  id: number
  name: string
  category?: string
}

const openAssetPreview = usePreviewAsset()
const router = useRouter()

const videos = ref<VideoAsset[]>([])
const loading = ref(false)
const broken = reactive(new Set<number>())
const products = ref<ModelSimpleRowLike[]>([])
const tierOptions = ref<CatalogMachineTier[]>([])

const filters = reactive({
  keyword: '',
  productCategory: '',
  modelId: '' as number | '',
  machineTierId: '' as number | '',
  category: '',
  application: '',
  contentType: '',
  featureTag: '',
  demonstrationTag: '',
  usageChannel: '',
  contentStatus: '',
  scope: '',
  hasCover: '' as '' | 'yes' | 'no',
  incomplete: false,
  favorite: false,
  missingItem: ''
})

const governanceSummary = ref<VideoGovernanceSummary | null>(null)
const { completenessOf, completenessHint, isVideoComplete, completenessBadgeText } = useVideoGovernanceDisplay()
const collectionVisible = ref(false)
const collectionVideo = ref<VideoAsset | null>(null)
const collections = ref<VideoCollection[]>([])
const newCollectionName = ref('')

const options = reactive<{
  applications: string[]
  contentTypes: string[]
  usageChannels: string[]
  contentStatuses: string[]
  features: string[]
  demonstrations: string[]
}>({ applications: [], contentTypes: [], usageChannels: [], contentStatuses: [], features: [], demonstrations: [] })

const pagination = useListPagination({ size: 24 })

const productCategoryOptions = computed(() =>
  [...new Set(products.value.map(item => item.category).filter((item): item is string => Boolean(item)))].sort()
)
const filteredProducts = computed(() =>
  filters.productCategory
    ? products.value.filter(item => item.category === filters.productCategory)
    : products.value
)
const applicationGroups = computed(() =>
  VIDEO_APPLICATION_GROUPS.map(group => ({
    label: group.label,
    children: [...new Set([...group.children, ...options.applications.filter(tag => group.children.includes(tag))])]
  }))
)
const groupedChildren = computed(() => new Set(applicationGroups.value.flatMap(group => group.children)))
const standaloneApplications = computed(() => [
  ...new Set([...VIDEO_APPLICATION_SUGGESTIONS, ...options.applications].filter(tag => !groupedChildren.value.has(tag)))
])
const contentTypeOptions = computed(() => [...new Set([...VIDEO_CONTENT_TYPES, ...options.contentTypes])])
const usageChannelOptions = computed(() => [...new Set([...VIDEO_USAGE_CHANNELS, ...options.usageChannels])])
const contentStatusOptions = computed(() => [...new Set([...VIDEO_CONTENT_STATUSES, ...options.contentStatuses])])
const featureOptions = computed(() => [...new Set([...options.features])].sort())
const demonstrationOptions = computed(() => [...new Set([...options.demonstrations])].sort())

const activeFilterCount = computed(
  () =>
    [
      filters.keyword,
      filters.productCategory,
      filters.modelId !== '' ? String(filters.modelId) : '',
      filters.machineTierId !== '' ? String(filters.machineTierId) : '',
      filters.category,
      filters.application,
      filters.contentType,
      filters.featureTag,
      filters.demonstrationTag,
      filters.usageChannel,
      filters.contentStatus,
      filters.scope,
      filters.hasCover,
      filters.incomplete ? 'incomplete' : '',
      filters.favorite ? 'favorite' : '',
      filters.missingItem
    ].filter(Boolean).length
)

// ===== 快速编辑 =====
const editVisible = ref(false)
const editing = ref<VideoAsset | null>(null)
const editForm = reactive({
  contentTags: '',
  applicationTags: '',
  videoContentType: '',
  videoUsageChannels: '',
  videoContentStatus: '',
  featureTags: '',
  demonstrationTags: '',
  videoTitle: ''
})
const editScopeConfirmed = ref(false)
const editingHints = ref<string[]>([])
const applicationSuggestions = computed(() => [...new Set([...standaloneApplications.value])])

// ===== 卡片更多菜单 =====
const cardMenuVideo = ref<VideoAsset | null>(null)
const cardMenuStyle = ref<Record<string, string>>({})

// ===== 智能补全（全库） =====
const inferenceVisible = ref(false)
const inferenceApplying = ref(false)
interface InferenceRow {
  video: VideoAsset
  inferred: InferredVideoMetadata
  selected: boolean
  hasResult: boolean
}
const inferenceRows = ref<InferenceRow[]>([])
const selectedInferenceCount = computed(() => inferenceRows.value.filter(item => item.selected).length)
const inferenceSummary = computed(() => {
  if (!inferenceRows.value.length) return ''
  const high = inferenceRows.value.filter(item => item.inferred.confidence === 'high').length
  const medium = inferenceRows.value.filter(item => item.inferred.confidence === 'medium').length
  const conflict = inferenceRows.value.filter(item => item.inferred.confidence === 'conflict').length
  return `共 ${inferenceRows.value.length} 条：高置信度 ${high} 条已默认勾选；中置信度 ${medium} 条需确认；冲突 ${conflict} 条仅提示。`
})

function tierLabel(tier: CatalogMachineTier) {
  return tier.tierLabel || tier.tierCode
}

// ===== 整理模式 =====
type WorkbenchMode = 'browse' | 'organize'
const mode = ref<WorkbenchMode>('browse')
const selectedIds = ref<Set<number>>(new Set())
const batchChannel = ref('')
const batchStatus = ref('')
const batchApplicationInput = ref('')
const batchFeatureInput = ref('')
const batchApplying = ref(false)

const allPageSelected = computed(
  () => videos.value.length > 0 && videos.value.every(video => selectedIds.value.has(video.id))
)

function switchMode(next: WorkbenchMode) {
  mode.value = next
  if (next === 'browse') clearSelection()
}

function toggleSelect(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAllPage() {
  const next = new Set(selectedIds.value)
  if (allPageSelected.value) videos.value.forEach(video => next.delete(video.id))
  else videos.value.forEach(video => next.add(video.id))
  selectedIds.value = next
}

function clearSelection() {
  selectedIds.value = new Set()
  batchChannel.value = ''
  batchStatus.value = ''
  batchApplicationInput.value = ''
  batchFeatureInput.value = ''
}

function fileNameConflict(video: VideoAsset) {
  return inferVideoMetadataFromFileName(video.fileName, video.category).confidence === 'conflict'
}

/**
 * 视频完整度（动态规则，与后端 VideoGovernanceRules.missingCompletenessItems 镜像）：
 * - 运行演示类：视频类型 + 适用范围确认 + 用途标签 + 展示参数
 * - 操作教学/维护维修类：视频类型 + 适用范围确认 + 内容标签
 * - 宣传成片：视频类型 + 适用范围确认 + 使用渠道
 * - 原始素材/未分组：仅视频类型 + 适用范围确认
 * 旧版静态四项检查（用途+类型+功能特点+范围）已废弃，因为它要求操作教学也填用途、要求所有视频都填功能特点，与业务实际不符。
 */
/** 内容类型徽章配色：演示类蓝、教学类绿、维护类橙、宣传类紫、其他灰。 */
function statusClass(video: VideoAsset): string {
  const type = video.videoContentType || ''
  if (['设备运行', '客户现场', '封口效果'].includes(type)) return 'type-demo'
  if (['操作教学', '安装调试', '参数调整', '换模调机', '部件更换'].includes(type)) return 'type-howto'
  if (['清洁维护', '故障处理'].includes(type)) return 'type-maint'
  if (type === '宣传成片') return 'type-promo'
  return 'type-other'
}

async function runBatch(payload: Omit<CatalogAssetBatchMetadataPayload, 'ids'>, successMessage: string) {
  const ids = [...selectedIds.value]
  if (!ids.length || batchApplying.value) return
  batchApplying.value = true
  try {
    await updateCatalogAssetMetadataBatch({ ids, ...payload })
    notify(successMessage.replace('%n', String(ids.length)), 'success')
    clearSelection()
    await Promise.all([loadVideos(), loadOptions(), loadGovernanceSummary()])
  } catch (e) {
    notify.errorWithDetail('批量操作失败', e)
  } finally {
    batchApplying.value = false
  }
}

function applyBatchChannel() {
  runBatch({ videoUsageChannelsAction: 'REPLACE', videoUsageChannels: batchChannel.value },
    `已为 %n 个视频设置渠道「${batchChannel.value}」`)
}

function applyBatchStatus() {
  runBatch({ videoContentStatusAction: 'REPLACE', videoContentStatus: batchStatus.value },
    `已为 %n 个视频设置状态「${batchStatus.value}」`)
}

function applyBatchAppendTags(kind: 'application' | 'feature') {
  const tags = kind === 'application' ? batchApplicationInput.value : batchFeatureInput.value
  if (!tags) return
  const payload: Omit<CatalogAssetBatchMetadataPayload, 'ids'> =
    kind === 'application'
      ? { applicationTagsAction: 'APPEND', applicationTags: tags }
      : { featureTagsAction: 'APPEND', featureTags: tags }
  runBatch(payload, `已为 %n 个视频追加${kind === 'application' ? '用途' : '特征'}标签`)
}

function confirmProductGeneral() {
  runBatch({ machineTierAction: 'UNLINK', machineScopeConfirmed: true }, '已将 %n 个视频确认为产品通用')
}

// ===== 重复检测 =====
const dupVisible = ref(false)
const dupLoading = ref(false)
const dupResult = ref<VideoDuplicatesResult | null>(null)

async function openDuplicateCheck() {
  dupVisible.value = true
  await refreshDuplicates(true)
}

async function refreshDuplicates(withBackfill: boolean) {
  if (dupLoading.value) return
  dupLoading.value = true
  try {
    if (withBackfill) {
      const stats = await backfillVideoHashes()
      notify(`哈希计算完成：新算 ${stats.computed} 条，缺失文件 ${stats.missingFiles} 条`, 'success')
    }
    dupResult.value = await fetchVideoDuplicates()
  } catch (e) {
    notify.errorWithDetail('重复检测失败', e)
  } finally {
    dupLoading.value = false
  }
}

function openProductById(modelId: number) {
  router.push({ path: '/products', query: { id: String(modelId), tab: 'assets' } })
}

async function openFolderById(item: VideoDuplicateGroup) {
  try {
    const result = await openFileAssetFolderByOptions({ ownerType: 'product_model', assetId: item.id })
    const feedback = fileAssetOpenFolderMessage(result)
    notify[feedback.type](feedback.message)
  } catch (err) {
    notify.errorWithDetail('打开文件夹失败', err)
  }
}

// ===== 媒体信息 / 自动封面 / 收藏 =====
const mediaInfoLoading = ref(false)
const coverBackfillLoading = ref(false)

function formatDuration(totalSeconds: number) {
  const s = Math.max(0, Math.round(totalSeconds))
  const hours = Math.floor(s / 3600)
  const minutes = Math.floor((s % 3600) / 60)
  const seconds = s % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(seconds).padStart(2, '0')
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`
}

async function extractMediaInfo() {
  if (mediaInfoLoading.value) return
  mediaInfoLoading.value = true
  notify('正在提取全库视频时长与分辨率…', 'info')
  try {
    const stats = await backfillVideoMediaInfo()
    notify(`媒体信息提取完成：新提取 ${stats.extracted} 条，失败 ${stats.failed} 条`, 'success')
    await loadVideos()
  } catch (e) {
    notify.errorWithDetail('媒体信息提取失败', e)
  } finally {
    mediaInfoLoading.value = false
  }
}

async function generateMissingCovers() {
  if (coverBackfillLoading.value) return
  coverBackfillLoading.value = true
  notify('正在为缺少封面的视频自动抽帧生成封面…', 'info')
  try {
    const stats = await backfillVideoAutoCovers()
    notify(`自动封面完成：生成 ${stats.generated} 张，已有封面跳过 ${stats.skippedHasCover} 条，失败 ${stats.failed} 条`, 'success')
    await loadVideos()
  } catch (e) {
    notify.errorWithDetail('自动封面生成失败', e)
  } finally {
    coverBackfillLoading.value = false
  }
}

async function runCardAutoCover(video: VideoAsset) {
  closeCardMenu()
  try {
    const result = await autoCoverVideo(video.id, Boolean(video.coverFileName))
    if (result.generated) notify('已从视频抽取一帧作为封面', 'success')
    else notify(result.reason || '未生成新封面', 'info')
    await Promise.all([loadVideos(), loadOptions(), loadGovernanceSummary()])
  } catch (e) {
    notify.errorWithDetail('自动封面失败', e)
  }
}

function toggleFavoriteFilter() {
  filters.favorite = !filters.favorite
  reload()
}

async function toggleFavorite(video: VideoAsset) {
  closeCardMenu()
  const next = !video.videoFavorite
  video.videoFavorite = next
  try {
    await setPersonalVideoFavorite(video.id, next)
    if (!next && filters.favorite) reload()
  } catch (e) {
    video.videoFavorite = !next
    notify.errorWithDetail('收藏操作失败', e)
  }
}

function scopeOf(video: VideoAsset): 'ALL' | 'SELECTED' | 'UNREVIEWED' {
  if ((video.machineTierIds || []).length > 0) return 'SELECTED'
  return video.machineScopeConfirmed ? 'ALL' : 'UNREVIEWED'
}

function scopeText(video: VideoAsset) {
  const scope = scopeOf(video)
  return scope === 'ALL' ? '产品通用' : scope === 'SELECTED' ? '指定机型' : '待确认范围'
}

function scopeLine(video: VideoAsset) {
  const labels = video.machineTierLabels || []
  if (labels.length) return `适用机型：${labels.join('、')}`
  return scopeOf(video) === 'ALL' ? '适用范围：产品通用（已确认）' : '适用范围：尚未确认（可能为产品通用）'
}

function metaLine(video: VideoAsset) {
  const features = splitTags(video.featureTags)
  const demos = splitTags(video.demonstrationTags)
  const parts: string[] = []
  if (features.length) parts.push(`特征：${features.join(' / ')}`)
  if (demos.length) parts.push(`展示：${demos.join(' / ')}`)
  return parts.join('　')
}

async function loadVideos() {
  loading.value = true
  try {
    const params: VideoAssetSearchParams = { ...pagination.getPageParams() }
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.productCategory) params.productCategory = filters.productCategory
    if (filters.modelId !== '') params.modelId = Number(filters.modelId)
    if (filters.machineTierId !== '') params.machineTierId = Number(filters.machineTierId)
    if (filters.category) params.category = filters.category
    if (filters.application) params.application = expandApplicationTerm(filters.application)
    if (filters.contentType) params.contentType = filters.contentType
    if (filters.featureTag) params.featureTag = filters.featureTag
    if (filters.demonstrationTag) params.demonstrationTag = filters.demonstrationTag
    if (filters.usageChannel) params.usageChannel = filters.usageChannel
    if (filters.contentStatus) params.contentStatus = filters.contentStatus
    if (filters.scope) params.scope = filters.scope as VideoAssetSearchParams['scope']
    if (filters.hasCover) params.hasCover = filters.hasCover === 'yes'
    if (filters.incomplete) params.incomplete = true
    if (filters.favorite) params.favorite = true
    if (filters.missingItem) params.missingItem = filters.missingItem
    const data = await fetchCatalogVideos(params)
    videos.value = data.content || []
    pagination.updateFromResponse(data)
    broken.clear()
  } catch (e) {
    notify.errorWithDetail('加载设备视频失败', e)
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    Object.assign(options, await fetchCatalogVideoFilterOptions())
  } catch (e) {
    console.warn(e)
  }
}

async function loadGovernanceSummary() {
  try {
    governanceSummary.value = await fetchVideoGovernanceSummary()
  } catch (e) {
    console.warn(e)
  }
}

function reload() {
  pagination.resetPage()
  loadVideos()
}

function resetFilters() {
  Object.assign(filters, {
    keyword: '',
    productCategory: '',
    modelId: '',
    machineTierId: '',
    category: '',
    application: '',
    contentType: '',
    featureTag: '',
    demonstrationTag: '',
    usageChannel: '',
    contentStatus: '',
    scope: '',
    hasCover: '',
    incomplete: false,
    favorite: false,
    missingItem: ''
  })
  tierOptions.value = []
  reload()
}

function onProductCategoryChange() {
  if (filters.modelId !== '') {
    const current = products.value.find(item => item.id === filters.modelId)
    if (!current || (filters.productCategory && current.category !== filters.productCategory)) {
      filters.modelId = ''
      tierOptions.value = []
      filters.machineTierId = ''
    }
  }
}

async function onProductChange() {
  filters.machineTierId = ''
  if (filters.modelId === '') {
    tierOptions.value = []
    return
  }
  try {
    tierOptions.value = await fetchMachineTiers(Number(filters.modelId))
  } catch (e) {
    console.warn(e)
    tierOptions.value = []
  }
}

function toggleIncomplete() {
  filters.incomplete = !filters.incomplete
  reload()
}

function setMissingItem(item: string) {
  filters.missingItem = filters.missingItem === item ? '' : item
  filters.incomplete = Boolean(filters.missingItem)
  reload()
}

function setHasCover(value: '' | 'yes' | 'no') {
  filters.hasCover = value
  reload()
}

function setScope(value: '' | 'ALL' | 'SELECTED' | 'UNREVIEWED') {
  filters.scope = value
  reload()
}

function splitTags(value?: string) {
  return String(value || '')
    .split(/[,，]/)
    .map(v => v.trim())
    .filter(Boolean)
}

function ownerType(_video: VideoAsset): 'product_model' {
  return 'product_model'
}

function thumbnailUrl(video: VideoAsset) {
  return fileAssetThumbnailUrlById(ownerType(video), video.id, 480)
}

function markBroken(id: number) {
  broken.add(id)
}

function preview(index: number) {
  const gallery = videos.value.map(item => ({
    id: item.id,
    assetId: item.id,
    ownerType: ownerType(item),
    modelId: item.modelId,
    category: item.category,
    fileName: item.fileName,
    title: `${displayTitle(item)} · ${item.modelName}`
  }))
  openAssetPreview({ ...gallery[index], gallery, galleryIndex: index })
  void recordVideoView(videos.value[index].id).catch(() => undefined)
}

async function openAddToCollection(video: VideoAsset) {
  closeCardMenu()
  collectionVideo.value = video
  newCollectionName.value = ''
  try {
    collections.value = await fetchVideoCollections()
    collectionVisible.value = true
  } catch (e) {
    notify.errorWithDetail('加载视频清单失败', e)
  }
}

async function addCurrentVideoToCollection(collectionId: number) {
  if (!collectionVideo.value) return
  try {
    await addVideoToCollection(collectionId, collectionVideo.value.id)
    collectionVisible.value = false
    notify('已加入客户视频清单', 'success')
  } catch (e) {
    notify.errorWithDetail('加入清单失败', e)
  }
}

async function createAndAddCollection() {
  if (!newCollectionName.value || !collectionVideo.value) return
  try {
    const collection = await createVideoCollection(newCollectionName.value)
    await addVideoToCollection(collection.id, collectionVideo.value.id)
    collectionVisible.value = false
    notify('已新建清单并加入视频', 'success')
  } catch (e) {
    notify.errorWithDetail('新建视频清单失败', e)
  }
}

function previewById(id: number) {
  const index = videos.value.findIndex(item => item.id === id)
  if (index >= 0) preview(index)
}

async function openFolder(video: VideoAsset) {
  try {
    const result = await openFileAssetFolderByOptions({ ownerType: ownerType(video), assetId: video.id })
    const feedback = fileAssetOpenFolderMessage(result)
    notify[feedback.type](feedback.message)
  } catch (err) {
    notify.errorWithDetail('打开文件夹失败', err)
  }
}

async function copyFileName(video: VideoAsset) {
  closeCardMenu()
  try {
    await navigator.clipboard.writeText(video.fileName)
    notify('文件名已复制', 'success')
  } catch {
    notify('复制失败，请手动复制', 'warning')
  }
}

function closeCardMenu() {
  cardMenuVideo.value = null
}

function toggleCardMenu(video: VideoAsset, event: MouseEvent) {
  if (cardMenuVideo.value?.id === video.id) {
    closeCardMenu()
    return
  }
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const menuWidth = 140
  const menuHeight = 200
  const gap = 6
  cardMenuStyle.value = {
    position: 'fixed',
    top: `${Math.min(window.innerHeight - menuHeight - gap, Math.max(gap, rect.bottom + gap))}px`,
    left: `${Math.min(window.innerWidth - menuWidth - gap, Math.max(gap, rect.right - menuWidth))}px`
  }
  cardMenuVideo.value = video
}

function handleGlobalClick(event: MouseEvent) {
  const target = event.target as Element | null
  if (!target) return
  if (cardMenuVideo.value && !target.closest('.card-menu-panel') && !target.closest('.row-actions')) {
    closeCardMenu()
  }
}

function openProduct(video: VideoAsset) {
  closeCardMenu()
  router.push({ path: '/products', query: { id: String(video.modelId), tab: 'assets' } })
}

function openEdit(video: VideoAsset) {
  closeCardMenu()
  editing.value = video
  editingHints.value = []
  Object.assign(editForm, {
    contentTags: video.contentTags || '',
    applicationTags: video.applicationTags || '',
    videoContentType: video.videoContentType || '',
    videoUsageChannels: video.videoUsageChannels || '',
    videoContentStatus: video.videoContentStatus || '',
    featureTags: video.featureTags || '',
    demonstrationTags: video.demonstrationTags || '',
    videoTitle: video.videoTitle || cleanVideoTitle(video.fileName)
  })
  editScopeConfirmed.value = Boolean(video.machineScopeConfirmed || video.machineTierIds?.length)
  editVisible.value = true
}

function regenerateTitle() {
  if (!editing.value) return
  editForm.videoTitle = cleanVideoTitle(editing.value.fileName)
}

function toggleApplication(tag: string) {
  const tags = splitTags(editForm.applicationTags)
  const index = tags.indexOf(tag)
  if (index >= 0) tags.splice(index, 1)
  else tags.push(tag)
  editForm.applicationTags = tags.join('，')
}

function toggleUsageChannel(tag: string) {
  const tags = splitTags(editForm.videoUsageChannels)
  const index = tags.indexOf(tag)
  if (index >= 0) tags.splice(index, 1)
  else tags.push(tag)
  editForm.videoUsageChannels = tags.join('，')
}

function inferEditingFileName() {
  if (!editing.value) return
  const inferred = inferVideoMetadataFromFileName(editing.value.fileName, editing.value.category)
  editForm.contentTags = mergeInferredTags(editForm.contentTags, inferred.contentTags)
  editForm.applicationTags = mergeInferredTags(editForm.applicationTags, inferred.applicationTags)
  editForm.featureTags = mergeInferredTags(editForm.featureTags, inferred.featureTags)
  editForm.demonstrationTags = mergeInferredTags(editForm.demonstrationTags, inferred.specificationHints)
  if (!editForm.videoContentType) editForm.videoContentType = inferred.videoContentType
  editingHints.value = inferred.specificationHints
  if (
    !inferred.applicationTags.length &&
    !inferred.contentTags.length &&
    !inferred.featureTags.length &&
    !inferred.specificationHints.length
  ) {
    notify('文件名中没有识别到可用信息', 'warning')
  }
}

async function saveEdit() {
  if (!editing.value) return
  try {
    await updateCatalogAssetMetadata(editing.value.id, {
      ...editForm,
      machineScopeConfirmed: editScopeConfirmed.value,
      machineTierAction: 'KEEP',
      machineTierIds: []
    })
    editVisible.value = false
    await Promise.all([loadVideos(), loadOptions(), loadGovernanceSummary()])
    notify('视频检索信息已保存', 'success')
  } catch (e) {
    notify.errorWithDetail('保存视频信息失败', e)
  }
}

async function collectAllVideos(): Promise<VideoAsset[]> {
  const all: VideoAsset[] = []
  let page = 0
  while (page < 50) {
    const data = await fetchCatalogVideos({ page, size: 200 })
    const content: VideoAsset[] = data.content || []
    all.push(...content)
    const total = Number(data.totalElements ?? all.length)
    if (content.length === 0 || all.length >= total) break
    page += 1
  }
  return all
}

function confidenceText(confidence: InferredVideoConfidence) {
  switch (confidence) {
    case 'high':
      return '高置信度'
    case 'medium':
      return '需确认'
    case 'conflict':
      return '疑似异常'
    default:
      return '未识别'
  }
}

async function openInferenceReview() {
  inferenceApplying.value = true
  notify('正在分析全库视频文件名…', 'info')
  try {
    const all = await collectAllVideos()
    inferenceRows.value = all.map(video => {
      const inferred = inferVideoMetadataFromFileName(video.fileName, video.category)
      const hasResult =
        inferred.applicationTags.length > 0 ||
        Boolean(inferred.videoContentType) ||
        inferred.featureTags.length > 0 ||
        inferred.specificationHints.length > 0 ||
        inferred.conflictedApplications.length > 0
      return {
        video,
        inferred,
        hasResult,
        selected: hasResult && inferred.confidence === 'high'
      }
    })
    inferenceVisible.value = true
  } catch (e) {
    notify.errorWithDetail('全库识别失败', e)
  } finally {
    inferenceApplying.value = false
  }
}

async function applyInferenceReview() {
  const rows = inferenceRows.value.filter(
    item => item.selected && item.inferred.confidence !== 'conflict'
  )
  if (!rows.length) return
  inferenceApplying.value = true
  try {
    for (let i = 0; i < rows.length; i += 5) {
      const chunk = rows.slice(i, i + 5)
      await Promise.all(
        chunk.map(({ video, inferred }) =>
          updateCatalogAssetMetadata(video.id, {
            contentTags: mergeInferredTags(video.contentTags, inferred.contentTags),
            applicationTags: mergeInferredTags(video.applicationTags, inferred.applicationTags),
            videoContentType: video.videoContentType || inferred.videoContentType,
            featureTags: mergeInferredTags(video.featureTags, inferred.featureTags),
            demonstrationTags: mergeInferredTags(video.demonstrationTags, inferred.specificationHints),
            machineTierAction: 'KEEP',
            machineTierIds: []
          })
        )
      )
    }
    inferenceVisible.value = false
    await Promise.all([loadVideos(), loadOptions(), loadGovernanceSummary()])
    notify(`已完成 ${rows.length} 个视频的检索信息补全`, 'success')
  } catch (e) {
    notify.errorWithDetail('智能补全失败', e)
  } finally {
    inferenceApplying.value = false
  }
}

onMounted(async () => {
  document.addEventListener('click', handleGlobalClick)
  window.addEventListener('resize', closeCardMenu)
  window.addEventListener('scroll', closeCardMenu, true)
  try {
    products.value = await fetchCatalogModelsSimple()
  } catch (e) {
    console.warn(e)
  }
  await Promise.all([loadVideos(), loadOptions(), loadGovernanceSummary()])
})

onUnmounted(() => {
  document.removeEventListener('click', handleGlobalClick)
  window.removeEventListener('resize', closeCardMenu)
  window.removeEventListener('scroll', closeCardMenu, true)
})
</script>

<style scoped>
.quick-edit .infer-button {
  margin-bottom: 4px;
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}
.video-library {
  height: 100%;
  overflow: auto;
  padding: 24px;
  background: var(--color-page-bg);
}
.video-library__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 20px;
}
.eyebrow {
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
}
.video-library h1 {
  margin: 4px 0;
  font-size: 26px;
}
.video-library__header p {
  margin: 0;
  color: var(--color-text-muted);
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}
.header-actions > button {
  padding: 8px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  cursor: pointer;
}
.header-actions > button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.mode-switch {
  display: inline-flex;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.mode-switch button {
  padding: 8px 14px;
  border: 0;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 13px;
  cursor: pointer;
}
.mode-switch button + button {
  border-left: 1px solid var(--color-border);
}
.mode-switch button.active {
  background: var(--color-primary);
  color: #fff;
}
.workbench {
  display: grid;
  gap: 12px;
  margin-top: 18px;
}
.batch-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}
.batch-select-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.batch-count {
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}
.batch-divider {
  width: 1px;
  height: 20px;
  margin: 0 4px;
  background: var(--color-border);
}
.batch-bar select,
.batch-bar input {
  min-height: 32px;
  max-width: 200px;
  padding: 0 9px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: 13px;
}
.batch-bar button {
  padding: 6px 11px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.batch-bar button:hover:not(:disabled) {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.batch-bar button:disabled,
.batch-bar select:disabled,
.batch-bar input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.batch-bar .confirm-scope {
  border-color: var(--color-success-text, #047857);
  color: var(--color-success-text, #047857);
}
.table-wrap {
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  max-height: calc(100vh - 320px);
}
.workbench-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.workbench-table th,
.workbench-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--color-border-light, var(--color-border));
  text-align: left;
  vertical-align: top;
  white-space: nowrap;
}
.workbench-table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 600;
}
.workbench-table tbody tr:hover {
  background: var(--color-surface-alt);
}
.workbench-table tbody tr.selected {
  background: var(--color-primary-bg-light, rgba(37, 99, 235, 0.07));
}
.col-check {
  width: 34px;
}
.col-title {
  min-width: 240px;
  max-width: 320px;
  white-space: normal !important;
}
.title-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.title-text {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conflict-dot {
  display: inline-grid;
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-danger, #dc2626);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  cursor: help;
}
.file-name {
  display: block;
  overflow: hidden;
  max-width: 300px;
  color: var(--color-text-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.scope-badge,
.complete-badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 12px;
}
.scope-badge.scope-ALL {
  background: var(--color-success-bg, rgba(16, 185, 129, 0.14));
  color: var(--color-success-text, #047857);
}
.scope-badge.scope-SELECTED {
  background: var(--color-primary-bg-light, rgba(37, 99, 235, 0.12));
  color: var(--color-primary);
}
.scope-badge.scope-UNREVIEWED {
  background: rgba(217, 119, 6, 0.14);
  color: #b45309;
}
.complete-badge.ok {
  background: var(--color-success-bg, rgba(16, 185, 129, 0.14));
  color: var(--color-success-text, #047857);
}
.complete-badge.missing {
  background: rgba(217, 119, 6, 0.14);
  color: #b45309;
}
.col-actions {
  white-space: nowrap;
}
.col-duration {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.resolution-text {
  display: block;
  color: var(--color-text-faint);
  font-size: 11px;
}
.col-actions button {
  margin-right: 6px;
  padding: 4px 9px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: 12px;
  cursor: pointer;
}
.col-actions button:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.dup-review {
  display: grid;
  gap: 10px;
}
.dup-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}
.dup-toolbar button {
  flex-shrink: 0;
  padding: 7px 12px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
}
.dup-toolbar button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.dup-empty {
  margin: 0;
  padding: 24px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-muted);
  text-align: center;
}
.dup-review h4 {
  margin: 6px 0 0;
  font-size: 14px;
}
.dup-group {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-left-width: 3px;
  border-radius: var(--radius-lg);
}
.dup-group.danger {
  border-left-color: var(--color-danger, #dc2626);
}
.dup-group.warn {
  border-left-color: #d97706;
}
.dup-group-title {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 600;
}
.dup-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 8px;
  border: 1px solid var(--color-border-light, var(--color-border));
  border-radius: var(--radius-md);
}
.dup-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.dup-main b {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dup-main small {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-count {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.result-count strong {
  font-size: 28px;
}
.video-filters {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
}
.filter-row {
  display: grid;
  grid-template-columns: minmax(240px, 2fr) repeat(4, minmax(130px, 1fr)) auto auto;
  gap: 10px;
}
.filter-row + .filter-row {
  grid-template-columns: repeat(7, minmax(130px, 1fr)) auto auto;
}
.filter-row input,
.filter-row select {
  min-height: 40px;
  padding: 0 11px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
}
.filter-row select:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.video-filters button {
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  cursor: pointer;
  white-space: nowrap;
}
.primary {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}
.quick-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  padding: 5px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
}
.chip.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg-light, rgba(37, 99, 235, 0.1));
  color: var(--color-primary);
}
.chip.chip-favorite.active {
  border-color: #f59e0b;
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
}
.active-hint {
  color: var(--color-text-muted);
  font-size: 12px;
}
.video-state {
  padding: 60px 20px;
  text-align: center;
  color: var(--color-text-muted);
}
.video-state strong {
  display: block;
  margin-bottom: 6px;
  font-size: 16px;
  color: var(--color-text);
}
.video-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  margin-top: 18px;
}
.video-card {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
}
.video-cover {
  position: relative;
  width: 100%;
  height: 240px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: var(--color-surface-alt);
  cursor: pointer;
}
.video-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.video-placeholder {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--color-text-muted);
}
.video-placeholder b {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  background: var(--color-primary);
  color: #fff;
}
.badge {
  position: absolute;
  top: 10px;
  padding: 3px 8px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  color: #fff;
  background: rgba(15, 23, 42, 0.62);
}
.badge-category {
  left: 10px;
}
.badge-scope {
  right: 10px;
}
.badge-scope.scope-ALL {
  background: var(--color-success-bg, rgba(16, 185, 129, 0.9));
  color: var(--color-success-text, #065f46);
}
.badge-scope.scope-SELECTED {
  background: var(--color-primary);
  color: #fff;
}
.badge-scope.scope-UNREVIEWED {
  background: rgba(217, 119, 6, 0.92);
  color: #fff;
}
.badge-duration {
  top: auto;
  right: 10px;
  bottom: 10px;
  font-variant-numeric: tabular-nums;
}
.favorite-star {
  flex-shrink: 0;
  margin-right: 4px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-border);
  font-size: inherit;
  line-height: 1;
  cursor: pointer;
}
.favorite-star:hover {
  color: #d97706;
}
.favorite-star.on {
  color: #f59e0b;
}
.video-card__body {
  display: grid;
  gap: 6px;
  padding: 12px 14px 14px;
}
.video-title {
  margin: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-strong, var(--color-text));
  white-space: nowrap;
  text-overflow: ellipsis;
}
.video-product {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 13px;
}
.model-code {
  padding: 1px 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--color-text-muted);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chips span {
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg-light, rgba(37, 99, 235, 0.08));
  color: var(--color-primary);
  font-size: 12px;
}
.chips em {
  color: var(--color-text-faint);
  font-size: 12px;
  font-style: normal;
}
.meta-line {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}
.scope-line {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}
.scope-line.warn {
  color: #b45309;
}
.card-footer {
  display: grid;
  gap: 8px;
  padding-top: 10px;
  margin-top: 2px;
  border-top: 1px dashed var(--color-border-light, var(--color-border));
}
.footer-info {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.status-badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.status-badge.type-demo {
  background: rgba(37, 99, 235, 0.1);
  color: #2563eb;
}
.status-badge.type-howto {
  background: rgba(16, 185, 129, 0.1);
  color: #059669;
}
.status-badge.type-maint {
  background: rgba(217, 119, 6, 0.1);
  color: #b45309;
}
.status-badge.type-promo {
  background: rgba(139, 92, 246, 0.1);
  color: #7c3aed;
}
.status-badge.type-other {
  background: rgba(107, 114, 128, 0.1);
  color: #6b7280;
}
.status-dot {
  color: var(--color-text-faint);
  font-size: 11px;
}
.status-state {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt, rgba(0, 0, 0, 0.04));
  color: var(--color-text-secondary);
  font-size: 11px;
}
.status-state.state-待处理 {
  background: rgba(107, 114, 128, 0.12);
  color: #6b7280;
}
.status-state.state-待确认 {
  background: rgba(217, 119, 6, 0.12);
  color: #b45309;
}
.status-state.state-可用 {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
}
.status-state.state-精选 {
  background: rgba(245, 158, 11, 0.15);
  color: #d97706;
  font-weight: 600;
}
.status-state.state-停用 {
  background: rgba(107, 114, 128, 0.08);
  color: #9ca3af;
}
.status-state.state-异常 {
  background: rgba(239, 68, 68, 0.12);
  color: #dc2626;
}
.status-state.state-未设置 {
  background: rgba(107, 114, 128, 0.08);
  color: #9ca3af;
}
.completeness-badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: rgba(217, 119, 6, 0.12);
  color: #b45309;
  font-size: 11px;
  font-weight: 500;
  cursor: help;
}
.completeness-badge.complete {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
}
.row-actions {
  display: flex;
  gap: 6px;
}
.row-actions .action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.row-actions .action-btn svg {
  flex-shrink: 0;
}
.row-actions .action-btn:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.row-actions .action-btn.primary {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}
.row-actions .action-btn.primary:hover {
  background: var(--color-primary-hover, #2563eb);
  border-color: var(--color-primary-hover, #2563eb);
  color: #fff;
}
.card-menu-panel {
  z-index: var(--z-popover);
  display: grid;
  min-width: 128px;
  padding: var(--space-sm, 8px);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
}
.card-menu-panel button {
  justify-content: flex-start;
  min-height: 32px;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.card-menu-panel button:hover {
  background: var(--color-surface-alt);
  color: var(--color-primary);
}
.pagination {
  margin-top: 18px;
}
.quick-edit {
  display: grid;
  gap: 10px;
}
.edit-context {
  margin: 0;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.quick-edit label,
.scope-confirm {
  display: grid;
  gap: 4px;
}
.quick-edit label > span:first-child {
  color: var(--color-text-secondary);
  font-size: 12px;
}
.inline-field {
  display: flex;
  gap: 8px;
}
.inline-field input {
  flex: 1;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.quick-edit input:not([type='radio']) {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.quick-edit select {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.suggestions {
  gap: 6px;
}
.radio-row {
  display: inline-flex !important;
  align-items: center;
  gap: 6px;
  grid-auto-flow: column;
}
.scope-confirm {
  padding-top: 6px;
  border-top: 1px dashed var(--color-border);
}
.scope-confirm > span:first-child {
  color: var(--color-text-secondary);
  font-size: 12px;
}
.edit-hint {
  margin: 0;
  color: var(--color-text-faint);
  font-size: 12px;
}
.spec-hints {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}
.inference-review {
  display: grid;
  max-height: 60vh;
  gap: 8px;
  overflow: auto;
}
.inference-summary {
  position: sticky;
  top: 0;
  margin: 0;
  padding: 6px 8px;
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  font-size: 12px;
}
.inference-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--color-border-light, var(--color-border));
  border-radius: var(--radius-md);
}
.inference-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.inference-main b {
  font-size: 13px;
}
.inference-main small {
  color: var(--color-text-muted);
  word-break: break-all;
}
.hint-text {
  color: var(--color-primary) !important;
}
.conflict-text {
  color: var(--color-danger, #dc2626) !important;
}
.inference-row.row-conflict {
  border-color: var(--color-danger, #dc2626);
  background: var(--color-danger-bg-soft, rgba(220, 38, 38, 0.04));
}
.confidence-badge {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  white-space: nowrap;
}
.confidence-high {
  background: var(--color-success-bg, rgba(16, 185, 129, 0.14));
  color: var(--color-success-text, #047857);
}
.confidence-medium {
  background: rgba(217, 119, 6, 0.14);
  color: #b45309;
}
.confidence-conflict {
  background: var(--color-danger-bg-soft, rgba(220, 38, 38, 0.1));
  color: var(--color-danger, #dc2626);
}
.confidence-none {
  background: var(--color-surface-alt);
  color: var(--color-text-faint);
}
.collection-picker { display: grid; gap: var(--space-2); }
.collection-option {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
}
.collection-option:hover { border-color: var(--color-primary); }
.collection-option span { color: var(--color-text-secondary); font-size: 12px; }
.collection-create { display: grid; grid-template-columns: 1fr auto; gap: var(--space-2); margin-top: var(--space-2); }
@media (max-width: 1280px) {
  .filter-row,
  .filter-row + .filter-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filter-keyword {
    grid-column: 1 / -1;
  }
}
</style>
