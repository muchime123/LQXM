<template>
  <LqDialog
    :model-value="visible"
    width="min(1180px, 94vw)"
    title="客户区域分布"
    subtitle="按省/市查看客户分布，点选后可联动筛选下方客户列表"
    mode="detail"
    :show-footer="true"
    @close="requestClose"
  >
    <!-- 概览统计 -->
    <div class="dist-stats">
        <div class="dist-stat">
          <span class="dist-stat__label">客户总数</span>
          <strong>{{ data?.totalCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </div>
        <div class="dist-stat">
          <span class="dist-stat__label">大陆已定位</span>
          <strong>{{ data?.locatedCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </div>
        <div class="dist-stat">
          <span class="dist-stat__label">完整到市</span>
          <strong>{{ data?.cityLocatedCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </div>
        <div class="dist-stat">
          <span class="dist-stat__label">完整到区县</span>
          <strong>{{ data?.districtLocatedCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </div>
        <button
          type="button"
          class="dist-stat dist-stat--warn dist-stat--action"
          :disabled="(data?.partialRegionCount ?? 0) === 0"
          title="省份已明确，但市县缺失或地区信息需要复核"
          @click="applyPartialRegion"
        >
          <span class="dist-stat__label">待补市县</span>
          <strong>{{ data?.partialRegionCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </button>
        <div class="dist-stat">
          <span class="dist-stat__label">覆盖省份</span>
          <strong>{{ data?.provinceCount ?? '—' }}</strong>
          <span class="dist-stat__unit">个</span>
        </div>
        <button
          type="button"
          class="dist-stat dist-stat--warn dist-stat--action"
          :class="{ 'is-active': noRegionActive }"
          :disabled="noRegionCount === 0"
          :title="noRegionCount ? '点击查看省份为空或非官方省名（上不了地图）的客户' : ''"
          @click="toggleNoRegion"
        >
          <span class="dist-stat__label">未标注地区</span>
          <strong>{{ noRegionCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </button>
        <div class="dist-stat" :title="'已判定为境外的客户（不参与大陆省级分布与地区治理）'">
          <span class="dist-stat__label">境外客户</span>
          <strong>{{ data?.overseasCount ?? '—' }}</strong>
          <span class="dist-stat__unit">家</span>
        </div>
    </div>

    <div class="dist-controls">
      <span class="dist-controls__note">
        按客户主档所在地统计；客户总数 = 大陆已定位 + 未标注地区 + 境外客户。完整到市、完整到区县和待补市县为质量子集，不参与重复相加。
      </span>
    </div>

    <!-- 地图 + 排行 -->
    <div class="dist-main">
      <section class="dist-panel dist-panel--map">
        <header class="dist-panel__head">
          <nav v-if="districtDrill || drill" class="dist-crumbs" aria-label="地图层级导航">
            <button type="button" class="dist-crumbs__link" @click="backToNational">全国分布</button>
            <template v-if="districtDrill && districtDrill.province.name !== districtDrill.city.name">
              <span class="dist-crumbs__sep" aria-hidden="true">/</span>
              <button type="button" class="dist-crumbs__link" @click="backToCity">回到{{ drill!.province.name }}</button>
              <span class="dist-crumbs__sep" aria-hidden="true">/</span>
              <span class="dist-crumbs__current">{{ districtDrill.city.name }}</span>
            </template>
            <template v-else>
              <span class="dist-crumbs__sep" aria-hidden="true">/</span>
              <span class="dist-crumbs__current">
                {{ districtDrill ? districtDrill.city.name : drill!.province.name }}
              </span>
            </template>
          </nav>
          <template v-else>
            <span class="dist-panel__title">全国分布</span>
          </template>
          <span v-if="districtDrill" class="dist-panel__hint">点击区县可选中筛选该区县客户</span>
          <span v-else-if="drill" class="dist-panel__hint">{{ cityMapHint }}</span>
          <span v-else class="dist-panel__hint">{{ mapHint }}</span>
        </header>
        <div class="dist-map" aria-label="客户省份分布地图">
          <div ref="mapElRef" class="dist-map__chart"></div>
          <div v-if="mapLoading" class="dist-map__overlay">
            <span class="dist-map__spinner" aria-hidden="true"></span>
            <span>地图加载中...</span>
          </div>
          <div v-else-if="mapError" class="dist-map__overlay">
            <span class="dist-map__error">地图加载失败，可点击重试</span>
            <button type="button" class="dist-map__retry" @click="retryInit">重试</button>
          </div>
          <div v-else-if="!hasLocated" class="dist-map__overlay">
            <span>暂无可展示的已标注地区客户</span>
          </div>
        </div>
        <footer class="dist-map__legend">
          <template v-if="drill">
            <span class="dist-map__legend-label">客户较少</span>
            <span class="dist-map__legend-bar" aria-hidden="true"></span>
            <span class="dist-map__legend-label">客户较多</span>
          </template>
          <template v-else>
            <span class="dist-map__legend-label">客户较少</span>
            <span class="dist-map__legend-bar" aria-hidden="true"></span>
            <span class="dist-map__legend-label">客户较多</span>
          </template>
        </footer>
      </section>

      <section class="dist-panel dist-panel--rank">
        <header class="dist-panel__head">
          <template v-if="districtDrill">
            <span class="dist-panel__title">{{ districtRankTitle }}</span>
            <span v-if="districtDrill.unmatched.length" class="dist-panel__hint">
              另有 {{ districtDrill.unmatched.length }} 个区县写法 ·
              {{ unmatchedCustomerCount(districtDrill.unmatched) }} 家未匹配地图分区，点击排行行仍可筛选
            </span>
          </template>
          <template v-else-if="drill">
            <span class="dist-panel__title">{{ drill.province.name }} · 城市分布</span>
            <span v-if="drill.unmatched.length" class="dist-panel__hint">
              另有 {{ drill.unmatched.length }} 个城市写法 ·
              {{ unmatchedCustomerCount(drill.unmatched) }} 家未匹配地图分区，点击排行行仍可筛选
            </span>
          </template>
          <template v-else>
            <span class="dist-panel__title">{{ rankTitle }}</span>
            <span v-if="data?.provinces.length" class="dist-panel__hint">
              共 {{ data.provinceCount }} 省，点击行展开市内分布
            </span>
          </template>
        </header>

        <div class="dist-rank">
          <!-- 全国：省份排行 -->
          <template v-if="!drill">
            <div
              v-if="!data && loading"
              class="dist-rank__empty"
            >
              正在统计...
            </div>
            <div v-else-if="rows.length === 0" class="dist-rank__empty">
              {{ emptyRankText }}
            </div>
            <template v-else>
              <div v-for="(p, index) in rows" :key="p.name" class="dist-rank__item">
                <button
                  type="button"
                  class="dist-rank__row"
                  :class="{ 'is-active': isNameSelected(p.name) }"
                  @click="handleRankRowClick(p)"
                >
                  <span class="dist-rank__no">{{ String(index + 1).padStart(2, '0') }}</span>
                  <span class="dist-rank__meta">
                    <span class="dist-rank__name">
                      {{ p.name }}
                      <span v-if="p.archive!.cities.length" class="dist-rank__city-count">
                        {{ p.archive!.cities.length }} 市
                      </span>
                    </span>
                    <span class="dist-rank__bar-track">
                      <span
                        class="dist-rank__bar"
                        :style="{ width: rowBarWidth(p) }"
                      />
                    </span>
                  </span>
                  <span class="dist-rank__count">{{ rowCountText(p) }}</span>
                  <span
                    class="dist-rank__caret"
                    :class="{ 'is-open': isNameSelected(p.name) }"
                    aria-hidden="true"
                  >
                    ▾
                  </span>
                </button>

                <!-- 客户档案视图：选中省后展开市内分布 -->
                <div v-if="isNameSelected(p.name)" class="dist-rank__cities">
                  <span v-if="p.archive!.unassignedCityCount" class="dist-rank__cities-empty">
                    尚未细分到市 {{ p.archive!.unassignedCityCount }} 家
                  </span>
                  <span v-if="p.archive!.cities.length === 0" class="dist-rank__cities-empty">该省客户暂未细分到市</span>
                  <template v-else>
                    <button
                      v-for="c in p.archive!.cities"
                      :key="c.name"
                      type="button"
                      class="dist-rank__city"
                      :class="{ 'is-active': isCitySelected(p.archive!, c) }"
                      @click="selectCity(p.archive!, c)"
                    >
                      <span class="dist-rank__city-name">{{ c.name }}</span>
                      <span class="dist-rank__city-count">{{ c.count }} 家</span>
                    </button>
                  </template>
                </div>
              </div>
            </template>
          </template>

          <!-- 下钻：某市区县分布 -->
          <template v-else-if="districtDrill">
            <div v-if="districtDrill.city.unassignedDistrictCount" class="dist-rank__empty">
              尚未细分到区县 {{ districtDrill.city.unassignedDistrictCount }} 家
            </div>
            <div v-for="(d, index) in districtDrill.city.districts" :key="d.name" class="dist-rank__item">
              <button
                type="button"
                class="dist-rank__row"
                :class="{ 'is-active': selectedDistrictName === d.name }"
                :title="isDistrictUnmatched(d) ? '该区县写法未匹配到地图分区，仍可选中后筛选该区县客户' : ''"
                @click="toggleDistrict(d)"
              >
                <span class="dist-rank__no">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="dist-rank__meta">
                  <span class="dist-rank__name">
                    {{ d.name }}
                    <span v-if="isDistrictUnmatched(d)" class="dist-rank__tag">未收录</span>
                  </span>
                  <span class="dist-rank__bar-track">
                    <span class="dist-rank__bar" :style="{ width: districtBarWidth(d.count) }" />
                  </span>
                </span>
                <span class="dist-rank__count">{{ d.count }}</span>
              </button>
            </div>
            <div v-if="districtDrill.city.districts.length === 0" class="dist-rank__empty">
              该市客户暂未细分到区县，已为你选中整个城市
            </div>
          </template>
          <!-- 下钻：某省城市分布 -->
          <template v-else>
            <div v-if="drill.province.unassignedCityCount" class="dist-rank__empty">
              尚未细分到市 {{ drill.province.unassignedCityCount }} 家
            </div>
            <div v-for="(c, index) in drill.cities" :key="c.name" class="dist-rank__item">
              <button
                type="button"
                class="dist-rank__row"
                :class="{ 'is-active': isDrillCitySelected(c) }"
                :title="isDrillCityUnmatched(c) ? '该城市写法未匹配到地图分区，仍可选中后筛选该市客户' : ''"
                @click="drillToCity(c)"
              >
                <span class="dist-rank__no">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="dist-rank__meta">
                  <span class="dist-rank__name">
                    {{ c.name }}
                    <span v-if="isDrillCityUnmatched(c)" class="dist-rank__tag">未收录</span>
                  </span>
                  <span class="dist-rank__bar-track">
                    <span class="dist-rank__bar" :style="{ width: drillCityBarWidth(c.count) }" />
                  </span>
                </span>
                <span class="dist-rank__count">{{ c.count }}</span>
              </button>
            </div>
            <div v-if="drill.cities.length === 0" class="dist-rank__empty">该省客户暂未细分到市</div>
          </template>
        </div>
      </section>
    </div>

    <template #footer>
      <span class="footer-status">
        <template v-if="selectionText">
          已选择 <strong>{{ selectionText }}</strong>
        </template>
        <template v-else>从地图或右侧排行中选择省份，即可筛选客户列表</template>
      </span>
      <button
        v-if="selectedProvince || noRegionActive || selectedName"
        type="button"
        class="dist-foot__clear"
        @click="clearSelection"
      >
        清除选择
      </button>
      <button
        v-if="aliasSeedForSelected"
        type="button"
        class="dist-foot__gap"
        title="把该「未收录」写法登记为官方行政区写法别名，登记后分布与写入口自动按别名归一"
        @click="openAliasForSeed"
      >
        登记「{{ aliasSeedForSelected.alias }}」为别名
      </button>
      <button
        type="button"
        class="dist-foot__gap"
        :disabled="!canExportFilter || exporting"
        @click="exportFilterCsv"
      >
        {{ exporting ? '导出中…' : '导出清单 CSV' }}
      </button>
      <button
        type="button"
        class="dist-foot__gap"
        :disabled="importLoading || !canManage"
        :title="canManage ? '选择由本页面导出的 CSV，预览并写回省市区县' : '需要客户管理权限'"
        @click="chooseImportFile"
      >
        {{ importLoading ? '解析中…' : '导入整理结果' }}
      </button>
      <button
        type="button"
        class="dist-foot__gap"
        :disabled="autoParseLoading || !canManage"
        :title="canManage ? '扫描未标注客户，仅写回通过省市区县三级校验的地址解析结果' : '需要客户管理权限'"
        @click="previewAutoParse"
      >
        {{ autoParseLoading ? '扫描中…' : '批量解析地址' }}
      </button>
      <button
        type="button"
        class="dist-foot__gap"
        :disabled="fallbackLoading || !canManage"
        :title="canManage ? '对没有收货地址的客户（如全部自提出库），按出库单「地区」标注回填省份' : '需要客户管理权限'"
        @click="previewFallback"
      >
        {{ fallbackLoading ? '扫描中…' : '按出库单回填省份' }}
      </button>
      <button
        type="button"
        class="dist-foot__gap"
        :disabled="overseasLoading || !canManage"
        :title="canManage ? '出库单地区标注为「国外」的客户：逐条判定真境外 / 国内转发' : '需要客户管理权限'"
        @click="previewOverseas"
      >
        {{ overseasLoading ? '扫描中…' : '境外客户处理' }}
      </button>
      <input ref="importInput" type="file" accept=".csv,.xlsx,.xls,text/csv" hidden @change="handleImportFile" />
      <button type="button" class="dist-foot__gap" @click="openAliasManager">写法别名管理</button>
      <button type="button" @click="requestClose">关闭</button>
      <button
        type="button"
        class="primary"
        :disabled="(!selectedProvince && !noRegionActive) || applying"
        @click="applyFilter"
      >
        {{ ctaText }}
      </button>
    </template>
  </LqDialog>

  <el-dialog v-model="importVisible" title="导入客户地区整理结果" width="900px" top="6vh" destroy-on-close>
    <el-alert type="info" :closable="false" title="按客户编号匹配。只写入行政区划校验通过的行；错误行不会修改客户档案。" />
    <div class="batch-summary" style="margin: 14px 0">
      共 <b>{{ importPreview?.total ?? 0 }}</b> 行，
      可更新 <b>{{ importPreview?.valid ?? 0 }}</b> 行，
      无变化 <b>{{ importPreview?.unchanged ?? 0 }}</b> 行，
      错误 <b>{{ importPreview?.errors ?? 0 }}</b> 行
    </div>
    <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 12px">
      <span style="color: var(--color-text-secondary); font-size: 13px">结果筛选</span>
      <el-radio-group v-model="importStatusFilter" size="small">
        <el-radio-button value="ALL">全部 {{ importPreview?.total ?? 0 }}</el-radio-button>
        <el-radio-button value="VALID">可更新 {{ importPreview?.valid ?? 0 }}</el-radio-button>
        <el-radio-button value="UNCHANGED">无变化 {{ importPreview?.unchanged ?? 0 }}</el-radio-button>
        <el-radio-button value="ERROR">错误 {{ importPreview?.errors ?? 0 }}</el-radio-button>
      </el-radio-group>
    </div>
    <el-table :data="filteredImportItems" max-height="480" size="small" empty-text="当前状态下没有数据">
      <el-table-column prop="rowNumber" label="行" width="60" />
      <el-table-column prop="customerCode" label="客户编号" width="145" />
      <el-table-column prop="customerName" label="客户名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="省 / 市 / 区县" min-width="250" show-overflow-tooltip>
        <template #default="{ row }">{{ [row.province, row.city, row.district].filter(Boolean).join(' / ') || '—' }}</template>
      </el-table-column>
      <el-table-column label="结果" width="100">
        <template #default="{ row }"><el-tag :type="row.status === 'VALID' ? 'success' : row.status === 'ERROR' ? 'danger' : 'info'" effect="plain">{{ row.status === 'VALID' ? '可更新' : row.status === 'ERROR' ? '错误' : '无变化' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="180" />
    </el-table>
    <template #footer>
      <el-button :disabled="!importPreview?.errors" @click="exportRegionImportErrors">导出错误报告 CSV</el-button>
      <el-button @click="importVisible = false">取消</el-button>
      <el-button type="primary" :loading="importApplying" :disabled="!importPreview?.valid" @click="confirmRegionImport">确认写回 {{ importPreview?.valid ?? 0 }} 家</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="autoParseVisible" title="批量解析客户地址" width="1240px" top="6vh" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      title="先检查现有省市区县。只有字段不完整或不符合地址选择器，且详细地址可解析出完整、合法的三级行政区划时才会写回；有效字段冲突时不会覆盖。"
    />
    <div class="batch-summary" style="margin: 14px 0">
      待检查 <b>{{ autoParsePreview?.total ?? 0 }}</b> 家，
      可自动补全 <b>{{ autoParsePreview?.ready ?? 0 }}</b> 家，
      暂无法处理 <b>{{ autoParsePreview?.errors ?? 0 }}</b> 家
    </div>
    <el-radio-group v-model="autoParseStatusFilter" size="small" style="margin-bottom: 12px">
      <el-radio-button value="ALL">全部</el-radio-button>
      <el-radio-button value="READY">可补全</el-radio-button>
      <el-radio-button value="ERROR">无法处理</el-radio-button>
    </el-radio-group>
    <el-table :data="filteredAutoParseItems" max-height="480" size="small" empty-text="当前状态下没有数据">
      <el-table-column prop="customerCode" label="客户编号" width="135" />
      <el-table-column prop="customerName" label="客户名称" min-width="130" show-overflow-tooltip />
      <el-table-column label="客户地址" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ row.address || '—' }}</template>
      </el-table-column>
      <el-table-column label="当前省 / 市 / 区县" min-width="205" show-overflow-tooltip>
        <template #default="{ row }">{{ [row.currentProvince, row.currentCity, row.currentDistrict].filter(Boolean).join(' / ') || '未填写' }}</template>
      </el-table-column>
      <el-table-column label="解析结果" min-width="205" show-overflow-tooltip>
        <template #default="{ row }">{{ [row.province, row.city, row.district].filter(Boolean).join(' / ') || '未识别' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"><el-tag :type="row.status === 'READY' ? 'success' : 'warning'" effect="plain">{{ row.status === 'READY' ? '可补全' : '未处理' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="180" show-overflow-tooltip />
    </el-table>
    <template #footer>
      <el-button @click="autoParseVisible = false">取消</el-button>
      <el-button type="primary" :loading="autoParseApplying" :disabled="!autoParsePreview?.ready" @click="confirmAutoParse">
        确认解析并写回 {{ autoParsePreview?.ready ?? 0 }} 家
      </el-button>
    </template>
  </el-dialog>

  <CustomerRegionAliasModal
    :visible="aliasModalVisible"
    :seed="aliasSeed"
    @close="aliasModalVisible = false"
    @changed="handleAliasChanged"
  />

  <el-dialog v-model="fallbackVisible" title="按出库单回填省份（无地址客户）" width="1100px" top="6vh" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      title="针对详细地址为空、省份未标注的客户（典型：出库单运输方式全为自提，没有收货地址）。按其出库单「地区」标注回填省份；地区标注是省级粒度，市/区县留待人工补录。多省份冲突与无法识别的写法不会写回。"
    />
    <div class="batch-summary" style="margin: 14px 0">
      待处理 <b>{{ fallbackPreview?.total ?? 0 }}</b> 家，
      可回填 <b>{{ fallbackPreview?.ready ?? 0 }}</b> 家，
      暂无法处理 <b>{{ fallbackPreview?.errors ?? 0 }}</b> 家
    </div>
    <el-radio-group v-model="fallbackStatusFilter" size="small" style="margin-bottom: 12px">
      <el-radio-button value="ALL">全部</el-radio-button>
      <el-radio-button value="READY">可回填</el-radio-button>
      <el-radio-button value="ERROR">无法处理</el-radio-button>
    </el-radio-group>
    <el-table :data="filteredFallbackItems" max-height="480" size="small" empty-text="当前状态下没有数据">
      <el-table-column prop="customerCode" label="客户编号" width="135" />
      <el-table-column prop="customerName" label="客户名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="出库单地区" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.outboundRegions || '—' }}</template>
      </el-table-column>
      <el-table-column label="回填省份" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.province || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"><el-tag :type="row.status === 'READY' ? 'success' : 'warning'" effect="plain">{{ row.status === 'READY' ? '可回填' : '未处理' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="200" show-overflow-tooltip />
    </el-table>
    <template #footer>
      <el-button @click="fallbackVisible = false">取消</el-button>
      <el-button type="primary" :loading="fallbackApplying" :disabled="!fallbackPreview?.ready" @click="confirmFallback">
        确认回填 {{ fallbackPreview?.ready ?? 0 }} 家
      </el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="overseasVisible" title="境外客户处理（出库单地区＝国外）" width="1180px" top="6vh" destroy-on-close>
    <el-alert
      type="info"
      :closable="false"
      title="这类客户的出库单地区标注为「国外」，形态混杂：可能是真实外贸客户（外文地址、境外电话），也可能是国内经销商转发到港澳台/海外。请逐条判定：真境外→省份标为「境外」（不再参与大陆地区治理）；国内转发→按档案地址回填省市区；保持待定→暂不处理。"
    />
    <div class="batch-summary" style="margin: 14px 0">
      待判定 <b>{{ overseasPreview?.total ?? 0 }}</b> 家，
      建议境外 <b>{{ overseasPreview?.overseas ?? 0 }}</b> 家，
      地址可解析（疑似国内转发） <b>{{ overseasPreview?.domestic ?? 0 }}</b> 家，
      已选择 <b>{{ overseasChoiceCount }}</b> 家
    </div>
    <el-table :data="overseasPreview?.items ?? []" max-height="440" size="small" empty-text="没有待处理的国外地区客户">
      <el-table-column prop="customerCode" label="客户编号" width="120" />
      <el-table-column prop="customerName" label="客户名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="档案地址" min-width="230" show-overflow-tooltip>
        <template #default="{ row }">{{ row.address || '（无地址）' }}</template>
      </el-table-column>
      <el-table-column label="出库单地区" width="95" show-overflow-tooltip>
        <template #default="{ row }">{{ row.outboundRegions || '—' }}</template>
      </el-table-column>
      <el-table-column label="经销商 / 电话" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ [row.dealerNames, row.contactPhone].filter(Boolean).join(' / ') || '—' }}</template>
      </el-table-column>
      <el-table-column label="单数" width="60" prop="orderCount" />
      <el-table-column label="判定" width="230">
        <template #default="{ row }">
          <el-radio-group
            :model-value="overseasChoices[row.id]"
            size="small"
            @update:model-value="(v: any) => setOverseasChoice(row.id, v)"
          >
            <el-radio-button value="OVERSEAS">标记境外</el-radio-button>
            <el-radio-button value="DOMESTIC" :disabled="!row.suggestedProvince">按地址回填</el-radio-button>
            <el-radio-button value="PENDING">保持待定</el-radio-button>
          </el-radio-group>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="210" show-overflow-tooltip />
    </el-table>
    <template #footer>
      <el-button @click="overseasVisible = false">取消</el-button>
      <el-button type="primary" :loading="overseasApplying" @click="confirmOverseas">
        确认写回 {{ overseasChoiceCount }} 家
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { ECharts } from 'echarts'
import {
  applyCustomerRegionAutoParse,
  applyCustomerRegionCsv,
  applyCustomerRegionOutboundFallback,
  applyCustomerRegionOverseas,
  exportCustomerRegionCsv,
  fetchCustomerDistribution,
  previewCustomerRegionAutoParse,
  previewCustomerRegionCsv,
  previewCustomerRegionOutboundFallback,
  previewCustomerRegionOverseas,
  type CustomerRegionAutoParsePreview,
  type CustomerRegionFallbackPreview,
  type CustomerRegionImportPreview,
  type CustomerRegionOverseasPreview
} from '@/api/customer'
import type {
  CustomerDistributionCity,
  CustomerDistributionData,
  CustomerDistributionDistrict,
  CustomerDistributionProvince,
  CustomerRegionFilter
} from '@/types/customer'
import { notify } from '@/composables/ui/useToast'
import LqDialog from '../common/LqDialog.vue'
import CustomerRegionAliasModal from './CustomerRegionAliasModal.vue'

/** 地区缺口治理：由「未收录」写法快速登记为字典别名 */
interface RegionAliasSeed {
  kind: 'CITY' | 'DISTRICT'
  alias: string
  province: string | null
}

const props = withDefaults(
  defineProps<{
    visible: boolean
    /** 当前客户列表已生效的地区筛选（用于打开时预选中） */
    activeRegion?: CustomerRegionFilter | null
    canManage?: boolean
  }>(),
  { activeRegion: null, canManage: false }
)

const emit = defineEmits<{
  close: []
  apply: [region: CustomerRegionFilter]
}>()

// ==================== 数据 ====================

const data = ref<CustomerDistributionData | null>(null)
const loading = ref(false)
const importInput = ref<HTMLInputElement>()
const importFile = ref<File>()
const importVisible = ref(false)
const importLoading = ref(false)
const importApplying = ref(false)
const importPreview = ref<CustomerRegionImportPreview>()
type ImportStatusFilter = 'ALL' | 'VALID' | 'UNCHANGED' | 'ERROR'
const importStatusFilter = ref<ImportStatusFilter>('ALL')
const filteredImportItems = computed(() => {
  const items = importPreview.value?.items ?? []
  return importStatusFilter.value === 'ALL'
    ? items
    : items.filter(item => item.status === importStatusFilter.value)
})
const autoParseVisible = ref(false)
const autoParseLoading = ref(false)
const autoParseApplying = ref(false)
const autoParsePreview = ref<CustomerRegionAutoParsePreview>()
type AutoParseStatusFilter = 'ALL' | 'READY' | 'ERROR'
const autoParseStatusFilter = ref<AutoParseStatusFilter>('ALL')
const filteredAutoParseItems = computed(() => {
  const items = autoParsePreview.value?.items ?? []
  return autoParseStatusFilter.value === 'ALL'
    ? items
    : items.filter(item => item.status === autoParseStatusFilter.value)
})
const fallbackVisible = ref(false)
const fallbackLoading = ref(false)
const fallbackApplying = ref(false)
const fallbackPreview = ref<CustomerRegionFallbackPreview>()
type FallbackStatusFilter = 'ALL' | 'READY' | 'ERROR'
const fallbackStatusFilter = ref<FallbackStatusFilter>('ALL')
const filteredFallbackItems = computed(() => {
  const items = fallbackPreview.value?.items ?? []
  return fallbackStatusFilter.value === 'ALL'
    ? items
    : items.filter(item => item.status === fallbackStatusFilter.value)
})
const overseasVisible = ref(false)
const overseasLoading = ref(false)
const overseasApplying = ref(false)
const overseasPreview = ref<CustomerRegionOverseasPreview>()
/** 逐条人工判定：customerId → OVERSEAS（真境外）/ DOMESTIC（按地址回填）/ PENDING（保持待定） */
const overseasChoices = ref<Record<number, 'OVERSEAS' | 'DOMESTIC' | 'PENDING'>>({})

function setOverseasChoice(customerId: number, choice: 'OVERSEAS' | 'DOMESTIC' | 'PENDING') {
  overseasChoices.value = { ...overseasChoices.value, [customerId]: choice }
}

const overseasChoiceCount = computed(() =>
  Object.values(overseasChoices.value).filter(v => v === 'OVERSEAS' || v === 'DOMESTIC').length
)
const provinces = computed(() => data.value?.provinces ?? [])

// ==================== 客户档案分布 ====================

interface DistRankRow {
  name: string
  archive: CustomerDistributionProvince
}

const rows = computed<DistRankRow[]>(() =>
  provinces.value.map(p => ({ name: p.name, archive: p }))
)
const maxRowMetric = computed(() => Math.max(...rows.value.map(r => r.archive.count), 1))

function rowBarWidth(r: DistRankRow): string {
  const v = r.archive.count
  if (v <= 0) return '0%'
  return `${Math.max(2, Math.round((v / maxRowMetric.value) * 100))}%`
}

function rowCountText(r: DistRankRow): string {
  return String(r.archive.count)
}

const mapHint = computed(() => '点击省份下钻查看市级分布')
const cityMapHint = computed(() => '点击城市进入区县分布')
const districtRankTitle = computed(() => {
  const ds = districtDrill.value
  if (!ds) return ''
  return ds.province.name === ds.city.name
    ? `${ds.city.name} · 区县分布`
    : `${ds.province.name} · ${ds.city.name} · 区县分布`
})
const rankTitle = computed(() => '省份排行（客户数）')
const emptyRankText = computed(() =>
  data.value && data.value.totalCount === 0
    ? '暂无客户档案'
    : '客户档案均未标注省份，可在客户表单补充地区'
)
async function loadDistribution() {
  loading.value = true
  try {
    data.value = await fetchCustomerDistribution()
    await renderMap()
  } catch (err) {
    console.warn('加载客户区域分布失败', err)
    notify.errorWithDetail('加载客户区域分布失败', err)
  } finally {
    loading.value = false
  }
}

// ==================== ECharts 地图 ====================

const mapElRef = ref<HTMLElement | null>(null)
let chart: ECharts | null = null
let mapFeatureNames: string[] = []
let echartsModulePromise: Promise<typeof import('echarts')> | null = null
let chinaGeoPromise: Promise<unknown> | null = null
const mapBaseUrl = `${import.meta.env.BASE_URL}map/china.json`
/** 省级市域 GeoJSON（DataV areas_v3 版式，与 china.json 同源，存放于 public/map/provinces/{adcode}.json） */
const provinceGeoBaseUrl = `${import.meta.env.BASE_URL}map/provinces/`
/** 全国地图省级 feature 名 → adcode，用于点击省份后加载其市域 GeoJSON */
const provinceAdcodes = new Map<string, number>()
const provinceGeoCache = new Map<number, Promise<unknown>>()

const mapLoading = ref(false)
const mapError = ref(false)

const hasLocated = computed(() => (data.value?.locatedCount ?? 0) > 0)
const noRegionCount = computed(() => data.value?.noRegionCount ?? 0)

function loadEchartsModule() {
  echartsModulePromise ||= import('echarts')
  return echartsModulePromise
}

function loadChinaGeo() {
  chinaGeoPromise ||= (async () => {
    const res = await fetch(mapBaseUrl)
    if (!res.ok) throw new Error(`地图资源加载失败：HTTP ${res.status}`)
    const geo = await res.json()
    mapFeatureNames = (geo.features || [])
      .map((f: { properties?: { name?: string } }) => f.properties?.name)
      .filter(Boolean)
    provinceAdcodes.clear()
    for (const f of geo.features ?? []) {
      const props = f.properties as { name?: string; level?: string; adcode?: number } | undefined
      if (props?.name && props.level === 'province' && typeof props.adcode === 'number') {
        provinceAdcodes.set(props.name, props.adcode)
      }
    }
    const echarts = await loadEchartsModule()
    echarts.registerMap('china', geo)
    return geo
  })()
  return chinaGeoPromise
}

/** 加载某省市级 GeoJSON 并注册为 province-{adcode} 地图（带缓存） */
function loadProvinceGeo(adcode: number): Promise<unknown> {
  let promise = provinceGeoCache.get(adcode)
  if (!promise) {
    promise = (async () => {
      const res = await fetch(`${provinceGeoBaseUrl}${adcode}.json`)
      if (!res.ok) throw new Error(`省级地图资源加载失败：HTTP ${res.status}`)
      const geo = await res.json()
      const echarts = await loadEchartsModule()
      echarts.registerMap(`province-${adcode}`, geo)
      return geo
    })()
    provinceGeoCache.set(adcode, promise)
  }
  return promise
}

/** 区县 GeoJSON：public/map/cities/{adcode}.json，注册为 city-{adcode}（带缓存）。
 *  直辖市城市=省，无独立地级文件，直接复用已注册的省级（区县）边界。 */
const cityGeoBaseUrl = `${import.meta.env.BASE_URL}map/cities/`
const cityGeoCache = new Map<number, Promise<unknown>>()

function loadCityGeo(cityAdcode: number, provinceAdcode: number): Promise<unknown> {
  if (cityAdcode === provinceAdcode) return loadProvinceGeo(provinceAdcode)
  let promise = cityGeoCache.get(cityAdcode)
  if (!promise) {
    promise = (async () => {
      const res = await fetch(`${cityGeoBaseUrl}${cityAdcode}.json`)
      if (!res.ok) throw new Error(`区县地图资源加载失败：HTTP ${res.status}`)
      const geo = await res.json()
      const echarts = await loadEchartsModule()
      echarts.registerMap(`city-${cityAdcode}`, geo)
      return geo
    })()
    cityGeoCache.set(cityAdcode, promise)
  }
  return promise
}

/** 客户数主色调渐变：0 客户为浅灰，按数量向品牌青绿加深 */
function regionFill(count: number, max: number): string {
  if (count <= 0) return '#eef1f6'
  const t = Math.min(1, count / Math.max(max, 1))
  return `hsl(172, ${Math.round(55 + 25 * t)}%, ${Math.round(92 - 40 * t)}%)`
}

function renderMapOption() {
  const currentRows = rows.value
  const rowByName = new Map(currentRows.map(r => [r.name, r]))
  const max = maxRowMetric.value
  const selected = selectedName.value

  const mapData = mapFeatureNames.map(name => {
    const row = rowByName.get(name)
    const metric = row?.archive.count ?? 0
    const isSelected = selected === name
    let areaColor = '#eef1f6'
    if (row) areaColor = regionFill(metric, max)
    return {
      name,
      value: metric,
      itemStyle: {
        areaColor: isSelected ? '#0f766e' : areaColor,
        borderColor: '#ffffff',
        borderWidth: isSelected ? 1.5 : 0.8
      },
      label: isSelected ? { show: true, color: '#ffffff', fontSize: 11, fontWeight: 600 } : undefined
    }
  })

  const option = {
    backgroundColor: 'transparent',
    animation: false,
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: 'var(--color-border, #e2e8f0)',
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: '#0f172a', fontSize: 12 },
      formatter: (params: { name?: string; value?: unknown }) => {
        const name = params.name ?? ''
        const row = rowByName.get(name)
        const base = `<b>${name}</b><br/>`
        if (!row) {
          return `${base}客户数：<b>0</b> 家<br/><span style="color:#94a3b8">（暂无已标注客户）</span>`
        }
        const p = row.archive
        const canDrill = p.cities.length > 0 && provinceAdcodes.get(p.name) != null
        const unassigned = p.unassignedCityCount > 0
          ? `<br/><span style="color:#d97706">其中 ${p.unassignedCityCount} 家尚未细分到市</span>` : ''
        const hint = canDrill ? '<br/><span style="color:#0f766e">点击省份下钻查看市级分布</span>' : ''
        return `${base}客户数：<b>${p.count}</b> 家${unassigned}${hint}`
      }
    },
    series: [
      {
        type: 'map',
        map: 'china',
        roam: false,
        layoutCenter: ['50%', '50%'],
        layoutSize: '92%',
        itemStyle: { areaColor: '#eef1f6', borderColor: '#ffffff', borderWidth: 0.8 },
        emphasis: {
          label: { show: true, color: '#0f172a', fontSize: 11, fontWeight: 600 },
          itemStyle: { areaColor: '#14b8a6' }
        },
        select: {
          label: { show: true, color: '#ffffff', fontSize: 11, fontWeight: 600 },
          itemStyle: { areaColor: '#0f766e' }
        },
        data: mapData
      }
    ]
  }
  chart?.setOption(option)
}

async function initChart() {
  if (!props.visible || !mapElRef.value) return
  if (mapLoading.value) return
  mapLoading.value = true
  mapError.value = false
  try {
    const echarts = await loadEchartsModule()
    await loadChinaGeo()
    // el-dialog 内容挂载后尺寸才可用，必要时等一帧
    await nextTick()
    // 弹窗可能已关闭 / 容器可能被 Vue 更新摘除，重新校验后再初始化
    if (!props.visible || !mapElRef.value || !mapElRef.value.isConnected) return
    chart?.dispose()
    chart = echarts.init(mapElRef.value)
    chart.on('click', params => {
      const name = (params as { name?: string }).name
      if (!name) return
      if (districtDrill.value) {
        handleDistrictMapClick(name)
        return
      }
      if (drill.value) {
        handleDrillMapClick(name)
        return
      }
      const row = rows.value.find(r => r.name === name)
      if (!row) return
      handleProvinceClick(row.archive)
    })
    renderMapOption()
  } catch (err) {
    console.warn('中国地图初始化失败', err)
    mapError.value = true
  } finally {
    mapLoading.value = false
  }
}

async function renderMap() {
  if (!chart) {
    await initChart()
    return
  }
  renderMapOption()
}

function retryInit() {
  chinaGeoPromise = null
  echartsModulePromise = null
  initChart()
}

// ==================== 省级下钻（全国 → 市级分布） ====================

interface DrillUnmatchedCity {
  name: string
  count: number
}

interface DrillState {
  province: CustomerDistributionProvince
  adcode: number
  cities: CustomerDistributionCity[]
  /** 市级 GeoJSON feature 名（按数据顺序，用于 map data 逐项着色） */
  featureNames: string[]
  /** feature 名 → 市级 adcode（下钻区县地图用） */
  featureAdcodes: Map<string, number>
  /** feature 名 → 客户数（同一城市多种写法会汇总） */
  featureCounts: Map<string, number>
  /** 原始城市写法 → 匹配到的 feature 名 */
  rawToFeature: Map<string, string>
  /** feature 名 → 用于筛选的原始城市写法 */
  featureToRaw: Map<string, string>
  /** 客户档案里无法匹配到市级地图分区的城市写法 */
  unmatched: DrillUnmatchedCity[]
  /** 能落到地图分区的客户合计 */
  matchedTotal: number
}

const drill = ref<DrillState | null>(null)

/**
 * 档案写法与地图分区对齐：分布聚合已统一输出「区划字典规范全名」，
 * 此处按 feature 官方名做精确对齐即可（不再做行政/民族后缀的启发式猜测）；
 * 无法精确命中的写法视为未收录，保留可筛选并提示数量。
 */
interface ProvinceGeo {
  features?: Array<{ properties?: { name?: string; adcode?: number } }>
}

/** 未匹配到地图分区的写法累计客户数（用于排行头提示）。 */
function unmatchedCustomerCount(items: DrillUnmatchedCity[]): number {
  return items.reduce((sum, item) => sum + item.count, 0)
}

/** 把省级 GeoJSON 的市级分区与客户档案城市写法对齐（规范名精确匹配） */
function buildDrillState(province: CustomerDistributionProvince, adcode: number, geo: ProvinceGeo): DrillState {
  const featureNames: string[] = []
  const featureAdcodes = new Map<string, number>()
  const featureNameSet = new Set<string>()
  for (const f of geo.features ?? []) {
    const props = f.properties
    const name = props?.name
    if (!name) continue
    featureNames.push(name)
    featureNameSet.add(name)
    if (typeof props.adcode === 'number') featureAdcodes.set(name, props.adcode)
  }

  const rawToFeature = new Map<string, string>()
  const featureToRaw = new Map<string, string>()
  const featureCounts = new Map<string, number>()
  const unmatched: DrillUnmatchedCity[] = []
  let matchedTotal = 0

  for (const city of province.cities) {
    if (!featureNameSet.has(city.name)) {
      unmatched.push({ name: city.name, count: city.count })
      continue
    }
    const featureName = city.name
    rawToFeature.set(city.name, featureName)
    if (!featureToRaw.has(featureName)) featureToRaw.set(featureName, city.name)
    featureCounts.set(featureName, (featureCounts.get(featureName) ?? 0) + city.count)
    matchedTotal += city.count
  }

  return {
    province,
    adcode,
    cities: province.cities,
    featureNames,
    featureAdcodes,
    featureCounts,
    rawToFeature,
    featureToRaw,
    unmatched,
    matchedTotal
  }
}

/** 全国视图地图点击：可下钻的省份进入市级分布，否则退化为省份选中 */
async function handleProvinceClick(p: CustomerDistributionProvince) {
  const adcode = provinceAdcodes.get(p.name)
  if (adcode == null || p.cities.length === 0) {
    toggleProvince(p)
    return
  }
  await drillToProvince(p, adcode)
}

async function drillToProvince(p: CustomerDistributionProvince, adcode: number) {
  if (mapLoading.value) return
  mapLoading.value = true
  mapError.value = false
  try {
    const geo = (await loadProvinceGeo(adcode)) as ProvinceGeo
    if (!props.visible) return
    drill.value = buildDrillState(p, adcode, geo)
    selectedName.value = p.name
    selectedCityName.value = null
    selectedDistrictName.value = null
    districtDrill.value = null
    noRegionActive.value = false
    renderDrillMap()
    // 直辖市等「市=省」地区：城市写法与省全名一致（存储约定），省级 GeoJSON 即区县边界，
    // 进入省份后直接继续到区县分布
    const directCity = drill.value.cities.find(c => c.name === p.name && c.districts.length > 0)
    if (directCity) {
      mapLoading.value = false
      await drillToCity(directCity)
    }
  } catch (err) {
    console.warn(`加载「${p.name}」市级地图失败`, err)
    drill.value = null
    // 降级为省份选中，筛选功能不受影响
    toggleProvince(p)
  } finally {
    mapLoading.value = false
  }
}

/** 面包屑返回：回到全国分布 */
function backToNational() {
  if (!drill.value && !districtDrill.value) return
  drill.value = null
  districtDrill.value = null
  selectedDistrictName.value = null
  renderMapOption()
}

function renderDrillMap() {
  const s = drill.value
  if (!chart || !s) return
  const max = Math.max(...Array.from(s.featureCounts.values()), 1)
  const selectedFeature = selectedCityName.value ? s.rawToFeature.get(selectedCityName.value) : undefined
  const mapData = s.featureNames.map(name => {
    const count = s.featureCounts.get(name) ?? 0
    const isSelected = count > 0 && name === selectedFeature
    const baseLabel =
      count > 0 && s.featureNames.length <= 18 ? { show: true, color: '#475569', fontSize: 9 } : undefined
    return {
      name,
      value: count,
      itemStyle: {
        areaColor: isSelected ? '#0f766e' : regionFill(count, max),
        borderColor: '#ffffff',
        borderWidth: isSelected ? 1.5 : 0.8
      },
      label: isSelected
        ? { show: true, color: '#ffffff', fontSize: 10, fontWeight: 600 }
        : baseLabel
    }
  })

  const option = {
    backgroundColor: 'transparent',
    animation: false,
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: 'var(--color-border, #e2e8f0)',
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: '#0f172a', fontSize: 12 },
      formatter: (params: { name?: string; value?: unknown }) => {
        const count = typeof params.value === 'number' ? params.value : 0
        return `<b>${params.name ?? ''}</b><br/>客户数：<b>${count}</b> 家${count === 0 ? '<br/><span style="color:#94a3b8">（暂无客户）</span>' : ''}`
      }
    },
    series: [
      {
        type: 'map',
        map: `province-${s.adcode}`,
        roam: false,
        layoutCenter: ['50%', '50%'],
        layoutSize: '97%',
        itemStyle: { areaColor: '#eef1f6', borderColor: '#ffffff', borderWidth: 0.8 },
        emphasis: {
          label: { show: true, color: '#0f172a', fontSize: 11, fontWeight: 600 },
          itemStyle: { areaColor: '#14b8a6' }
        },
        data: mapData
      }
    ]
  }
  chart.setOption(option)
}

/** 下钻地图点击市级分区 → 进入该市区县下钻（无区县明细时退化为城市选中） */
function handleDrillMapClick(featureName: string) {
  const s = drill.value
  if (!s) return
  const raw = s.featureToRaw.get(featureName)
  if (!raw) return
  const city = s.cities.find(c => c.name === raw)
  if (city) drillToCity(city)
}

function selectDrillCity(c: CustomerDistributionCity) {
  if (!drill.value) return
  if (selectedCityName.value === c.name) {
    // 取消市级粒度，保留省份选中，便于整省筛选
    selectedCityName.value = null
  } else {
    selectedCityName.value = c.name
    noRegionActive.value = false
  }
  renderDrillMap()
}

function isDrillCitySelected(c: CustomerDistributionCity): boolean {
  return drill.value !== null && selectedCityName.value === c.name
}

function isDrillCityUnmatched(c: CustomerDistributionCity): boolean {
  return drill.value !== null && !drill.value.rawToFeature.has(c.name)
}

/** 下钻视图城市排行条宽度相对该省城市最大值的比例 */
function drillCityBarWidth(count: number): string {
  const cities = drill.value?.cities ?? []
  const max = Math.max(...cities.map(c => c.count), 1)
  return `${Math.round((count / max) * 100)}%`
}

// ==================== 区县下钻（省 → 市 → 区县） ====================

interface DistrictDrillState {
  province: CustomerDistributionProvince
  city: CustomerDistributionCity
  /** 城市 adcode；与 provinceAdcode 相同时表示直辖市（省文件即区县边界），复用已注册地图 */
  adcode: number
  provinceAdcode: number
  /** 区县 GeoJSON feature 名（按数据顺序） */
  featureNames: string[]
  /** feature 名 → 客户数 */
  featureCounts: Map<string, number>
  /** 原始区县写法 → 匹配到的 feature 名 */
  rawToFeature: Map<string, string>
  /** feature 名 → 用于筛选的原始区县写法 */
  featureToRaw: Map<string, string>
  /** 无法匹配到区县地图分区的原始区县写法 */
  unmatched: DrillUnmatchedCity[]
  /** 能落到区县地图分区的客户合计 */
  matchedTotal: number
}

const districtDrill = ref<DistrictDrillState | null>(null)
/** 当前选中的原始区县写法 */
const selectedDistrictName = ref<string | null>(null)

/** 用市级区县 GeoJSON 对齐客户档案原始区县写法（逻辑同 buildDrillState） */
function buildDistrictDrillState(
  province: CustomerDistributionProvince,
  city: CustomerDistributionCity,
  adcode: number,
  provinceAdcode: number,
  geo: ProvinceGeo
): DistrictDrillState {
  const featureNames: string[] = []
  const featureNameSet = new Set<string>()
  for (const f of geo.features ?? []) {
    const name = f.properties?.name
    if (!name) continue
    featureNames.push(name)
    featureNameSet.add(name)
  }
  const rawToFeature = new Map<string, string>()
  const featureToRaw = new Map<string, string>()
  const featureCounts = new Map<string, number>()
  const unmatched: DrillUnmatchedCity[] = []
  let matchedTotal = 0
  for (const d of city.districts) {
    if (!featureNameSet.has(d.name)) {
      unmatched.push({ name: d.name, count: d.count })
      continue
    }
    const featureName = d.name
    rawToFeature.set(d.name, featureName)
    if (!featureToRaw.has(featureName)) featureToRaw.set(featureName, d.name)
    featureCounts.set(featureName, (featureCounts.get(featureName) ?? 0) + d.count)
    matchedTotal += d.count
  }
  return {
    province,
    city,
    adcode,
    provinceAdcode,
    featureNames,
    featureCounts,
    rawToFeature,
    featureToRaw,
    unmatched,
    matchedTotal
  }
}

/** 城市下钻所需的 adcode：命中市级分区用其 adcode；直辖市（市=省全名）复用省级 adcode。 */
function resolveCityAdcode(c: CustomerDistributionCity): number | null {
  const s = drill.value
  if (!s) return null
  const feature = s.rawToFeature.get(c.name)
  if (feature) return s.featureAdcodes.get(feature) ?? null
  if (c.name === s.province.name) return s.adcode
  return null
}

/** 进入某市的区县分布：可下钻则展示第三层地图与区县排行，否则退化为城市选中 */
async function drillToCity(c: CustomerDistributionCity) {
  const s = drill.value
  if (!s || !props.visible || mapLoading.value) return
  if (!c.districts.length) {
    selectDrillCity(c)
    return
  }
  const cityAdcode = resolveCityAdcode(c)
  if (cityAdcode == null) {
    selectDrillCity(c)
    return
  }
  mapLoading.value = true
  try {
    const geo = (await loadCityGeo(cityAdcode, s.adcode)) as ProvinceGeo
    if (!props.visible || !drill.value) return
    const ds = buildDistrictDrillState(s.province, c, cityAdcode, s.adcode, geo)
    if (ds.featureNames.length === 0) {
      selectDrillCity(c)
      return
    }
    selectedCityName.value = c.name
    selectedDistrictName.value = null
    noRegionActive.value = false
    districtDrill.value = ds
    renderDistrictMap()
  } catch (err) {
    console.warn(`加载「${c.name}」区县地图失败`, err)
    districtDrill.value = null
    selectDrillCity(c)
  } finally {
    mapLoading.value = false
  }
}

/** 面包屑返回：区县 → 市级分布 */
function backToCity() {
  if (!districtDrill.value) return
  districtDrill.value = null
  selectedDistrictName.value = null
  renderDrillMap()
}

function renderDistrictMap() {
  const ds = districtDrill.value
  if (!chart || !ds) return
  const max = Math.max(...Array.from(ds.featureCounts.values()), 1)
  const selectedFeature = selectedDistrictName.value
    ? ds.rawToFeature.get(selectedDistrictName.value)
    : undefined
  const mapName = ds.adcode === ds.provinceAdcode ? `province-${ds.provinceAdcode}` : `city-${ds.adcode}`
  const mapData = ds.featureNames.map(name => {
    const count = ds.featureCounts.get(name) ?? 0
    const isSelected = count > 0 && name === selectedFeature
    const baseLabel =
      count > 0 && ds.featureNames.length <= 22 ? { show: true, color: '#475569', fontSize: 9 } : undefined
    return {
      name,
      value: count,
      itemStyle: {
        areaColor: isSelected ? '#0f766e' : regionFill(count, max),
        borderColor: '#ffffff',
        borderWidth: isSelected ? 1.5 : 0.8
      },
      label: isSelected
        ? { show: true, color: '#ffffff', fontSize: 10, fontWeight: 600 }
        : baseLabel
    }
  })
  chart.setOption({
    backgroundColor: 'transparent',
    animation: false,
    tooltip: {
      trigger: 'item',
      confine: true,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: 'var(--color-border, #e2e8f0)',
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: '#0f172a', fontSize: 12 },
      formatter: (params: { name?: string; value?: unknown }) => {
        const count = typeof params.value === 'number' ? params.value : 0
        return `<b>${params.name ?? ''}</b><br/>客户数：<b>${count}</b> 家${count === 0 ? '<br/><span style="color:#94a3b8">（暂无客户）</span>' : ''}`
      }
    },
    series: [
      {
        type: 'map',
        map: mapName,
        roam: false,
        layoutCenter: ['50%', '50%'],
        layoutSize: '97%',
        itemStyle: { areaColor: '#eef1f6', borderColor: '#ffffff', borderWidth: 0.8 },
        emphasis: {
          label: { show: true, color: '#0f172a', fontSize: 11, fontWeight: 600 },
          itemStyle: { areaColor: '#14b8a6' }
        },
        data: mapData
      }
    ]
  })
}

/** 区县地图点击分区 → 选中/取消该区县（取消后回到整市粒度） */
function handleDistrictMapClick(featureName: string) {
  const ds = districtDrill.value
  if (!ds) return
  const raw = ds.featureToRaw.get(featureName)
  if (!raw) return
  const d = ds.city.districts.find(x => x.name === raw)
  if (d) toggleDistrict(d)
}

function toggleDistrict(d: CustomerDistributionDistrict) {
  if (!districtDrill.value) return
  if (selectedDistrictName.value === d.name) {
    selectedDistrictName.value = null
  } else {
    selectedDistrictName.value = d.name
    noRegionActive.value = false
  }
  renderDistrictMap()
}

function isDistrictUnmatched(d: CustomerDistributionDistrict): boolean {
  return districtDrill.value !== null && !districtDrill.value.rawToFeature.has(d.name)
}

function districtBarWidth(count: number): string {
  const districts = districtDrill.value?.city.districts ?? []
  const max = Math.max(...districts.map(d => d.count), 1)
  return `${Math.round((count / max) * 100)}%`
}

// ==================== 选中与联动 ====================

const selectedName = ref<string | null>(null)
const selectedCityName = ref<string | null>(null)
const noRegionActive = ref(false)
const applying = ref(false)

const selectedProvince = computed(() =>
  provinces.value.find(p => p.name === selectedName.value) ?? null
)

const selectedCity = computed<CustomerDistributionCity | null>(() => {
  if (!selectedProvince.value || !selectedCityName.value) return null
  return selectedProvince.value.cities.find(c => c.name === selectedCityName.value) ?? null
})

const selectedDistrict = computed<CustomerDistributionDistrict | null>(() => {
  if (!selectedCity.value || !selectedDistrictName.value) return null
  return selectedCity.value.districts.find(d => d.name === selectedDistrictName.value) ?? null
})

function isProvinceSelected(p: CustomerDistributionProvince): boolean {
  return selectedName.value === p.name
}

function isNameSelected(name: string): boolean {
  return selectedName.value === name
}

/** 排行点击选中省份；地图点击负责进入市级下钻。 */
function handleRankRowClick(r: DistRankRow) {
  toggleProvince(r.archive)
}

function isCitySelected(p: CustomerDistributionProvince, c: CustomerDistributionCity): boolean {
  return isProvinceSelected(p) && selectedCityName.value === c.name
}

function toggleProvince(p: CustomerDistributionProvince) {
  if (isProvinceSelected(p) && !selectedCity.value && !selectedDistrict.value) {
    clearSelection()
    return
  }
  selectedName.value = p.name
  selectedCityName.value = null
  selectedDistrictName.value = null
  noRegionActive.value = false
  renderMapOption()
}

function selectCity(p: CustomerDistributionProvince, c: CustomerDistributionCity) {
  selectedName.value = p.name
  selectedCityName.value = c.name
  selectedDistrictName.value = null
  noRegionActive.value = false
  renderMapOption()
}

/** 点击「未标注地区」统计卡：再次点击取消选中 */
function toggleNoRegion() {
  if (drill.value) {
    // 下钻态下点击统计卡：先回到全国分布再切到未标注筛选
    drill.value = null
    districtDrill.value = null
    selectedName.value = null
    selectedCityName.value = null
    selectedDistrictName.value = null
    noRegionActive.value = true
    renderMapOption()
    return
  }
  if (noRegionActive.value) {
    clearSelection()
    return
  }
  noRegionActive.value = true
  selectedName.value = null
  selectedCityName.value = null
  selectedDistrictName.value = null
  renderMapOption()
}

function applyPartialRegion() {
  if ((data.value?.partialRegionCount ?? 0) > 0) emit('apply', { regionPartial: true })
}

function clearSelection() {
  drill.value = null
  districtDrill.value = null
  selectedName.value = null
  selectedCityName.value = null
  selectedDistrictName.value = null
  noRegionActive.value = false
  renderMapOption()
}

const selectionText = computed(() => {
  if (noRegionActive.value) return `未标注地区（${noRegionCount.value} 家）`
  const p = selectedProvince.value
  if (!p) {
    // 业务视图选中了档案内不存在的省份（仅有出库分布）：仍展示选择态便于清除
    if (selectedName.value) return `${selectedName.value}（客户档案无此省客户）`
    return ''
  }
  if (selectedCity.value) {
    if (selectedDistrict.value) {
      return `${p.name} · ${selectedCity.value.name} · ${selectedDistrict.value.name}（${selectedDistrict.value.count} 家）`
    }
    return `${p.name} · ${selectedCity.value.name}（${selectedCity.value.count} 家）`
  }
  return `${p.name}（${p.count} 家）`
})

const ctaText = computed(() => {
  if (noRegionActive.value) return '筛选未标注地区客户'
  const p = selectedProvince.value
  if (!p) return selectedName.value ? '该省暂无档案客户' : '查看该地区客户'
  if (selectedCity.value) {
    return selectedDistrict.value
      ? `筛选${p.name}·${selectedCity.value.name}·${selectedDistrict.value.name}`
      : `筛选${p.name}·${selectedCity.value.name}`
  }
  return `筛选${p.name}`
})

function applyFilter() {
  if ((!selectedProvince.value && !noRegionActive.value) || applying.value) return
  applying.value = true
  try {
    if (noRegionActive.value) {
      emit('apply', { noRegion: true })
      return
    }
    const p = selectedProvince.value!
    const city = selectedCity.value
    emit('apply', {
      province: p.filterValue || p.name,
      city: city?.name ?? null,
      district: selectedDistrict.value ? selectedDistrict.value.name : undefined
    })
  } finally {
    applying.value = false
  }
}

// ==================== 地区缺口治理（写法别名登记 + 待标注清单导出） ====================

const aliasModalVisible = ref(false)
const aliasSeed = ref<RegionAliasSeed | null>(null)
const exporting = ref(false)

/** 当前选中的「未收录」城市/区县写法 → 可一键登记为字典别名。 */
const aliasSeedForSelected = computed<RegionAliasSeed | null>(() => {
  const p = selectedProvince.value
  if (!p) return null
  if (selectedCity.value && isDrillCityUnmatched(selectedCity.value)) {
    return { kind: 'CITY', alias: selectedCity.value.name, province: p.name }
  }
  const d = selectedDistrict.value
  if (d && isDistrictUnmatched(d)) {
    return { kind: 'DISTRICT', alias: d.name, province: p.name }
  }
  return null
})

const canExportFilter = computed(() =>
  noRegionActive.value || Boolean(selectedName.value && selectedProvince.value)
)

function openAliasManager() {
  aliasSeed.value = null
  aliasModalVisible.value = true
}

function openAliasForSeed() {
  const seed = aliasSeedForSelected.value
  if (!seed) return
  aliasSeed.value = seed
  aliasModalVisible.value = true
}

/** 别名登记/删除后：回到全国视角重新聚合（桶名会随别名变化） */
async function handleAliasChanged() {
  clearSelection()
  await loadDistribution()
  renderMapOption()
  notify?.('地区分布已按最新写法别名刷新', 'success')
}

function exportFilterParams(): Record<string, unknown> {
  if (noRegionActive.value) return { noRegion: true }
  const p = selectedProvince.value
  if (!p) return {}
  const params: Record<string, unknown> = { province: p.filterValue || p.name }
  const city = selectedCity.value
  if (city) {
    params.city = city.name
    const d = selectedDistrict.value
    if (d) params.district = d.name
  }
  return params
}

async function exportFilterCsv() {
  if (!canExportFilter.value || exporting.value) return
  exporting.value = true
  try {
    const blob = await exportCustomerRegionCsv(exportFilterParams())
    const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '')
    const params = exportFilterParams()
    const label =
      String(params.noRegion === true ? '未标注地区' : params.district || params.city || params.province || '地区') ||
      '地区'
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `客户地区缺口_${label}_${stamp}.csv`
    a.click()
    URL.revokeObjectURL(url)
    notify?.('已导出「' + label + '」客户清单 CSV', 'success')
  } catch (err: any) {
    notify?.(err?.message || '导出失败', 'error')
  } finally {
    exporting.value = false
  }
}

function chooseImportFile() {
  if (importLoading.value) return
  if (importInput.value) importInput.value.value = ''
  importInput.value?.click()
}

async function handleImportFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  importLoading.value = true
  try {
    importFile.value = file
    importPreview.value = await previewCustomerRegionCsv(file)
    importStatusFilter.value = 'ALL'
    importVisible.value = true
  } catch (err: any) {
    notify.errorWithDetail('地区整理结果解析失败', err)
  } finally {
    importLoading.value = false
  }
}

async function confirmRegionImport() {
  if (!importFile.value || !importPreview.value?.valid) return
  importApplying.value = true
  try {
    const result = await applyCustomerRegionCsv(importFile.value)
    notify.success(`已更新 ${result.applied} 家客户的省市区县`)
    importVisible.value = false
    await loadDistribution()
    renderMapOption()
  } catch (err: any) {
    notify.errorWithDetail('客户地区写回失败', err)
  } finally {
    importApplying.value = false
  }
}

async function previewAutoParse() {
  if (autoParseLoading.value || !props.canManage) return
  autoParseLoading.value = true
  try {
    autoParsePreview.value = await previewCustomerRegionAutoParse()
    autoParseStatusFilter.value = 'ALL'
    autoParseVisible.value = true
  } catch (err: any) {
    notify.errorWithDetail('客户地址批量解析预览失败', err)
  } finally {
    autoParseLoading.value = false
  }
}

async function confirmAutoParse() {
  if (!autoParsePreview.value?.ready) return
  autoParseApplying.value = true
  try {
    const result = await applyCustomerRegionAutoParse()
    notify.success(`已解析并写回 ${result.applied} 家客户的省市区县`)
    autoParseVisible.value = false
    await loadDistribution()
    renderMapOption()
  } catch (err: any) {
    notify.errorWithDetail('客户地址批量解析写回失败', err)
  } finally {
    autoParseApplying.value = false
  }
}

async function previewFallback() {
  if (fallbackLoading.value || !props.canManage) return
  fallbackLoading.value = true
  try {
    fallbackPreview.value = await previewCustomerRegionOutboundFallback()
    fallbackStatusFilter.value = 'ALL'
    fallbackVisible.value = true
  } catch (err: any) {
    notify.errorWithDetail('出库单地区回填预览失败', err)
  } finally {
    fallbackLoading.value = false
  }
}

async function confirmFallback() {
  if (!fallbackPreview.value?.ready) return
  fallbackApplying.value = true
  try {
    const result = await applyCustomerRegionOutboundFallback()
    notify.success(`已按出库单地区写回 ${result.applied} 家客户的省份`)
    fallbackVisible.value = false
    await loadDistribution()
    renderMapOption()
  } catch (err: any) {
    notify.errorWithDetail('出库单地区回填写回失败', err)
  } finally {
    fallbackApplying.value = false
  }
}

async function previewOverseas() {
  if (overseasLoading.value || !props.canManage) return
  overseasLoading.value = true
  try {
    const preview = await previewCustomerRegionOverseas()
    overseasPreview.value = preview
    // 默认按建议预选，人工可逐条改
    overseasChoices.value = Object.fromEntries(
      preview.items.map(item => [item.id, item.suggested === 'DOMESTIC_FORWARD' ? 'DOMESTIC' : 'OVERSEAS'])
    ) as Record<number, 'OVERSEAS' | 'DOMESTIC' | 'PENDING'>
    overseasVisible.value = true
  } catch (err: any) {
    notify.errorWithDetail('境外客户判定预览失败', err)
  } finally {
    overseasLoading.value = false
  }
}

async function confirmOverseas() {
  const decisions = Object.entries(overseasChoices.value).map(([id, decision]) => ({
    customerId: Number(id),
    decision
  }))
  if (!decisions.length) {
    notify('没有需要写回的判定', 'warning')
    return
  }
  overseasApplying.value = true
  try {
    const result = await applyCustomerRegionOverseas(decisions)
    notify.success(
      `已标记境外 ${result.overseas} 家，按地址回填 ${result.domestic} 家，保持待定 ${result.skipped} 家`
    )
    overseasVisible.value = false
    await loadDistribution()
    renderMapOption()
  } catch (err: any) {
    notify.errorWithDetail('境外客户判定写回失败', err)
  } finally {
    overseasApplying.value = false
  }
}

function csvCell(value: unknown) {
  const text = value == null ? '' : String(value)
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

function exportRegionImportErrors() {
  const rows = importPreview.value?.items.filter(row => row.status === 'ERROR') ?? []
  if (!rows.length) return
  const table = [
    ['原文件行号', '客户编号', '客户名称', '省份', '城市', '区县', '错误原因'],
    ...rows.map(row => [row.rowNumber, row.customerCode, row.customerName, row.province, row.city, row.district, row.message])
  ]
  const csv = `\uFEFF${table.map(row => row.map(csvCell).join(',')).join('\r\n')}`
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `客户地区导入错误报告_${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
  notify.success(`已导出 ${rows.length} 条错误记录`)
}

// ==================== 生命周期 ====================

let resizeObserver: ResizeObserver | null = null

watch(
  () => props.visible,
  visible => {
    if (!visible) {
      chart?.dispose()
      chart = null
      resizeObserver?.disconnect()
      resizeObserver = null
      mapLoading.value = false
      mapError.value = false
      return
    }
    // 打开时重置选中/地图层级，并按当前列表的地区筛选预选中
    selectedName.value = null
    selectedCityName.value = null
    selectedDistrictName.value = null
    noRegionActive.value = false
    drill.value = null
    districtDrill.value = null
    data.value = null
    nextTick(async () => {
      await loadDistribution()
      const region = props.activeRegion
      if (region?.noRegion) {
        noRegionActive.value = true
      } else if (region?.province) {
        const p = provinces.value.find(
          item => item.filterValue === region.province || item.name === region.province
        )
        if (p) {
          selectedName.value = p.name
          if (region.city) {
            const c = p.cities.find(city => city.name === region.city)
            if (c) {
              selectedCityName.value = c.name
              if (region.district) {
                const d = c.districts.find(d => d.name === region.district)
                if (d) selectedDistrictName.value = d.name
              }
            }
          }
        }
      }
      renderMapOption()
      // 监听容器尺寸变化做 resize
      if (mapElRef.value && typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => chart?.resize())
        resizeObserver.observe(mapElRef.value)
      }
    })
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
  resizeObserver?.disconnect()
  resizeObserver = null
})

function requestClose() {
  emit('close')
}
</script>

<style scoped>
.dist-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-control-gap-wide);
  margin-bottom: var(--space-md);
}

.dist-stat {
  display: flex;
  align-items: baseline;
  gap: var(--space-control-gap-tight);
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.dist-stat__label {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dist-stat strong {
  margin-left: auto;
  color: var(--color-primary);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.dist-stat--warn strong {
  color: var(--color-warning, #d97706);
}

.dist-stat--action {
  width: 100%;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease,
    box-shadow 0.15s ease;
}

.dist-stat--action:hover:not(:disabled) {
  border-color: var(--color-warning-border, #fcd34d);
  background: var(--color-warning-bg, #fffbeb);
}

.dist-stat--action:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.dist-stat--action.is-active {
  border-color: var(--color-warning, #d97706);
  background: var(--color-warning-bg, #fffbeb);
  box-shadow: inset 0 0 0 1px var(--color-warning, #d97706);
}

.dist-stat--action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.dist-stat__unit {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
}

.dist-main {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(340px, 1fr);
  gap: var(--space-md);
  height: 540px;
}

.dist-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  overflow: hidden;
}

.dist-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-control-gap-wide) var(--space-md);
  border-bottom: 1px solid var(--color-border-light);
}

.dist-panel__title {
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.dist-panel__hint {
  color: var(--color-text-faint);
  font-size: var(--fs-xxs);
}

/* 地图层级面包屑 */
.dist-crumbs {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  min-width: 0;
  overflow: hidden;
}

.dist-crumbs__link {
  flex: 0 0 auto;
  padding: 2px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-primary);
  font: inherit;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  cursor: pointer;
  transition: background 0.15s ease;
}

.dist-crumbs__link:hover {
  background: var(--color-primary-bg);
}

.dist-crumbs__link:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.dist-crumbs__sep {
  flex: 0 0 auto;
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
}

.dist-crumbs__current {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dist-map {
  position: relative;
  flex: 1;
  min-height: 0;
  background: var(--color-surface-raised, #fbfcfe);
}

.dist-map__chart {
  position: absolute;
  inset: 0;
}

.dist-map__overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  background: rgba(255, 255, 255, 0.72);
}

.dist-map__spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--color-primary-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: dist-map-spin 0.8s linear infinite;
}

.dist-map__error {
  color: var(--color-danger, #dc2626);
}

.dist-map__retry {
  padding: 4px 14px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-md);
  color: var(--color-primary);
  background: var(--color-surface);
  font-size: var(--fs-sm);
  cursor: pointer;
}

.dist-map__legend {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-control-gap-tight) var(--space-md);
  border-top: 1px solid var(--color-border-light);
}

.dist-map__legend-label {
  color: var(--color-text-faint);
  font-size: var(--fs-xxs);
}

.dist-map__legend-bar {
  width: 120px;
  height: 8px;
  border-radius: var(--radius-pill);
  background: linear-gradient(90deg, #eef1f6, hsl(172, 55%, 92%), hsl(172, 80%, 62%), hsl(172, 80%, 52%));
}

.dist-rank {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-control-gap) var(--space-control-gap-tight);
}

.dist-rank__empty {
  padding: var(--space-2xl, 32px) var(--space-md);
  color: var(--color-text-faint);
  font-size: var(--fs-sm);
  text-align: center;
}

.dist-rank__item {
  margin-bottom: var(--space-control-gap-tight);
}

.dist-rank__row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  width: 100%;
  padding: var(--space-control-gap-tight) var(--space-control-gap);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.dist-rank__row:hover {
  background: var(--color-surface-hover, #f1f5f9);
}

.dist-rank__row.is-active {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.dist-rank__no {
  flex: 0 0 auto;
  width: 22px;
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}

.dist-rank__row.is-active .dist-rank__no {
  color: var(--color-primary);
  font-weight: var(--fw-bold);
}

.dist-rank__meta {
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
  flex: 1;
}

.dist-rank__name {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dist-rank__city-count {
  flex: 0 0 auto;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt, #f1f5f9);
  color: var(--color-text-faint);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-normal);
}

.dist-rank__tag {
  flex: 0 0 auto;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--color-warning-bg, #fffbeb);
  color: var(--color-warning, #d97706);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-medium);
}

.dist-rank__bar-track {
  height: 5px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover, #f1f5f9);
  overflow: hidden;
}

.dist-rank__bar {
  display: block;
  height: 100%;
  border-radius: var(--radius-pill);
  background: linear-gradient(90deg, #5eead4, var(--color-primary));
  transition: width 0.3s ease;
}

.dist-rank__count {
  flex: 0 0 auto;
  min-width: 26px;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.dist-rank__caret {
  flex: 0 0 auto;
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  transition: transform 0.2s ease;
}

.dist-rank__caret.is-open {
  transform: rotate(180deg);
  color: var(--color-primary);
}

.dist-rank__cities {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight);
  padding: var(--space-control-gap-tight) var(--space-control-gap) var(--space-control-gap-tight) 40px;
}

.dist-rank__cities-empty {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
}

.dist-rank__city {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: 3px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.dist-rank__city:hover {
  border-color: var(--color-primary-border);
}

.dist-rank__city.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}

.dist-rank__city-count {
  opacity: 0.75;
}

.dist-rank__city.is-active .dist-rank__city-count {
  opacity: 0.9;
}

/* 统计口径说明 */
.dist-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm) var(--space-md);
  margin: calc(-1 * var(--space-control-gap-wide)) 0 var(--space-md);
}

.dist-controls__note {
  color: var(--color-text-faint);
  font-size: var(--fs-xxs);
}

.dist-map__legend {
  flex-wrap: wrap;
  row-gap: var(--space-control-gap-tight);
}

.dist-rank__tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.dist-rank__count {
  min-width: 52px;
}

@keyframes dist-map-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 900px) {
  .dist-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dist-main {
    grid-template-columns: 1fr;
    height: auto;
  }

  .dist-panel--map {
    min-height: 420px;
  }

  .dist-panel--rank {
    min-height: 360px;
  }
}
</style>
