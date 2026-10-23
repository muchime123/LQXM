<template>
  <div class="master-detail-layout">
    <!-- 主从行：左栏 + 详情 -->
    <div class="master-detail-row">
      <!-- 左侧：模具卡片面板 -->
      <aside class="master-panel">
        <DataState
          :state="listState"
          :skeleton="listState === 'loading'"
          skeleton-variant="card"
          :skeleton-rows="mode === 'global' ? 8 : 5"
          :refreshing="loading && molds.length > 0"
          :empty-text="mode === 'global' ? '暂无模具数据' : '该系列还没有模具'"
          :empty-hint="
            mode === 'global'
              ? '点击「导入 Excel」或「新建模具」建立模具台账。'
              : '点击「新建模具」或「导入 Excel」添加模具。'
          "
          filtered-empty-text="没有符合条件的模具"
          filtered-empty-hint="调整筛选条件或清除筛选。"
          error-title="模具数据加载失败"
          :error-message="errorMsg"
          stale-message="刷新失败，显示的是旧数据。"
          @retry="loadMolds"
        >
          <template #emptyActions="{ state }">
            <el-button v-if="state === 'filtered-empty'" @click="resetLocalSearch">清除搜索</el-button>
            <el-button v-else type="primary" @click="openCreate">新建模具</el-button>
          </template>

          <div v-if="filteredMolds.length === 0 && molds.length > 0" class="filter-empty">
            <p>没有匹配「{{ moldKeyword }}」的模具</p>
            <el-button link type="primary" size="small" @click="moldKeyword = ''">清除搜索</el-button>
          </div>

          <!-- 规格族仅组织左侧模具记录；选择项始终是具体模具。 -->
          <div v-else class="mold-grouped-list">
            <section v-for="group in groupedMolds" :key="group.groupName" class="mold-group">
              <header class="mold-group-header">
                <span class="mold-group-title">{{ group.groupName }}</span>
                <span class="mold-group-count">
                  {{ group.linkedVariantCount }} 规格 · {{ group.items.length }} 模具
                </span>
              </header>
              <div class="mold-card-grid">
                <article
                  v-for="mold in group.items"
                  :key="mold.id"
                  class="mold-card"
                  :class="{
                    'is-selected': selectedMoldId === mold.id || (batchMode && isSelected(mold.id)),
                    'is-unmatched': !mold.variantCount
                  }"
                  @click="onCardClick(mold)"
                >
                  <header class="mold-card-header">
                    <div class="mold-card-title">
                      <strong>{{ mold.moldCode }}</strong>
                      <span
                        v-if="mold.baseMoldCode && mold.baseMoldCode !== mold.moldCode"
                        class="mold-base-badge"
                        :title="`基模：${mold.baseMoldCode}`"
                        >{{ mold.baseMoldCode }}</span
                      >
                    </div>
                    <div class="header-right">
                      <el-tag
                        :type="!moldPublishEligibility(mold).eligible ? 'danger' : mold.websiteEnabled ? 'success' : 'warning'"
                        size="small"
                        effect="light"
                        class="website-publish-tag"
                        role="switch"
                        tabindex="0"
                        :aria-checked="mold.websiteEnabled === true"
                        :title="moldPublishEligibility(mold).reason"
                        @click.stop="toggleMoldWebsite(mold)"
                        @keydown.enter.stop.prevent="toggleMoldWebsite(mold)"
                        @keydown.space.stop.prevent="toggleMoldWebsite(mold)"
                      >{{ !moldPublishEligibility(mold).eligible ? '不可发布' : mold.websiteEnabled ? '已发布' : '待发布' }}</el-tag>
                      <el-tag
                        v-if="!mold.variantCount"
                        type="warning"
                        size="small"
                        effect="light"
                        class="unmatched-tag"
                        >{{ mode === 'global' ? '待匹配' : '待匹配规格' }}</el-tag
                      >
                      <StatusTag
                        v-if="mold.status"
                        :type="statusTagType(mold.status)"
                        :label="statusLabel(mold.status)"
                        size="sm"
                      />
                    </div>
                  </header>

                  <div v-if="showSeriesName" class="mold-card-product" :title="mold.modelName">
                    {{ mold.modelName || '—' }}
                  </div>

                  <div v-if="mold.representativeCaliber || mold.representativeHeight" class="mold-card-dimensions">
                    <div v-if="mold.representativeCaliber" class="dim-item">
                      <span class="dim-label">口径</span>
                      <span class="dim-value">{{ mold.representativeCaliber }}</span>
                    </div>
                    <div v-if="mold.representativeHeight" class="dim-item">
                      <span class="dim-label">高度</span>
                      <span class="dim-value">{{ mold.representativeHeight }}</span>
                    </div>
                  </div>

                  <div class="mold-card-stats">
                    <div class="stat">
                      <span class="stat-label">一出</span>
                      <strong class="stat-value">{{ mold.cavityCount ?? '—' }}</strong>
                    </div>
                    <div class="stat">
                      <span class="stat-label">规格</span>
                      <strong
                        class="stat-value"
                        :class="{ 'has-variants': mold.variantCount > 0, 'is-zero': !mold.variantCount }"
                        >{{ mold.variantCount || 0 }}</strong
                      >
                    </div>
                    <div class="stat">
                      <span class="stat-label">配件</span>
                      <strong class="stat-value">{{ mold.accessoryCount || 0 }}</strong>
                    </div>
                  </div>

                  <footer v-if="mold.moldTypeCode" class="mold-card-footer">
                    <span class="type-chip">{{ mold.moldTypeCode }}</span>
                  </footer>
                </article>
              </div>
            </section>
          </div>
        </DataState>

        <div v-if="enablePagination" class="group-pagination">
          <div class="group-pagination-summary">
            <span>共 {{ pageInfo.total }} 个规格族</span>
            <el-select
              :model-value="pageInfo.size"
              class="group-page-size"
              size="small"
              :disabled="loading"
              aria-label="每页规格族数量"
              @change="changeGroupPageSize"
            >
              <el-option v-for="size in [10, 20, 50]" :key="size" :label="`${size} 组/页`" :value="size" />
            </el-select>
          </div>
          <LqPagination
            class="molds-group-pagination"
            mode="compact"
            :pagination="pagination"
            :disabled="loading"
            :pager-count="5"
            @change="() => loadMolds()"
          />
        </div>
      </aside>

      <!-- 右侧：详情面板 -->
      <section class="detail-panel">
        <template v-if="selectedMold">
          <header class="detail-header">
            <div class="detail-title">
              <h3>{{ selectedMold.moldCode }}</h3>
              <StatusTag
                v-if="selectedMold.status"
                :type="statusTagType(selectedMold.status)"
                :label="statusLabel(selectedMold.status)"
                size="sm"
              />
              <span class="detail-meta">
                {{ selectedMold.cavityCount ?? '?' }} 出 ·
                {{ detailLoading ? '…' : detailData?.variantLinks.length || 0 }} 个规格
              </span>
              <el-tag v-if="showSeriesName && selectedMold.modelName" size="small" effect="plain" class="series-tag">
                {{ selectedMold.modelName }}
              </el-tag>
            </div>
            <div class="detail-actions">
              <el-button size="small" @click="openEdit(selectedMold)">编辑</el-button>
              <template v-if="mode === 'global'">
                <el-popconfirm title="确定移入回收站？" @confirm="doDelete(selectedMold)">
                  <template #reference>
                    <el-button size="small" type="danger" plain>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
              <el-button v-else size="small" type="danger" @click="onDeleteMold">删除</el-button>
            </div>
          </header>

          <div class="detail-body">
            <LqSkeleton v-if="detailLoading" :rows="mode === 'global' ? 10 : 8" class="detail-skeleton" />

            <template v-else-if="detailData">
              <LqTabs v-model="activeTab" class="detail-tabs">
                <!-- Tab: 关联规格 -->
                <el-tab-pane label="关联规格" name="variants">
                  <div class="tab-header-actions">
                    <span v-if="mode === 'global'" class="tab-header-title"
                      >共 {{ linkedSpecCards.length }} 个关联规格</span
                    >
                    <el-button size="small" type="primary" plain @click="openAddVariant">
                      <el-icon><Plus /></el-icon>添加规格
                    </el-button>
                  </div>
                  <div v-if="linkedSpecCards.length > 0" class="variant-standard-card-list">
                    <article
                      v-for="spec in linkedSpecCards"
                      :key="mode === 'global' ? spec.link.id : spec.variant.id"
                      class="variant-standard-card"
                      @click="onSpecCardClick(spec)"
                    >
                      <div class="card-delete-btn" @click.stop="removeVariantLink(spec.link.id)">
                        <svg
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="2"
                          width="14"
                          height="14"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                        >
                          <path d="M3 6h18" />
                          <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
                          <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                        </svg>
                      </div>
                      <div class="card-image-wrap" :class="{ 'no-image': !spec.thumbnailUrl }">
                        <template v-if="spec.thumbnailUrl">
                          <img
                            :src="spec.thumbnailUrl"
                            :alt="spec.variant.variantName || '规格图片'"
                            @click.stop="previewImage(spec)"
                          />
                        </template>
                        <div v-else class="image-placeholder">
                          <svg viewBox="0 0 48 48" fill="none" width="32" height="32">
                            <rect
                              x="8"
                              y="12"
                              width="32"
                              height="26"
                              rx="4"
                              stroke="currentColor"
                              stroke-width="2"
                              fill="none"
                            />
                            <circle cx="20" cy="24" r="4" stroke="currentColor" stroke-width="1.5" fill="none" />
                            <path
                              d="M36 38l-6-8-5 4-3-4-6 8"
                              stroke="currentColor"
                              stroke-width="1.5"
                              fill="none"
                              stroke-linejoin="round"
                            />
                          </svg>
                          <span>{{ spec.variant.capacityMl ? `${spec.variant.capacityMl}ml` : '规格图' }}</span>
                        </div>
                        <span v-if="spec.variant.capacityMl" class="capacity-label">
                          <strong>{{ spec.variant.capacityMl }}</strong>
                          <em>ml</em>
                        </span>
                        <div v-if="spec.thumbnailUrl" class="image-preview-btn" @click.stop="previewImage(spec)">
                          <svg
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                            width="14"
                            height="14"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                            <circle cx="12" cy="12" r="3" />
                          </svg>
                        </div>
                      </div>

                      <header class="card-spec-title">
                        <strong :title="spec.variant.specLabel || spec.variant.variantName || '未命名规格'">
                          {{ spec.variant.specLabel || spec.variant.variantName || '未命名规格' }}
                        </strong>
                        <span class="card-shape-pill">{{ spec.variant.shapeType || '—' }}</span>
                      </header>

                      <div class="card-info-body">
                        <div class="card-info-metrics">
                          <div class="metric">
                            <dt>容量</dt>
                            <dd>{{ spec.variant.capacityMl || '—' }}</dd>
                          </div>
                          <div class="metric">
                            <dt>克重</dt>
                            <dd>{{ spec.variant.weightG || '—' }}</dd>
                          </div>
                          <div class="metric">
                            <dt>装箱</dt>
                            <dd>{{ spec.variant.variantMoq || '—' }}</dd>
                          </div>
                        </div>
                        <div v-if="spec.remark" class="card-info-footer">
                          <span class="card-remark">{{ spec.remark }}</span>
                        </div>
                      </div>
                    </article>
                  </div>
                  <div v-else class="empty-tab">
                    <p>该模具暂未关联规格</p>
                    <el-button size="small" type="primary" @click="openAddVariant">添加规格</el-button>
                  </div>
                </el-tab-pane>

                <!-- Tab: 模具信息 -->
                <el-tab-pane label="模具信息" name="info">
                  <div class="info-section">
                    <!-- 归属与编号 -->
                    <div class="info-block">
                      <div class="info-block-header">
                        <span class="info-block-title">归属与编号</span>
                        <span class="info-block-hint">模号、归属产品与规格族。</span>
                      </div>
                      <div class="info-meta-grid">
                        <div class="info-meta-item">
                          <span class="meta-label">模号</span>
                          <span class="meta-value mono">{{ detailData.mold.moldCode || '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">基础号</span>
                          <span class="meta-value mono">{{ detailData.mold.baseMoldCode || '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">状态</span>
                          <el-tag
                            v-if="detailData.mold.status"
                            size="small"
                            :type="statusTagType(detailData.mold.status) as TagProps['type']"
                            effect="light"
                            >{{ statusLabel(detailData.mold.status) }}</el-tag
                          >
                          <span v-else class="meta-value">—</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">官网发布</span>
                          <el-tag
                            :type="
                              !moldPublishEligibility(detailData.mold).eligible
                                ? 'danger'
                                : detailData.mold.websiteEnabled
                                  ? 'success'
                                  : 'warning'
                            "
                            size="small"
                            effect="light"
                            :title="moldPublishEligibility(detailData.mold).reason"
                          >{{
                            !moldPublishEligibility(detailData.mold).eligible
                              ? '不可发布'
                              : detailData.mold.websiteEnabled
                                ? '已发布'
                                : '待发布'
                          }}</el-tag>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">产品系列</span>
                          <span class="meta-value">{{ detailData.mold.modelName || '—' }}</span>
                        </div>
                        <div v-if="mode === 'global'" class="info-meta-item">
                          <span class="meta-label">规格族</span>
                          <span class="meta-value">{{ detailData.mold.groupName || '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">ID</span>
                          <span class="meta-value mono">#{{ detailData.mold.id }}</span>
                        </div>
                      </div>
                    </div>

                    <!-- 模具特性 -->
                    <div class="info-block">
                      <div class="info-block-header">
                        <span class="info-block-title">模具特性</span>
                        <span class="info-block-hint">业务属性与物理特征。</span>
                      </div>
                      <div class="info-meta-grid">
                        <div v-if="mode === 'global'" class="info-meta-item">
                          <span class="meta-label">模具角色</span>
                          <span class="meta-value">{{ roleLabel(detailData.mold.moldRole) }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">业务分类</span>
                          <span class="meta-value">{{ detailData.mold.moldCategory || '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">盒型</span>
                          <span class="meta-value">{{ detailData.mold.moldTypeCode || '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">一出几</span>
                          <span class="meta-value">{{ detailData.mold.cavityCount ?? '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">格数</span>
                          <span class="meta-value">{{ detailData.mold.compartmentCount || '—' }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">表面结构</span>
                          <span class="meta-value">{{ detailData.mold.surfaceStructure || '—' }}</span>
                        </div>
                      </div>
                    </div>

                    <!-- 规格推断（口径/高度来自关联规格或原始字段） -->
                    <div class="info-block">
                      <div class="info-block-header">
                        <span class="info-block-title">规格推断</span>
                        <span class="info-block-hint">口径/高度优先显示模具原始录入值，缺失时从关联规格推导。</span>
                      </div>
                      <div class="info-meta-grid">
                        <div class="info-meta-item">
                          <span class="meta-label">口径</span>
                          <span
                            class="meta-value"
                            :class="{ inferred: !detailData.mold.rawCaliber && moldCaliberText }"
                          >
                            {{ detailData.mold.rawCaliber || moldCaliberText || '—' }}
                            <el-tag
                              v-if="detailData.mold.rawCaliber"
                              size="small"
                              type="success"
                              effect="plain"
                              class="meta-hint-tag"
                              >已录入</el-tag
                            >
                            <span v-else-if="moldCaliberText" class="meta-hint-text">（推断）</span>
                          </span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">高度</span>
                          <span class="meta-value" :class="{ inferred: !detailData.mold.rawHeight && moldHeightText }">
                            {{ detailData.mold.rawHeight || moldHeightText || '—' }}
                            <el-tag
                              v-if="detailData.mold.rawHeight"
                              size="small"
                              type="success"
                              effect="plain"
                              class="meta-hint-tag"
                              >已录入</el-tag
                            >
                            <span v-else-if="moldHeightText" class="meta-hint-text">（推断）</span>
                          </span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">规格数</span>
                          <span class="meta-value">{{ detailData.variantLinks.length }}</span>
                        </div>
                        <div class="info-meta-item">
                          <span class="meta-label">配件数</span>
                          <span class="meta-value">{{ detailData.accessories.length }}</span>
                        </div>
                      </div>
                    </div>

                    <!-- 刻字 / 配件原文 / 备注 -->
                    <div class="info-block info-block--stacked">
                      <div class="info-block-header">
                        <span class="info-block-title">刻字与备注</span>
                        <span class="info-block-hint">刻字内容、版本分类、配件原文、自由备注。</span>
                      </div>

                      <div class="info-stack-row">
                        <span class="info-stack-label">刻字分类</span>
                        <el-tag v-if="detailData.mold.engravingType" size="small" effect="light">{{
                          engravingLabel(detailData.mold.engravingType)
                        }}</el-tag>
                        <span v-else class="meta-value">—</span>
                      </div>

                      <div class="info-stack-row">
                        <span class="info-stack-label">杯底刻字</span>
                        <div class="info-stack-value">
                          <span v-if="detailData.mold.bottomEngraving" class="engraving-text">
                            {{ detailData.mold.bottomEngraving }}
                          </span>
                          <span v-else class="engraving-text empty">空白（无刻字）</span>
                        </div>
                      </div>

                      <div v-if="detailData.mold.rawAccessoriesText" class="info-stack-row">
                        <span class="info-stack-label">配件原文</span>
                        <span class="meta-value">{{ detailData.mold.rawAccessoriesText }}</span>
                      </div>

                      <div v-if="detailData.mold.remark" class="info-stack-row">
                        <span class="info-stack-label">备注</span>
                        <span class="meta-value">{{ detailData.mold.remark }}</span>
                      </div>
                    </div>

                    <!-- 系统元信息（右下角小字） -->
                    <div class="info-meta-footer">
                      <span v-if="detailData.mold.createdAt">
                        创建：{{ formatDateTime(detailData.mold.createdAt) }}
                      </span>
                      <span v-if="detailData.mold.updatedAt && detailData.mold.updatedAt !== detailData.mold.createdAt">
                        更新：{{ formatDateTime(detailData.mold.updatedAt) }}
                      </span>
                      <span v-if="detailData.mold.sourceFile">
                        来源：{{ detailData.mold.sourceFile }}
                        <template v-if="detailData.mold.sourceSheet">／{{ detailData.mold.sourceSheet }}</template>
                        <template v-if="detailData.mold.sourceRowNumber"
                          >·第 {{ detailData.mold.sourceRowNumber }} 行</template
                        >
                      </span>
                    </div>
                  </div>
                </el-tab-pane>

                <!-- Tab: 配件 -->
                <el-tab-pane label="配件" name="accessories">
                  <MoldAccessoriesPanel
                    v-if="detailData && selectedMold"
                    :mold-id="selectedMold.id"
                    :accessories="detailData.accessories"
                    :raw-text="detailData.mold.rawAccessoriesText"
                    @changed="onAccessoriesChanged"
                  />
                  <div v-else class="empty-tab">
                    <p>暂无配件记录</p>
                  </div>
                </el-tab-pane>

                <!-- Tab: 数据质量 -->
                <el-tab-pane v-if="detailData" label="数据质量" name="quality">
                  <MoldQualityPanel :detail="detailData" />
                </el-tab-pane>
              </LqTabs>
            </template>

            <div v-else-if="detailError" class="empty-tab error">
              <p>加载失败：{{ detailError }}</p>
              <el-button size="small" type="primary" @click="loadDetail(selectedMold.id)">重试</el-button>
            </div>
          </div>
        </template>

        <!-- 未选择模具 -->
        <div v-else class="no-selection">
          <div class="no-selection-icon">
            <svg viewBox="0 0 80 80" fill="none" width="36" height="36">
              <rect x="10" y="20" width="24" height="40" rx="3" stroke="currentColor" stroke-width="2" fill="none" />
              <rect
                x="46"
                y="20"
                width="24"
                height="40"
                rx="3"
                stroke="currentColor"
                stroke-width="2"
                fill="none"
                stroke-dasharray="4 2"
              />
              <path d="M34 40h12" stroke="currentColor" stroke-width="1.5" />
              <path d="M40 36v8" stroke="currentColor" stroke-width="1.5" />
            </svg>
          </div>
          <p>从左侧选择一个模具</p>
          <span class="empty-hint">
            {{ mode === 'global' ? '即可查看关联规格、模具信息、配件与数据质量' : '即可查看模具详情与关联规格' }}
          </span>
        </div>
      </section>
    </div>

    <!-- 新建/编辑表单 -->
    <MoldFormDialog
      v-if="formVisible"
      v-model:visible="formVisible"
      :mold-id="editingId"
      :model-id="usePresetModelId ? modelId : undefined"
      @saved="onSaved"
    />

    <!-- Excel 批量导入 -->
    <MoldImportDialog
      v-if="importVisible"
      v-model:visible="importVisible"
      :default-model-id="usePresetModelId ? modelId : undefined"
      @confirmed="onImportConfirmed"
    />

    <!-- 规格图片预览 -->
    <LqPreview v-model="previewVisible" :url="previewUrl" :title="previewTitle" :file-name="previewFileName" />

    <!-- 添加规格对话框（全系列场景：先选系列再选规格，使用 LqDialog） -->
    <LqDialog
      v-if="mode === 'global'"
      v-model:model-value="addVariantVisible"
      title="添加关联规格"
      subtitle="将该模具关联到包材规格（用于出成规格选择和报价）"
      size="md"
      mode="form"
    >
      <div class="add-variant-form">
        <el-form label-position="top">
          <el-form-item label="目标包材系列">
            <el-select
              v-model="addVariantModelId"
              filterable
              placeholder="先选系列"
              @change="onAddVariantModelChanged"
              style="width: 100%"
            >
              <el-option v-for="m in allModels" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="规格" :disabled="!addVariantModelId">
            <el-select
              v-model="selectedVariantId"
              filterable
              placeholder="选择规格"
              :disabled="!addVariantModelId || loadingVariants"
              style="width: 100%"
            >
              <el-option
                v-for="variant in availableVariants"
                :key="variant.id"
                :label="variant.variantName || variant.specLabel || `规格 ${variant.id}`"
                :value="variant.id"
              >
                <div class="variant-option">
                  <span class="variant-option-name">{{
                    variant.variantName || variant.specLabel || `规格 ${variant.id}`
                  }}</span>
                  <span v-if="variant.capacityMl" class="variant-option-capacity">{{ variant.capacityMl }}ml</span>
                </div>
              </el-option>
            </el-select>
            <div v-if="!addVariantModelId" class="field-hints">先选择包材系列后加载规格列表。</div>
          </el-form-item>
          <el-form-item label="关联类型">
            <el-radio-group v-model="addVariantRelationType">
              <el-radio-button value="main">主规格</el-radio-button>
              <el-radio-button value="aux">辅助</el-radio-button>
              <el-radio-button value="sample">打样</el-radio-button>
              <el-radio-button value="other">其他</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="是否默认模具">
            <el-checkbox v-model="addVariantIsDefault">设为该规格的默认模具（报价/出成优先使用）</el-checkbox>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="addVariantVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedVariantId" :loading="addingVariant" @click="confirmAddVariant">
          确认添加
        </el-button>
      </template>
    </LqDialog>

    <!-- 添加规格对话框（单系列场景：直接选规格，使用 el-dialog） -->
    <el-dialog
      v-else
      v-model="addVariantVisible"
      title="添加关联规格"
      width="500px"
      :close-on-click-modal="true"
      append-to-body
    >
      <div class="add-variant-dialog">
        <div class="dialog-tip">选择要关联到当前模具的规格</div>
        <el-select v-model="selectedVariantId" filterable placeholder="搜索或选择规格" style="width: 100%">
          <el-option
            v-for="variant in availableVariants"
            :key="variant.id"
            :label="variant.variantName || variant.specLabel || `规格 ${variant.id}`"
            :value="variant.id"
          >
            <div class="variant-option">
              <span class="variant-option-name">{{
                variant.variantName || variant.specLabel || `规格 ${variant.id}`
              }}</span>
              <span v-if="variant.capacityMl" class="variant-option-capacity">{{ variant.capacityMl }}ml</span>
            </div>
          </el-option>
        </el-select>
      </div>
      <template #footer>
        <el-button @click="addVariantVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedVariantId" @click="confirmAddVariant">确认添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import {
  fetchPackagingMolds,
  fetchPackagingMold,
  fetchPackagingModel,
  fetchPackagingModels,
  deletePackagingMold,
  batchDeletePackagingMolds,
  batchUpdatePackagingMolds,
  createMoldVariantLink,
  deleteMoldVariantLink,
  type PackagingMoldSummary,
  type PackagingMoldDetail,
  type PackagingVariantView,
  type PackagingAssetView,
  type MoldVariantLinkView,
  type PackagingModelSummary
} from '@/api'
import { useListPagination } from '@/composables/list/useListPagination'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { fileAssetThumbnailUrlByOptions } from '@/utils/ui/fileAssetUrls'
import { packagingFileOwnerType } from '@/utils/catalog/fileAssetUtils'
import { formatDateTime } from '@/utils/common/dateUtils'
import { ElMessage, ElMessageBox, type TagProps } from 'element-plus'

import DataState from '@/components/common/DataState.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import LqTabs from '@/components/common/LqTabs.vue'
import LqSkeleton from '@/components/common/LqSkeleton.vue'
import LqPreview from '@/components/common/LqPreview.vue'
import LqDialog from '@/components/common/LqDialog.vue'
import MoldFormDialog from '@/components/mold/MoldFormDialog.vue'
import MoldImportDialog from '@/components/mold/MoldImportDialog.vue'
import MoldQualityPanel from '@/components/mold/MoldQualityPanel.vue'
import MoldAccessoriesPanel from '@/components/mold/MoldAccessoriesPanel.vue'

// ====== Props / Emits ======
type Mode = 'single' | 'global'

const props = withDefaults(
  defineProps<{
    mode: Mode
    modelId?: number
    variants?: PackagingVariantView[]
    assets?: PackagingAssetView[]
    enableBatch?: boolean
    enablePagination?: boolean
    showSeriesName?: boolean
    usePresetModelId?: boolean
    enableVariantClick?: boolean
    extraFilters?: {
      keyword?: string
      status?: string
      moldRole?: string
      moldTypeCode?: string
      engravingType?: string
      matchStatus?: string
      sort?: string
    }
    defaultPageSize?: number
    moldKeyword?: string
    batchMode?: boolean
  }>(),
  {
    enableBatch: (p: { mode: Mode }) => p.mode === 'global',
    enablePagination: true,
    showSeriesName: (p: { mode: Mode }) => p.mode === 'global',
    usePresetModelId: (p: { mode: Mode }) => p.mode === 'single',
    enableVariantClick: (p: { mode: Mode }) => p.mode === 'single',
    defaultPageSize: 20
  }
)

const emit = defineEmits<{
  'count-change': [count: number]
  'open-variant': [variant: PackagingVariantView]
  'data-changed': []
  'update:moldKeyword': [value: string]
  'update:batchMode': [value: boolean]
}>()

// ====== 常量 ======
const STATUS_OPTIONS: Record<string, { label: string; tag: TagProps['type'] }> = {
  in_use: { label: '在用', tag: 'success' },
  stopped: { label: '停用', tag: 'info' },
  repair: { label: '维修中', tag: 'warning' },
  unverified: { label: '待核实', tag: 'danger' }
}

const ROLE_OPTIONS: Record<string, string> = {
  main: '主模',
  accessory: '配件模',
  unknown: '未知'
}

const ENGRAVING_OPTIONS = [
  { value: 'blank', label: '空白' },
  { value: 'generic', label: '通用' },
  { value: 'brand_custom', label: '品牌专版' },
  { value: 'functional', label: '功能标识' }
]

const DEFAULT_SORT = { prop: 'moldCode', order: 'ascending' as const }

// ====== 数据 ======
const loading = ref(false)
const listState = ref<'ready' | 'loading' | 'empty' | 'filtered-empty' | 'error' | 'stale'>('loading')
const errorMsg = ref('')
const molds = ref<PackagingMoldSummary[]>([])
const sort = reactive<{ prop: string; order: string }>({ ...DEFAULT_SORT })
const pagination = useListPagination({ size: props.defaultPageSize })
const { pageInfo, updateFromResponse, queryParams } = pagination

function changeGroupPageSize(size: number) {
  pagination.onPageSizeChange(size)
  loadMolds()
}

const batchSel = useBatchSelection()
const { selectedCount, clearSelection, toggleSelection, getSelectedIds, isSelected, isAllSelected, toggleSelectAll } =
  batchSel
const batchLoading = ref(false)
const isAllPageSelected = computed(() => isAllSelected.value(molds.value))

function toggleSelectAllCurrentPage() {
  toggleSelectAll(molds.value)
}

// batchMode 受控：父级通过 v-model:batch-mode 接管（如批量管理按钮），
// 未传时使用 composable 内部 batchMode。外部变化时同步内部；内部退出批量时也同步外部。
const batchMode = computed<boolean>({
  get: () => (props.batchMode !== undefined ? props.batchMode : batchSel.batchMode.value),
  set: v => {
    if (props.batchMode !== undefined) emit('update:batchMode', v)
    if (v !== batchSel.batchMode.value) {
      // 调用 composable 自带方法切换（退出时会清选中）
      if (v !== batchSel.batchMode.value) {
        if (v) batchSel.batchMode.value = true
        else batchSel.toggleBatchMode()
      }
    }
  }
})

function exitBatchMode() {
  if (props.batchMode !== undefined) emit('update:batchMode', false)
  if (batchSel.batchMode.value) batchSel.toggleBatchMode()
}

// 左侧：搜索、按规格族组织模具记录以及具体模具选中状态。
// 父级可通过 v-model:moldKeyword 接管搜索；未传时回退到本地状态。
const localMoldKeyword = ref('')
const moldKeyword = computed<string>({
  get: () => (props.moldKeyword !== undefined ? props.moldKeyword : localMoldKeyword.value),
  set: v => {
    if (props.moldKeyword !== undefined) emit('update:moldKeyword', v)
    else localMoldKeyword.value = v
  }
})
const selectedMoldId = ref<number | null>(null)

// 右侧：详情
const detailData = ref<PackagingMoldDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const activeTab = ref('variants')
const currentModelDetail = ref<{ variants: PackagingVariantView[]; assets: PackagingAssetView[] } | null>(null)

// 弹窗
const formVisible = ref(false)
const editingId = ref<number | undefined>(undefined)
const importVisible = ref(false)

// 添加规格对话框（单系列：直接选规格；全系列：先选系列再选）
const addVariantVisible = ref(false)
const addVariantModelId = ref<number | null>(null)
const selectedVariantId = ref<number | null>(null)
const addVariantRelationType = ref<'main' | 'aux' | 'sample' | 'other'>('main')
const addVariantIsDefault = ref(false)
const loadingVariants = ref(false)
const addingVariant = ref(false)
const allModels = ref<PackagingModelSummary[]>([])
const modelVariants = ref<PackagingVariantView[]>([])

// 预览
const previewVisible = ref(false)
const previewUrl = ref('')
const previewTitle = ref('')
const previewFileName = ref('')

// ====== 工具函数 ======
function statusLabel(s?: string) {
  return STATUS_OPTIONS[s || '']?.label || s || '—'
}
function statusTagType(s?: string): string {
  return (STATUS_OPTIONS[s || '']?.tag || 'info') as string
}
function roleLabel(r?: string) {
  return ROLE_OPTIONS[r || ''] || r || '—'
}
function engravingLabel(t?: string) {
  return ENGRAVING_OPTIONS.find(o => o.value === t)?.label || t || '—'
}

function resetLocalSearch() {
  moldKeyword.value = ''
}

// ====== 计算属性 ======
const selectedMold = computed(() => {
  if (!selectedMoldId.value) return null
  return molds.value.find(m => m.id === selectedMoldId.value) || null
})

const filteredMolds = computed(() => {
  const kw = moldKeyword.value.trim().toLowerCase()
  if (!kw) return molds.value
  const fields =
    props.mode === 'global'
      ? (m: PackagingMoldSummary) => [
          m.moldCode || '',
          m.baseMoldCode || '',
          m.moldTypeCode || '',
          m.groupName || '',
          m.modelName || ''
        ]
      : (m: PackagingMoldSummary) => [m.moldCode || '', m.baseMoldCode || '', m.moldTypeCode || '', m.groupName || '']
  return molds.value.filter(m => fields(m).some(f => f.toLowerCase().includes(kw)))
})

interface MoldGroup {
  groupName: string
  items: PackagingMoldSummary[]
  linkedVariantCount: number
}

function toMoldGroup(groupName: string, items: PackagingMoldSummary[]): MoldGroup {
  return {
    groupName,
    items,
    linkedVariantCount: items.reduce((total, mold) => total + (mold.variantCount || 0), 0)
  }
}

const groupedMolds = computed<MoldGroup[]>(() => {
  const groups = new Map<string, PackagingMoldSummary[]>()
  const ungrouped: PackagingMoldSummary[] = []
  for (const mold of filteredMolds.value) {
    const key = mold.groupName?.trim()
    if (key) {
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key)!.push(mold)
    } else {
      ungrouped.push(mold)
    }
  }
  const result: MoldGroup[] = [...groups.entries()]
    .sort((a, b) => a[0].localeCompare(b[0], 'zh-CN'))
    .map(([groupName, items]) => toMoldGroup(groupName, items))
  if (ungrouped.length > 0) {
    result.push(toMoldGroup('未分组', ungrouped))
  }
  return result
})

// ====== 规格卡片数据 ======
interface SpecCardData {
  link: MoldVariantLinkView
  variant: PackagingVariantView
  asset: PackagingAssetView | null
  thumbnailUrl: string
  relationType: string
  isDefault: boolean
  remark: string
}

const linkedSpecCards = computed<SpecCardData[]>(() => {
  if (!detailData.value) return []
  const links = detailData.value.variantLinks
  if (!links || links.length === 0) return []

  // 单系列：使用 props.variants / props.assets
  // 全系列：使用 currentModelDetail.variants / currentModelDetail.assets
  const variants: PackagingVariantView[] =
    (props.mode === 'single' ? props.variants : currentModelDetail.value?.variants) || []
  const assets: PackagingAssetView[] = (props.mode === 'single' ? props.assets : currentModelDetail.value?.assets) || []
  const effectiveModelId = props.mode === 'single' ? props.modelId : selectedMold.value?.modelId

  return links.map(link => {
    const variant =
      variants.find(v => v.id === link.variantId) ||
      ({
        id: link.variantId,
        variantName: link.variantName,
        capacityMl: link.variantCapacity,
        heightMm: link.variantHeight,
        sortOrder: 0
      } as PackagingVariantView)
    const asset = variant.id
      ? assets.find(a => a.variantId === variant.id && a.category === 'pkg_main_image') ||
        assets.find(a => a.variantId === variant.id) ||
        null
      : null
    const thumbnailUrl =
      asset && effectiveModelId
        ? fileAssetThumbnailUrlByOptions({
            ownerType: packagingFileOwnerType(asset),
            assetId: asset.id,
            category: asset.category,
            fileName: asset.fileName,
            modelId: effectiveModelId,
            size: 400
          })
        : ''
    return {
      link,
      variant,
      asset,
      thumbnailUrl,
      relationType: link.relationType,
      isDefault: link.isDefault,
      remark: link.remark || ''
    }
  })
})

// 模具信息 Tab 口径/高度推断
const moldCaliberText = computed(() => {
  if (!detailData.value) return ''
  const firstVariant = linkedSpecCards.value.find(s => s.variant)?.variant
  if (!firstVariant) return ''
  if (firstVariant.lengthMm && firstVariant.widthMm) return `${firstVariant.lengthMm}×${firstVariant.widthMm}mm`
  if (firstVariant.topDiameterMm) return `Ø${firstVariant.topDiameterMm}mm`
  return ''
})

const moldHeightText = computed(() => {
  if (!detailData.value) return ''
  const heights = linkedSpecCards.value.map(s => s.variant?.heightMm).filter((h): h is string => !!h)
  if (heights.length === 0) return ''
  const unique = [...new Set(heights)]
  return unique.length === 1 ? `${unique[0]}mm` : unique.map(h => `${h}mm`).join(' / ')
})

// 可添加的规格（排除已关联）
const availableVariants = computed<PackagingVariantView[]>(() => {
  if (props.mode === 'single') {
    if (!props.variants || props.variants.length === 0) return []
    const linkedIds = new Set(detailData.value?.variantLinks?.map(l => l.variantId) || [])
    return props.variants.filter(v => !linkedIds.has(v.id))
  } else {
    // 全系列：从 modelVariants 中选
    if (!modelVariants.value || modelVariants.value.length === 0) return []
    const linkedIds = new Set(detailData.value?.variantLinks?.map(l => l.variantId) || [])
    return modelVariants.value.filter(v => !linkedIds.has(v.id))
  }
})

// ====== 模具加载 ======
async function loadMolds() {
  loading.value = true
  listState.value = 'loading'
  const { page, size } = queryParams.value
  const sortParam = sort.prop && sort.order ? `${sort.prop},${sort.order === 'descending' ? 'desc' : 'asc'}` : undefined
  const previousSelected = selectedMoldId.value

  try {
    const params: Record<string, any> = { size, pageByGroup: props.enablePagination }
    if (props.enablePagination) params.page = page

    if (props.mode === 'single') {
      if (props.modelId) params.modelId = props.modelId
    } else {
      const ef = props.extraFilters || {}
      if (ef.keyword) params.keyword = ef.keyword
      if (ef.status) params.status = ef.status
      if (ef.moldRole) params.moldRole = ef.moldRole
      if (ef.moldTypeCode) params.moldTypeCode = ef.moldTypeCode
      if (ef.engravingType) params.engravingType = ef.engravingType
      if (ef.matchStatus === 'unmatched') params.hasVariant = false
      else if (ef.matchStatus === 'matched') params.hasVariant = true
      if (ef.sort) params.sort = ef.sort
      else if (sortParam) params.sort = sortParam
    }

    const data = (await fetchPackagingMolds(params)) as any
    molds.value = data.content || data || []
    if (props.enablePagination) updateFromResponse(data)

    if (molds.value.length === 0) {
      listState.value =
        props.extraFilters &&
        (props.extraFilters.keyword ||
          props.extraFilters.status ||
          props.extraFilters.moldRole ||
          props.extraFilters.moldTypeCode ||
          props.extraFilters.engravingType ||
          props.extraFilters.matchStatus)
          ? 'filtered-empty'
          : 'empty'
      selectedMoldId.value = null
      detailData.value = null
      currentModelDetail.value = null
    } else {
      listState.value = 'ready'
      const stillExists = molds.value.find(m => m.id === previousSelected)
      if (stillExists) {
        selectedMoldId.value = stillExists.id
      } else if (selectedMoldId.value == null) {
        const first = molds.value[0]
        selectedMoldId.value = first.id
        activeTab.value = 'variants'
        loadDetail(first.id)
      }
    }
    emit('count-change', molds.value.length)
  } catch (e: any) {
    if (molds.value.length > 0) {
      listState.value = 'stale'
    } else {
      listState.value = 'error'
      errorMsg.value = e?.message || '加载失败'
    }
  } finally {
    loading.value = false
  }
}

// ====== 详情加载 ======
async function loadDetail(moldId: number) {
  detailLoading.value = true
  detailError.value = ''
  try {
    const data = await fetchPackagingMold(moldId)
    detailData.value = data

    // 全系列模式：需要预取系列 variants + assets
    if (props.mode === 'global' && data.mold.modelId) {
      try {
        const md = await fetchPackagingModel(data.mold.modelId)
        currentModelDetail.value = { variants: md.variants || [], assets: md.assets || [] }
      } catch {
        currentModelDetail.value = { variants: [], assets: [] }
      }
    } else if (props.mode === 'global') {
      currentModelDetail.value = null
    }
  } catch (e: any) {
    detailData.value = null
    if (props.mode === 'global') currentModelDetail.value = null
    detailError.value = e?.message || '加载失败'
  } finally {
    detailLoading.value = false
  }
}

function onCardClick(mold: PackagingMoldSummary) {
  // 批量模式：点击卡片即切换勾选，不跳转详情
  if (batchMode.value) {
    toggleSelection(mold.id)
    return
  }
  if (selectedMoldId.value === mold.id) return
  selectedMoldId.value = mold.id
  activeTab.value = 'variants'
  loadDetail(mold.id)
}

function moldPublishEligibility(mold: Pick<PackagingMoldSummary, 'moldRole' | 'status' | 'websiteEnabled'>) {
  if (mold.moldRole !== 'main') return { eligible: false, reason: '只有主模可以发布到官网' }
  if (mold.status !== 'in_use') return { eligible: false, reason: '状态需调整为“在用”' }
  return {
    eligible: true,
    reason: mold.websiteEnabled ? '已发布到官网，点击可取消发布' : '发布条件已满足，点击发布到官网'
  }
}

async function toggleMoldWebsite(mold: PackagingMoldSummary) {
  const previous = mold.websiteEnabled === true
  if (!previous && mold.moldRole !== 'main') {
    ElMessage.warning('只有主模可以发布到官网')
    return
  }
  if (!previous && mold.status !== 'in_use') {
    ElMessage.warning('请先将模具状态调整为“在用”，再发布到官网')
    return
  }
  try {
    await ElMessageBox.confirm(
      previous
        ? `「${mold.moldCode}」将从官网隐藏，但不会删除模具档案及其包材关联。`
        : `「${mold.moldCode}」将作为可公开主模展示，官网读取的规格与包材关系仍来自当前档案。`,
      previous ? '确认取消官网展示' : '确认发布模具',
      {
        confirmButtonText: previous ? '取消展示' : '确认发布',
        cancelButtonText: '返回检查',
        type: previous ? 'warning' : 'info'
      }
    )
  } catch {
    return
  }
  mold.websiteEnabled = !previous
  try {
    await batchUpdatePackagingMolds([mold.id], { websiteEnabled: mold.websiteEnabled })
    if (detailData.value?.mold.id === mold.id) detailData.value.mold.websiteEnabled = mold.websiteEnabled
    ElMessage.success(`${mold.moldCode} 已${mold.websiteEnabled ? '发布到官网' : '取消官网发布'}`)
    emit('data-changed')
  } catch (error: any) {
    mold.websiteEnabled = previous
    if (detailData.value?.mold.id === mold.id) detailData.value.mold.websiteEnabled = previous
    ElMessage.error(error?.message || '更新官网发布状态失败')
  }
}

// ====== 规格卡片点击 ======
function onSpecCardClick(spec: SpecCardData) {
  if (props.enableVariantClick) {
    emit('open-variant', spec.variant)
  }
}

// ====== 表单 ======
function openCreate() {
  editingId.value = undefined
  formVisible.value = true
}
function openEdit(row: PackagingMoldSummary) {
  editingId.value = row.id
  formVisible.value = true
}
function onSaved() {
  const prev = selectedMoldId.value
  loadMolds()
  emit('data-changed')
  if (prev) {
    setTimeout(() => {
      const m = molds.value.find(x => x.id === prev)
      if (m) loadDetail(m.id)
    }, 300)
  }
}

// ====== 删除 ======
async function doDelete(row: PackagingMoldSummary) {
  try {
    await deletePackagingMold(row.id)
    ElMessage.success('已移入回收站')
    if (selectedMoldId.value === row.id) {
      selectedMoldId.value = null
      detailData.value = null
      currentModelDetail.value = null
    }
    onDeleted(row.id)
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

function onDeleted(id: number) {
  molds.value = molds.value.filter(m => m.id !== id)
  if (props.enablePagination) pageInfo.total = Math.max(0, pageInfo.total - 1)
  if (molds.value.length === 0) {
    listState.value =
      props.extraFilters &&
      (props.extraFilters.keyword ||
        props.extraFilters.status ||
        props.extraFilters.moldRole ||
        props.extraFilters.moldTypeCode ||
        props.extraFilters.engravingType)
        ? 'filtered-empty'
        : 'empty'
  }
  emit('data-changed')
}

// 单系列模式删除
async function onDeleteMold() {
  if (!selectedMoldId.value) return
  try {
    await ElMessageBox.confirm(`确定将模具 ${selectedMold.value?.moldCode} 移入回收站？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deletePackagingMold(selectedMoldId.value)
    ElMessage.success('已移入回收站')
    const deletedId = selectedMoldId.value
    selectedMoldId.value = null
    detailData.value = null
    loadMolds()
    const first = molds.value.find(m => m.id !== deletedId)
    if (first) {
      selectedMoldId.value = first.id
      loadDetail(first.id)
    }
    emit('data-changed')
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

// ====== 批量删除 ======
async function doBatchDelete() {
  const ids = getSelectedIds() as number[]
  if (ids.length === 0) return
  try {
    batchLoading.value = true
    await ElMessageBox.confirm(`确定将 ${ids.length} 个模具移入回收站？`, '批量删除', { type: 'warning' })
    await batchDeletePackagingMolds(ids)
    ElMessage.success('已批量移入回收站')
    if (ids.includes(selectedMoldId.value as number)) {
      selectedMoldId.value = null
      detailData.value = null
      currentModelDetail.value = null
    }
    clearSelection()
    onSaved()
  } catch {
    // 取消或报错
  } finally {
    batchLoading.value = false
  }
}

// ====== 导入 ======
function handleImportFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importVisible.value = true
  setTimeout(() => {
    const evt = new CustomEvent('mold-import-file-ready', { detail: file })
    window.dispatchEvent(evt)
  }, 50)
}
function openImport() {
  importVisible.value = true
}
function onImportConfirmed() {
  const previousMoldId = selectedMoldId.value
  loadMolds().then(() => {
    if (previousMoldId) {
      const mold = molds.value.find(m => m.id === previousMoldId)
      if (mold) {
        selectedMoldId.value = mold.id
        loadDetail(mold.id)
      } else if (molds.value.length > 0) {
        const first = molds.value[0]
        selectedMoldId.value = first.id
        loadDetail(first.id)
      }
    }
  })
  ElMessage.success('导入完成')
  emit('data-changed')
}

// ====== 配件变更 ======
function onAccessoriesChanged() {
  if (!selectedMold.value) return
  loadDetail(selectedMold.value.id).then(() => loadMolds())
}

// ====== 预览 ======
function previewImage(spec: SpecCardData) {
  if (!spec.asset) return
  const effectiveModelId = props.mode === 'single' ? props.modelId : selectedMold.value?.modelId
  if (!effectiveModelId) return
  const ownerType = packagingFileOwnerType(spec.asset)
  const previewSize = props.mode === 'single' ? 800 : 1200
  const url = fileAssetThumbnailUrlByOptions({
    ownerType,
    assetId: spec.asset.id,
    category: spec.asset.category,
    fileName: spec.asset.fileName,
    modelId: effectiveModelId,
    size: previewSize
  })
  previewUrl.value = url
  previewTitle.value = spec.variant.variantName || spec.variant.specLabel || '规格图片'
  previewFileName.value = spec.asset.fileName
  previewVisible.value = true
}

// ====== 添加规格 ======
async function openAddVariant() {
  if (!selectedMold.value) return

  if (props.mode === 'global') {
    addVariantModelId.value = selectedMold.value.modelId ?? null
    selectedVariantId.value = null
    addVariantRelationType.value = 'main'
    addVariantIsDefault.value = false
    modelVariants.value = []
    loadingVariants.value = false
    if (allModels.value.length === 0) {
      try {
        const data = (await fetchPackagingModels({ size: 9999 })) as any
        allModels.value = data.content || data || []
      } catch (e: any) {
        ElMessage.error(e?.message || '加载系列失败')
        return
      }
    }
    if (addVariantModelId.value) {
      await onAddVariantModelChanged(addVariantModelId.value)
    }
    addVariantVisible.value = true
  } else {
    selectedVariantId.value = null
    addVariantVisible.value = true
  }
}

async function onAddVariantModelChanged(modelId: number) {
  loadingVariants.value = true
  modelVariants.value = []
  try {
    const md = await fetchPackagingModel(modelId)
    modelVariants.value = md.variants || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载规格失败')
  } finally {
    loadingVariants.value = false
  }
}

async function confirmAddVariant() {
  if (!selectedVariantId.value || !selectedMoldId.value) return
  addingVariant.value = true
  try {
    const payload: any = {
      moldId: selectedMoldId.value,
      variantId: selectedVariantId.value,
      relationType: props.mode === 'global' ? addVariantRelationType.value : 'main'
    }
    if (props.mode === 'global') payload.isDefault = addVariantIsDefault.value
    await createMoldVariantLink(payload)
    ElMessage.success('规格关联成功')
    addVariantVisible.value = false
    selectedVariantId.value = null
    await loadDetail(selectedMoldId.value)
    await loadMolds()
  } catch (e: any) {
    ElMessage.error(e?.message || '关联失败')
  } finally {
    addingVariant.value = false
  }
}

async function removeVariantLink(linkId: number) {
  try {
    await ElMessageBox.confirm('确定要删除该规格关联吗？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteMoldVariantLink(linkId)
    ElMessage.success('已删除关联')
    if (selectedMoldId.value) {
      await loadDetail(selectedMoldId.value)
      await loadMolds()
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

// ====== Watch & 生命周期 ======
watch(
  () => selectedMoldId.value,
  (newId, oldId) => {
    if (newId != null && newId !== oldId) {
      activeTab.value = 'variants'
    }
  }
)

watch(
  () => props.extraFilters,
  () => {
    if (props.mode === 'global') {
      pageInfo.page = 0
      loadMolds()
    }
  },
  { deep: true }
)

if (props.mode === 'single') {
  watch(
    () => props.modelId,
    () => {
      selectedMoldId.value = null
      detailData.value = null
      loadMolds()
    }
  )
}

onMounted(() => loadMolds())

// ====== 对外暴露方法 ======
function getMolds() {
  return molds.value
}

defineExpose({
  getMolds,
  openCreate,
  openImport,
  handleImportFile,
  resetLocalSearch,
  exitBatchMode,
  selectedCount,
  isAllPageSelected,
  getSelectedIds,
  toggleSelectAllCurrentPage,
  doBatchDelete,
  loadMolds,
  clearSelection
})
</script>

<style scoped>
/* ====== 主从布局 ====== */
.master-detail-layout {
  display: flex;
  flex-direction: column;
  gap: var(--space-s, 6px);
  min-height: 0;
  overflow: hidden;
  flex: 1;
}

/* 批量操作浮条：位于主从区顶部，横跨左右面板 */
.batch-bar {
  flex-shrink: 0;
}

/* 主从行：左栏 + 详情 */
.master-detail-row {
  display: flex;
  gap: var(--space-m, 12px);
  min-height: 0;
  overflow: hidden;
  flex: 1;
}

/* 左侧：模具卡片面板 */
.master-panel {
  width: 380px;
  flex-shrink: 0;
  min-width: 0;
  border: 1px solid var(--color-border-subtle, #e5e7eb);
  border-radius: var(--radius-card, 10px);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--color-surface, #fff);
}

.select-all-checkbox {
  margin-right: auto;
}

.master-panel :deep(.data-state) {
  padding: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

.master-panel :deep(.data-state-content) {
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}

.master-panel :deep(.data-state-empty),
.master-panel :deep(.data-state-error),
.master-panel :deep(.data-state-stale) {
  padding: var(--space-xl, 24px) var(--space-m, 16px);
  flex: none;
}

/* 分页器：固定在左栏底部，不随内容滚动，视觉上与数据区分隔 */
.group-pagination {
  flex-shrink: 0;
  border-top: 1px solid var(--color-border-subtle, #e5e7eb);
  background: var(--color-surface-alt, #f8fafc);
}

.group-pagination-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px 0;
  font-size: 11px;
  color: var(--color-text-muted, #64748b);
}

.group-page-size {
  width: 92px;
}

/* 本组件的分页器实例：使用私有类名，不与公共 styles/modules/list-controls.css 的
   .list-pagination 同名（原同名时同时吃到了全局的 margin-top，属于隐式耦合）。
   下列 display / justify-content / margin-top 原由全局 .list-pagination 提供，显式复述以保持渲染不变。
   注：此前这里用 `.list-pagination :deep(.el-pagination …)` 写了一套紧凑分页皮肤，
   但 class 落在 el-pagination 根节点自身，`:deep()` 生成的是后代选择器，规则从未命中（死样式），
   故一并移除；如需启用紧凑皮肤，选择器应去掉中间的 .el-pagination 层级。 */
.molds-group-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-md);
  flex-shrink: 0;
  padding: 6px 10px;
  min-height: 44px;
}

.filter-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-s, 8px);
  padding: var(--space-xl, 24px) var(--space-m, 16px);
  text-align: center;
}

.filter-empty p {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-muted, #64748b);
}

/* ====== 按规格族分组 ====== */
.mold-grouped-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
  padding: var(--space-s, 8px);
}

.mold-group {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.mold-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 2px 6px;
  margin-bottom: 2px;
  border-bottom: 1px solid var(--color-border-lighter, #edf1f5);
  position: sticky;
  top: 0;
  background: var(--color-surface, #fff);
  z-index: 1;
}

.mold-group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.mold-group-count {
  font-size: 11px;
  color: var(--color-text-muted, #64748b);
  background: var(--color-surface-alt, #f3f4f6);
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

/* ====== 模具卡片网格（单列窄侧栏） ====== */
.mold-card-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-s, 8px);
  padding: var(--space-s, 8px);
}

.mold-card {
  position: relative;
  border: 1px solid var(--color-border, #e2e8f0);
  border-radius: var(--radius-lg, 8px);
  background: var(--color-surface, #fff);
  cursor: pointer;
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    background 0.15s;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.mold-card:hover {
  border-color: var(--color-primary, #0f766e);
  box-shadow: 0 2px 8px var(--color-primary-ring, rgba(15, 118, 110, 0.12));
}

.mold-card.is-selected {
  border-color: var(--color-primary, #0f766e);
  background: var(--color-primary-bg-light, #f0fdfa);
  box-shadow: 0 0 0 1px var(--color-primary, #0f766e) inset;
}

.mold-card.is-unmatched {
  border-color: var(--color-warning-border, #fed7aa);
  background: linear-gradient(180deg, #fff7ed 0%, #fff 55%);
}

.mold-card.is-unmatched::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  bottom: 0;
  width: 3px;
  background: linear-gradient(180deg, #f59e0b 0%, #ea580c 100%);
}

.mold-card.is-unmatched:hover {
  border-color: var(--color-warning, #f59e0b);
  box-shadow: 0 2px 8px rgba(245, 158, 11, 0.18);
}

.mold-card.is-selected.is-unmatched {
  border-color: var(--color-warning, #f59e0b);
  background: linear-gradient(180deg, #ffedd5 0%, #fff7ed 100%);
  box-shadow: 0 0 0 1px var(--color-warning, #f59e0b) inset;
}

.mold-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-s, 6px);
  padding: var(--space-s, 8px) var(--space-m, 10px);
  border-bottom: 1px solid var(--color-border-lighter, #edf1f5);
}

.mold-card.is-selected .mold-card-header {
  border-bottom-color: var(--color-primary-border, #a7f3d0);
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-2xs, 4px);
  flex-shrink: 0;
}

.unmatched-tag {
  font-size: 10px;
  line-height: 1.4;
}

.website-publish-tag {
  cursor: pointer;
  user-select: none;
}

.website-publish-tag:focus-visible {
  outline: 2px solid var(--color-primary-border, #99f6e4);
  outline-offset: 2px;
}

.mold-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.mold-card-title strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  font-family: var(--font-mono, ui-monospace, monospace);
  letter-spacing: 0.3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mold-base-badge {
  font-size: 10px;
  color: var(--color-text-muted, #64748b);
  background: var(--color-surface-alt, #f3f4f6);
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
  font-family: var(--font-mono, ui-monospace, monospace);
}

.mold-card.is-selected .mold-base-badge {
  background: var(--color-surface, #fff);
  color: var(--color-primary, #0f766e);
}

.mold-card-product {
  padding: 6px var(--space-m, 10px);
  font-size: 12px;
  color: var(--color-text-secondary, #374151);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-bottom: 1px solid var(--color-border-lighter, #edf1f5);
}

/* 口径/高度行 */
.mold-card-dimensions {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px var(--space-m, 10px);
  background: linear-gradient(135deg, rgba(15, 118, 110, 0.04) 0%, rgba(15, 118, 110, 0.02) 100%);
  border-bottom: 1px solid var(--color-border-lighter, #edf1f5);
}

.mold-card-dimensions .dim-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.mold-card-dimensions .dim-item + .dim-item {
  padding-top: 3px;
  border-top: 1px dashed var(--color-border-lighter, #e2e8f0);
}

.dim-label {
  font-size: 11px;
  color: var(--color-text-muted, #64748b);
  font-weight: 500;
  flex-shrink: 0;
  width: 30px;
}

.dim-value {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-primary, #0f766e);
  font-family: var(--font-mono, ui-monospace, monospace);
  letter-spacing: 0.2px;
  line-height: 1.4;
  word-break: break-all;
  white-space: normal;
  flex: 1;
  min-width: 0;
}

/* 统计行 */
.mold-card-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: var(--space-s, 8px) var(--space-m, 10px);
  gap: var(--space-s, 6px);
}

.mold-card-stats .stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 0;
}

.mold-card-stats .stat + .stat {
  border-left: 1px solid var(--color-border-lighter, #edf1f5);
}

.stat-label {
  font-size: 11px;
  color: var(--color-text-muted, #64748b);
  line-height: 1;
}

.stat-value {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text-primary, #111827);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.has-variants {
  color: var(--color-primary, #0f766e);
}

.stat-value.is-zero {
  color: var(--color-warning, #f59e0b);
}

.mold-card-footer {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 0 var(--space-m, 10px) var(--space-s, 8px);
}

.mold-card-footer:empty {
  display: none;
}

.type-chip {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--color-primary-bg, #ecfdf5);
  color: var(--color-primary, #0f766e);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-weight: 600;
  font-size: 11px;
}

/* ====== 右侧详情面板 ====== */
.detail-panel {
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--color-border-subtle, #e5e7eb);
  border-radius: var(--radius-card, 10px);
  background: var(--color-surface, #fff);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-s, 8px) var(--space-m, 12px);
  border-bottom: 1px solid var(--color-border-subtle, #e5e7eb);
  background: var(--color-surface-alt, #f8fafc);
  flex-shrink: 0;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: var(--space-s, 8px);
  flex-wrap: wrap;
  min-width: 0;
}

.detail-title h3 {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  margin: 0;
  font-family: var(--font-mono, ui-monospace, monospace);
}

.series-tag {
  font-size: 11px;
}

.detail-meta {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.detail-actions {
  display: flex;
  gap: var(--space-s, 8px);
  flex-shrink: 0;
}

.detail-body {
  flex: 1;
  overflow-y: auto;
  position: relative;
}

.detail-skeleton {
  padding: var(--space-m, 12px);
}

/* Tabs */
.detail-tabs {
  padding: 0 var(--space-m, 12px) var(--space-m, 12px);
}

.detail-tabs :deep(.el-tabs__header) {
  position: sticky;
  top: 0;
  z-index: 5;
  margin: 0;
  padding: var(--space-s, 8px) 0;
  background: var(--color-surface, #fff);
  flex-shrink: 0;
}

.detail-tabs :deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
  border-radius: var(--radius-lg, 8px);
  padding: 3px;
  background: var(--color-surface-alt, #f8fafc);
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}

.detail-tabs :deep(.el-tabs__nav-scroll) {
  border-radius: var(--radius-lg, 8px);
}

.detail-tabs :deep(.el-tabs__item) {
  height: 30px;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary, #6b7280);
  transition:
    color 0.15s,
    background-color 0.15s,
    box-shadow 0.15s;
}

.detail-tabs :deep(.el-tabs__item:hover) {
  color: var(--color-primary, #0f766e);
}

.detail-tabs :deep(.el-tabs__item.is-active) {
  color: var(--color-primary, #0f766e);
  background: var(--color-surface, #fff);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.1);
  font-weight: 600;
}

.detail-tabs :deep(.el-tabs__active-bar) {
  display: none;
}

.detail-tabs :deep(.el-tabs__content) {
  padding-top: var(--space-s, 8px);
}

/* ====== 关联规格标准卡片 ====== */
.variant-standard-card-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--space-m, 12px);
  padding: 2px 0;
}

.variant-standard-card {
  position: relative;
  border: 1px solid var(--color-border, #e2e8f0);
  border-radius: var(--radius-lg, 8px);
  padding: 0;
  background: var(--color-surface, #fff);
  cursor: pointer;
  overflow: hidden;
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    transform 0.1s;
}

.variant-standard-card:hover {
  border-color: var(--color-primary, #0f766e);
  box-shadow: 0 2px 8px var(--color-primary-ring, rgba(15, 118, 110, 0.12));
}

.card-image-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: linear-gradient(135deg, var(--color-surface-alt, #f8fafc) 0%, var(--color-primary-bg, #ecfdf5) 100%);
  border-bottom: 1px solid var(--color-border-lighter, #eef0f3);
}

.card-image-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.card-image-wrap.no-image {
  background: var(--color-surface-alt, #f3f4f6);
}

.image-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--color-text-tertiary, #9ca3af);
  gap: 6px;
}

.image-placeholder span {
  font-size: 13px;
  font-weight: 500;
}

.capacity-label {
  position: absolute;
  left: 10px;
  bottom: 10px;
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  padding: 4px 10px;
  background: linear-gradient(135deg, var(--color-primary-light, #0d9488), var(--color-primary, #0f766e));
  color: var(--color-text-on-primary, #fff);
  border-radius: 6px;
  line-height: 1;
  box-shadow: 0 2px 6px var(--color-primary-shadow, rgba(15, 118, 110, 0.35));
}

.capacity-label strong {
  font-size: 15px;
  font-weight: 700;
  font-family: var(--font-mono, ui-monospace, monospace);
}

.capacity-label em {
  font-size: 11px;
  font-style: normal;
  opacity: 0.9;
}

.image-preview-btn {
  position: absolute;
  right: 8px;
  top: 8px;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  background: rgba(17, 24, 39, 0.55);
  color: #fff;
  opacity: 0;
  transition: opacity 0.15s;
}

.variant-standard-card:hover .image-preview-btn {
  opacity: 1;
}

.image-preview-btn:hover {
  background: rgba(17, 24, 39, 0.75);
}

/* 标题区 */
.card-spec-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: linear-gradient(180deg, var(--color-primary-bg, #ecfdf5) 0%, var(--color-primary-bg-light, #f0fdfa) 100%);
  border-bottom: 1px solid var(--color-border-lighter, #eef0f3);
}

.card-spec-title strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-primary, #0f766e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.card-shape-pill {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-primary, #0f766e);
  border: 1px solid var(--color-primary-border, #a7f3d0);
  border-radius: var(--radius-pill, 999px);
  padding: 1px 8px;
  background: var(--color-surface, #fff);
}

/* 信息区 */
.card-info-body {
  padding: 8px 12px 10px;
}

.card-info-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.card-info-metrics .metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  border-radius: var(--radius-lg, 8px);
  padding: 6px 8px;
  background: var(--color-surface-alt, #f8fafc);
}

.card-info-metrics dt {
  margin: 0;
  font-size: 11px;
  color: var(--color-text-tertiary, #9ca3af);
  font-weight: 400;
}

.card-info-metrics dd {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  overflow-wrap: anywhere;
}

.card-info-footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding-top: 8px;
}

.card-remark {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--color-text-tertiary, #9ca3af);
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ====== 模具信息 Tab ====== */
.info-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
}

.info-block {
  background: var(--color-surface, #fff);
  border: 1px solid var(--color-border, #e2e8f0);
  border-radius: var(--radius-xl, 10px);
  padding: var(--space-m, 12px) var(--space-lg-xl) var(--space-lg-xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-s, 8px);
}

.info-block-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--color-border-subtle, #f1f5f9);
}

.info-block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  letter-spacing: 0.2px;
}

.info-block-hint {
  font-size: 11.5px;
  color: var(--color-text-tertiary, #9ca3af);
  font-weight: 400;
}

.info-meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-s, 8px) var(--space-m, 16px);
}

.info-meta-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  border-radius: var(--radius-lg, 8px);
  padding: 8px 10px;
  background: var(--color-surface-alt, #f8fafc);
}

.meta-label {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.meta-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
  overflow-wrap: anywhere;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.meta-value.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-weight: 500;
}

.meta-hint-tag {
  font-size: 10.5px;
  padding: 0 5px;
  height: 16px;
  line-height: 14px;
}

.meta-hint-text {
  font-size: 11px;
  font-weight: 400;
  color: var(--color-text-tertiary, #9ca3af);
}

.meta-value.inferred {
  color: var(--color-text-secondary, #6b7280);
  font-weight: 500;
}

/* 刻字与备注 —— 堆叠行布局（非网格） */
.info-block--stacked {
  gap: 6px;
  padding-bottom: var(--space-m, 12px);
}

.info-stack-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-m, 12px);
  padding: 6px 10px;
  background: var(--color-surface-alt, #f8fafc);
  border-radius: var(--radius-lg, 8px);
}

.info-stack-label {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
  flex-shrink: 0;
  width: 80px;
  line-height: 20px;
}

.info-stack-value {
  flex: 1;
  min-width: 0;
}

/* 页脚元信息（创建/更新时间、来源） */
.info-meta-footer {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-m, 16px);
  font-size: 11.5px;
  color: var(--color-text-tertiary, #9ca3af);
  padding: 0 2px 4px;
  justify-content: flex-end;
}

.info-meta-footer > span {
  white-space: nowrap;
}

.info-engraving {
  display: flex;
  align-items: baseline;
  gap: var(--space-s, 8px);
  padding: var(--space-s, 8px) var(--space-m, 12px);
  background: var(--color-surface-alt, #f8fafc);
  border-radius: var(--radius-lg, 8px);
}

.engraving-label {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
  flex-shrink: 0;
}

.engraving-text {
  font-size: 13px;
  color: var(--color-text-primary, #111827);
  font-family: var(--font-mono, ui-monospace, monospace);
  white-space: pre-wrap;
  word-break: break-word;
}

.engraving-text.empty {
  color: var(--color-text-tertiary, #9ca3af);
  font-style: italic;
}

/* ====== 空状态 ====== */
.empty-tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-xl, 32px);
  text-align: center;
  color: var(--color-text-tertiary, #9ca3af);
  gap: var(--space-s, 8px);
}

.empty-tab p {
  font-size: 14px;
  color: var(--color-text-secondary, #6b7280);
  margin: 0;
}

.empty-tab.error p {
  color: var(--color-danger, #ef4444);
}

.no-selection {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--color-text-tertiary, #9ca3af);
  gap: var(--space-s, 8px);
  padding: var(--space-2xl, 24px);
}

.no-selection-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin-bottom: var(--space-s, 8px);
  border-radius: var(--radius-3xl, 16px);
  background: var(--color-surface-alt, #f8fafc);
  color: var(--color-text-faint, #94a3b8);
}

.no-selection p {
  font-size: 15px;
  color: var(--color-text-secondary, #6b7280);
  margin: 0;
}

.empty-hint {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

/* ====== Tab 头部操作 ====== */
.tab-header-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-s, 8px);
  padding: 0 var(--space-2xs, 2px);
}

.tab-header-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary, #111827);
}

/* ====== 卡片删除按钮 ====== */
.card-delete-btn {
  position: absolute;
  top: 8px;
  left: 8px;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: rgba(239, 68, 68, 0.9);
  color: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0;
  transition:
    opacity 0.15s,
    background 0.15s;
  z-index: 10;
}

.variant-standard-card:hover .card-delete-btn {
  opacity: 1;
}

.card-delete-btn:hover {
  background: #ef4444;
}

/* ====== 添加规格对话框 ====== */
.add-variant-form,
.add-variant-dialog {
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
}

.field-hints,
.dialog-tip {
  font-size: 13px;
  color: var(--color-text-secondary, #6b7280);
}

.variant-option {
  display: flex;
  align-items: center;
  gap: var(--space-s, 8px);
  width: 100%;
}

.variant-option-name {
  flex: 1;
  font-size: 13px;
}

.variant-option-capacity {
  font-size: 12px;
  color: var(--color-primary, #0f766e);
  font-weight: 600;
  font-family: var(--font-mono, ui-monospace, monospace);
}
</style>
