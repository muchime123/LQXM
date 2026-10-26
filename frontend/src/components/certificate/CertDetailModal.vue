<template>
  <LqDialog
    :model-value="visible"
    :title="modalTitle"
    :subtitle="modalSubtitle"
    size="lg"
    :close-on-click-modal="false"
    destroy-on-close
    :show-footer="true"
    :loading="modalSaving"
    @close="$emit('close')"
  >
    <!-- ==================== 详情视图 ==================== -->
    <template v-if="!isEditMode && !isCreateMode">
      <!-- 顶部：身份 + 到期倒计时 -->
      <div class="cd-hero">
        <div class="cd-hero__main">
          <div class="cd-hero__tags">
            <StatusTag :type="certStatusTagClass(data.assetStatus)" :label="data.assetStatus || '未知'" />
            <span class="cd-chip">{{ heroTypeText }}</span>
            <span v-if="data.legalStatus" class="cd-chip cd-chip--muted">{{ data.legalStatus }}</span>
          </div>
          <div class="cd-hero__nums">
            <div v-if="data.patentNo" class="cd-num">
              <span class="cd-num__label">{{ isTrademark ? '商标注册号' : '专利号' }}</span>
              <span class="cd-num__value">
                <code>{{ data.patentNo }}</code>
                <button class="cd-copy" title="复制纯数字编号" @click="copyNo(data.patentNo, '编号')">复制数字</button>
              </span>
            </div>
            <div v-if="queryNoText" class="cd-num">
              <span class="cd-num__label">查询号</span>
              <span class="cd-num__value">
                <code>{{ queryNoText }}</code>
                <button class="cd-copy" title="复制查询号" @click="copyNo(queryNoText, '查询号')">复制</button>
              </span>
            </div>
          </div>
        </div>
        <div class="cd-count" :class="'cd-count--' + countdown.state">
          <template v-if="countdown.state !== 'none'">
            <div class="cd-count__head">
              <span class="cd-count__value">{{ countdown.value }}</span>
              <span v-if="countdown.unit" class="cd-count__unit">{{ countdown.unit }}</span>
            </div>
            <div v-if="countdown.state === 'ok' || countdown.state === 'warn' || countdown.state === 'danger'" class="cd-count__bar">
              <i :style="{ width: countdown.pct + '%' }"></i>
            </div>
            <div
              v-if="countdown.state === 'ok' || countdown.state === 'warn' || countdown.state === 'danger'"
              class="cd-count__foot"
            >
              <span v-if="countdown.start">{{ countdown.start }}</span>
              <span>{{ countdown.end }}</span>
            </div>
          </template>
          <template v-else>
            <div class="cd-count__head">
              <span class="cd-count__value">—</span>
              <span class="cd-count__unit">未设置到期日</span>
            </div>
          </template>
        </div>
      </div>

      <div class="cd-grid">
        <!-- 左栏 -->
        <div class="cd-col">
          <section class="cd-card">
            <h3 class="cd-card__title">号码信息</h3>
            <dl class="cd-kv">
              <div class="cd-kv__item">
                <dt>{{ isTrademark ? '商标注册号' : '专利号 / 证书号' }}</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !data.patentNo }">
                  {{ data.patentNo || '—' }}
                </dd>
              </div>
              <div class="cd-kv__item">
                <dt>申请号</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !data.applicationNo }">
                  {{ data.applicationNo || '—' }}
                </dd>
              </div>
              <div v-if="!isTrademark" class="cd-kv__item">
                <dt>公开号</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !data.publicationNo }">
                  {{ data.publicationNo || '—' }}
                </dd>
              </div>
              <div class="cd-kv__item">
                <dt>查询号（官方检索）</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !queryNoText }">
                  {{ queryNoText || '—' }}
                </dd>
              </div>
            </dl>
          </section>

          <section class="cd-card">
            <h3 class="cd-card__title">权属与代理</h3>
            <dl class="cd-kv">
              <div class="cd-kv__item">
                <dt>{{ isTrademark ? '注册人' : '申请人' }}</dt>
                <dd class="cd-kv__value" :class="{ 'cd-kv__value--empty': !data.applicant }">{{ data.applicant || '—' }}</dd>
              </div>
              <div v-if="!isTrademark" class="cd-kv__item">
                <dt>发明人</dt>
                <dd class="cd-kv__value" :class="{ 'cd-kv__value--empty': !data.inventors }">{{ data.inventors || '—' }}</dd>
              </div>
              <div class="cd-kv__item">
                <dt>代理机构</dt>
                <dd class="cd-kv__value" :class="{ 'cd-kv__value--empty': !data.agency }">{{ data.agency || '—' }}</dd>
              </div>
              <div class="cd-kv__item">
                <dt>代理联系人</dt>
                <dd class="cd-kv__value" :class="{ 'cd-kv__value--empty': !data.agencyContact }">{{ data.agencyContact || '—' }}</dd>
              </div>
              <div v-if="isTrademark" class="cd-kv__item cd-kv__item--wide">
                <dt>注册人地址</dt>
                <dd class="cd-kv__value" :class="{ 'cd-kv__value--empty': !data.applicantAddress }">{{ data.applicantAddress || '—' }}</dd>
              </div>
            </dl>
          </section>

          <section class="cd-card">
            <h3 class="cd-card__title">
              时间节点
              <span class="cd-card__hint">到期日按「申请日 + 法定年限」自动计算</span>
            </h3>
            <dl class="cd-kv">
              <div class="cd-kv__item">
                <dt>申请日</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !data.applyDate }">{{ fmtDate(data.applyDate) || '—' }}</dd>
              </div>
              <div class="cd-kv__item">
                <dt>{{ isTrademark ? '注册日期' : '授权日' }}</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !data.grantDate }">{{ fmtDate(data.grantDate) || '—' }}</dd>
              </div>
              <div class="cd-kv__item">
                <dt>法定年限</dt>
                <dd class="cd-kv__value" :class="{ 'cd-kv__value--empty': data.legalYears == null }">{{ data.legalYears != null ? `${data.legalYears} 年` : '—' }}</dd>
              </div>
              <div class="cd-kv__item">
                <dt>到期日</dt>
                <dd class="cd-kv__value cd-kv__value--mono" :class="{ 'cd-kv__value--empty': !data.expireDate }">{{ fmtDate(data.expireDate) || '—' }}</dd>
              </div>
            </dl>
          </section>

          <section v-if="isTrademark && data.niceClassDescription" class="cd-card">
            <h3 class="cd-card__title">商品 / 服务</h3>
            <dl class="cd-kv cd-kv--1">
              <div class="cd-kv__item cd-kv__item--wide">
                <dt>核定使用商品/服务项目</dt>
                <dd class="cd-kv__value cd-kv__value--para">{{ data.niceClassDescription }}</dd>
              </div>
            </dl>
          </section>

          <section class="cd-card">
            <h3 class="cd-card__title">附件与备注</h3>
            <dl class="cd-kv">
              <div v-if="data.specFile" class="cd-kv__item">
                <dt>{{ isTrademark ? '商标图样' : '说明书文件' }}</dt>
                <dd>
                  <span class="cd-file-link" title="点击预览" @click="modalEmitPreview('specFile')">
                    <i class="cd-file-ico">{{ extOf(data.specFile) }}</i>
                    <span>{{ fileNameOf(data.specFile) }}</span>
                  </span>
                </dd>
              </div>
              <div v-if="data.certFile" class="cd-kv__item">
                <dt>{{ isTrademark ? '注册证' : '证书图片' }}</dt>
                <dd>
                  <span class="cd-file-link" title="点击预览" @click="modalEmitPreview('certFile')">
                    <i class="cd-file-ico">{{ extOf(data.certFile) }}</i>
                    <span>{{ fileNameOf(data.certFile) }}</span>
                  </span>
                </dd>
              </div>
              <div v-if="!data.specFile && !data.certFile" class="cd-kv__item cd-kv__item--wide">
                <dd class="cd-kv__value cd-kv__value--empty">暂无附件</dd>
              </div>
              <div v-if="!isTrademark && data.reviewProgress" class="cd-kv__item cd-kv__item--wide">
                <dt>审查进度</dt>
                <dd class="cd-kv__value cd-kv__value--para">{{ data.reviewProgress }}</dd>
              </div>
              <div v-if="data.remark" class="cd-kv__item cd-kv__item--wide">
                <dt>备注</dt>
                <dd class="cd-kv__value cd-kv__value--para">{{ data.remark }}</dd>
              </div>
            </dl>
          </section>
        </div>

        <!-- 右栏 -->
        <aside class="cd-col cd-col--side">
          <section class="cd-card">
            <h3 class="cd-card__title">{{ isTrademark ? '商标图样' : '证书图片' }}</h3>
            <a
v-if="mainImageFile"
class="cd-thumb"
title="点击查看大图"
@click="modalEmitPreview(mainImageField)">
              <img :src="mainImageUrl" alt="" />
              <span class="cd-thumb__hint">点击查看大图</span>
            </a>
            <div v-else class="cd-thumb--empty">暂无图片</div>
            <div v-if="secondaryFile" class="cd-thumb__extra">
              <span class="cd-file-link" title="点击预览" @click="modalEmitPreview(secondaryField)">
                <i class="cd-file-ico">{{ extOf(secondaryFile) }}</i>
                <span>{{ secondaryLabel }} · {{ fileNameOf(secondaryFile) }}</span>
              </span>
            </div>
          </section>

          <section v-if="!isTrademark" class="cd-card">
            <h3 class="cd-card__title">
              缴费概览
              <span class="cd-card__hint">明细见下方缴费记录</span>
            </h3>
            <dl class="cd-kv-side">
              <div class="cd-kv-side__row">
                <dt>缴费状态</dt>
                <dd>
                  <span
                    v-if="data.feeStatus"
                    class="cd-fee-tag"
                    :class="certFeeTagClass(data.feeStatus)"
                  >{{ data.feeStatus }}</span>
                  <span v-else class="cd-kv__value--empty">—</span>
                </dd>
              </div>
              <div class="cd-kv-side__row">
                <dt>年费截止</dt>
                <dd :class="{ 'is-danger': feeDueOverdue }">{{ fmtDate(data.feeDueDate) || '—' }}</dd>
              </div>
              <div class="cd-kv-side__row">
                <dt>上次缴费</dt>
                <dd>{{ data.lastFeeAmount != null ? `¥${data.lastFeeAmount}` : '—' }}</dd>
              </div>
              <div class="cd-kv-side__row">
                <dt>下次查询</dt>
                <dd>{{ fmtDate(data.feeQueryDate) || '—' }}</dd>
              </div>
              <div v-if="data.invoiceStatus" class="cd-kv-side__row">
                <dt>发票状态</dt>
                <dd>{{ data.invoiceStatus }}</dd>
              </div>
            </dl>
            <p v-if="data.feeDetail" class="cd-side-note">{{ data.feeDetail }}</p>
          </section>

        </aside>
      </div>

      <FeeRecordsPanel v-if="data.id && !isTrademark" :certificate-id="data.id" @updated="$emit('feeUpdated')" />
    </template>

    <!-- ==================== 编辑/新增表单（缴费信息在详情页「缴费记录」中维护） ==================== -->
    <template v-else>
      <div class="cf">
        <section class="cf-sec cf-sec--tint">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">基础信息</h3>
            <span class="cf-sec__hint">名称、类别与当前法律状态</span>
          </div>
          <div class="cf-grid">
            <div class="cf-field cf-field--full">
              <label class="required">{{ isTrademark ? '商标名称' : '名称' }}</label>
              <el-input v-model="modalForm.name" :placeholder="isTrademark ? '商标名称' : '专利/证书名称'" />
            </div>
            <div class="cf-field">
              <label class="required">资产类别</label>
              <el-select v-model="modalForm.assetCategory" style="width: 100%">
                <el-option label="专利" value="专利" />
                <el-option label="软著" value="软著" />
                <el-option label="商标" value="商标" />
              </el-select>
            </div>
            <div v-if="!isTrademark" class="cf-field">
              <label>专利类型</label>
              <el-select
v-model="modalForm.patentType"
clearable
placeholder="--"
style="width: 100%">
                <el-option label="发明专利" value="发明专利" />
                <el-option label="实用新型" value="实用新型" />
                <el-option label="外观设计" value="外观设计" />
                <el-option label="软著" value="软著" />
              </el-select>
            </div>
            <div v-if="isTrademark" class="cf-field">
              <label>国际分类号</label>
              <el-input
v-model.number="modalForm.niceClass"
type="number"
min="1"
max="45"
placeholder="1-45" />
            </div>
            <div class="cf-field">
              <label class="required">资产状态</label>
              <el-select v-model="modalForm.assetStatus" style="width: 100%">
                <el-option label="有效" value="有效" />
                <el-option label="已失效" value="已失效" />
                <el-option label="未生效" value="未生效" />
                <el-option label="已转让" value="已转让" />
              </el-select>
            </div>
            <div class="cf-field">
              <label>法律状态</label>
              <el-input v-model="modalForm.legalStatus" placeholder="如：授权有效" />
            </div>
          </div>
        </section>

        <section class="cf-sec">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">{{ isTrademark ? '注册信息' : '号码信息' }}</h3>
            <span class="cf-sec__hint">查询号留空时按申请号 → 专利号自动提取纯数字</span>
          </div>
          <div class="cf-grid cf-grid--4">
            <div class="cf-field">
              <label>{{ isTrademark ? '商标注册号' : '专利号 / 证书号' }}</label>
              <el-input v-model="modalForm.patentNo" />
            </div>
            <div class="cf-field">
              <label>申请号</label>
              <el-input v-model="modalForm.applicationNo" />
            </div>
            <div v-if="!isTrademark" class="cf-field">
              <label>公开号</label>
              <el-input v-model="modalForm.publicationNo" />
            </div>
            <div v-if="!isTrademark" class="cf-field">
              <label>查询号</label>
              <el-input
                v-model="modalForm.queryNo"
                placeholder="留空自动提取"
                @blur="onQueryNoBlur"
              />
            </div>
          </div>
        </section>

        <section class="cf-sec">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">权属信息</h3>
            <span class="cf-sec__hint">权利人、发明人与代理</span>
          </div>
          <div class="cf-grid">
            <div class="cf-field">
              <label>{{ isTrademark ? '注册人' : '申请人' }}</label>
              <el-input v-model="modalForm.applicant" />
            </div>
            <div v-if="!isTrademark" class="cf-field">
              <label>发明人 <span class="opt-note">多人用分号分隔</span></label>
              <el-input v-model="modalForm.inventors" placeholder="张三；李四" />
            </div>
            <div class="cf-field">
              <label>代理机构</label>
              <el-input v-model="modalForm.agency" />
            </div>
            <div class="cf-field">
              <label>代理联系人</label>
              <el-input v-model="modalForm.agencyContact" placeholder="联系人姓名" />
            </div>
            <div v-if="isTrademark" class="cf-field cf-field--full">
              <label>注册人地址</label>
              <el-input v-model="modalForm.applicantAddress" />
            </div>
          </div>
        </section>

        <section class="cf-sec">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">时间信息</h3>
            <span class="cf-sec__hint">到期日 = 申请日 + 法定年限，保存时自动计算</span>
          </div>
          <div class="cf-grid cf-grid--4">
            <div class="cf-field">
              <label class="required">申请日</label>
              <LqDatePicker v-model="modalForm.applyDate" />
            </div>
            <div class="cf-field">
              <label>{{ isTrademark ? '注册日期' : '授权日' }}</label>
              <LqDatePicker v-model="modalForm.grantDate" />
            </div>
            <div class="cf-field">
              <label>法定年限（年）</label>
              <el-input v-model.number="modalForm.legalYears" type="number" min="1" />
            </div>
            <div class="cf-field">
              <label>到期日 <span class="opt-note">自动计算</span></label>
              <el-input :model-value="formExpireDate" disabled placeholder="按申请日 + 法定年限" />
            </div>
          </div>
        </section>

        <section v-if="isTrademark" class="cf-sec">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">商品 / 服务</h3>
            <span class="cf-sec__hint">核定使用商品或服务项目</span>
          </div>
          <div class="cf-grid">
            <div class="cf-field cf-field--full">
              <el-input
                v-model="modalForm.niceClassDescription"
                type="textarea"
                :rows="4"
                placeholder="如：盆（容器）；黄油碟；碗；瓶；调味瓶..."
              />
            </div>
          </div>
        </section>

        <section class="cf-sec">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">附件</h3>
            <span class="cf-sec__hint">支持点击选择或拖拽上传</span>
          </div>
          <div class="cf-grid cf-grid--2">
            <div class="cf-field">
              <label>{{ isTrademark ? '商标图样' : '说明书文件' }}</label>
              <div
                class="file-upload-cell"
                :class="{ 'drag-over': specDrop.isDragOver && !modalForm.specFile }"
                v-bind="specDrop.dragHandlers"
              >
                <template v-if="modalForm.specFile">
                  <div class="file-preview-item">
                    <img
                      v-if="isImageFile(modalForm.specFile)"
                      :src="certificatePreviewUrl(modalForm.specFile)"
                      class="file-thumb"
                      title="点击预览"
                      @click="modalEmitPreview('specFile')"
                    />
                    <span
                      v-else
                      class="file-name-text file-name-clickable"
                      :title="modalForm.specFile"
                      @click="modalEmitPreview('specFile')"
                    >
                      {{ modalForm.specFile }}
                    </span>
                    <button class="btn-icon btn-remove-file" title="删除文件" @click="modalHandleRemoveFile('specFile')">
                      x
                    </button>
                  </div>
                </template>
                <button
                  v-else
                  class="btn-sm btn-upload"
                  :disabled="modalUploadingField === 'specFile'"
                  @click="modalHandleFileUpload('specFile')"
                >
                  {{ modalUploadingField === 'specFile' ? '上传中...' : (isTrademark ? '+ 上传商标图样（可拖拽）' : '+ 上传说明书文件（可拖拽）') }}
                </button>
              </div>
            </div>
            <div class="cf-field">
              <label>{{ isTrademark ? '注册证' : '证书图片' }}</label>
              <div
                class="file-upload-cell"
                :class="{ 'drag-over': certDrop.isDragOver && !modalForm.certFile }"
                v-bind="certDrop.dragHandlers"
              >
                <template v-if="modalForm.certFile">
                  <div class="file-preview-item">
                    <img
                      v-if="isImageFile(modalForm.certFile)"
                      :src="certificatePreviewUrl(modalForm.certFile)"
                      class="file-thumb"
                      title="点击预览"
                      @click="modalEmitPreview('certFile')"
                    />
                    <span
                      v-else
                      class="file-name-text file-name-clickable"
                      :title="modalForm.certFile"
                      @click="modalEmitPreview('certFile')"
                    >
                      {{ modalForm.certFile }}
                    </span>
                    <button class="btn-icon btn-remove-file" title="删除文件" @click="modalHandleRemoveFile('certFile')">
                      x
                    </button>
                  </div>
                </template>
                <button
                  v-else
                  class="btn-sm btn-upload"
                  :disabled="modalUploadingField === 'certFile'"
                  @click="modalHandleFileUpload('certFile')"
                >
                  {{ modalUploadingField === 'certFile' ? '上传中...' : (isTrademark ? '+ 上传注册证（可拖拽）' : '+ 上传证书图片（可拖拽）') }}
                </button>
              </div>
            </div>
          </div>
        </section>

        <section class="cf-sec">
          <div class="cf-sec__head">
            <h3 class="cf-sec__title">备注与办理</h3>
            <span class="cf-sec__hint">年费缴纳请在详情页「缴费记录」中登记，保存后自动回填缴费状态</span>
          </div>
          <div class="cf-grid">
            <div v-if="!isTrademark" class="cf-field">
              <label>发票状态</label>
              <el-select
v-model="modalForm.invoiceStatus"
clearable
placeholder="--"
style="width: 100%">
                <el-option label="未开票" value="未开票" />
                <el-option label="已开票" value="已开票" />
                <el-option label="已归档" value="已归档" />
              </el-select>
            </div>
            <div v-if="!isTrademark" class="cf-field cf-field--full">
              <label>审查进度备注</label>
              <el-input
                v-model="modalForm.reviewProgress"
                type="textarea"
                :rows="2"
                placeholder="审查意见、补正记录、进度说明等"
              />
            </div>
            <div class="cf-field cf-field--full">
              <label>备注</label>
              <el-input v-model="modalForm.remark" type="textarea" :rows="2" />
            </div>
          </div>
        </section>
      </div>
    </template>

    <template #footer>
      <div class="cert-dialog-foot">
        <template v-if="isEditMode || isCreateMode">
          <button @click="$emit('close')">取消</button>
          <button class="primary" :disabled="modalSaving" @click="modalDoSave">
            {{ modalSaving ? '保存中...' : '保存' }}
          </button>
        </template>
        <template v-else>
          <button class="danger" @click="$emit('delete')">删除</button>
          <button class="primary" @click="$emit('edit')">编辑此证书</button>
        </template>
      </div>
    </template>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { CertificateDetail, CertificatePayload } from '../../types/certificate'
import { createCertificate, updateCertificate } from '../../api/certificate'
import LqDialog from '../common/LqDialog.vue'
import LqDatePicker from '../common/LqDatePicker.vue'
import StatusTag from '../common/StatusTag.vue'
import FeeRecordsPanel from './FeeRecordsPanel.vue'
import { clearDirty, setDirty } from '../../composables/common/useDirty'
import { certFeeTagClass, certStatusTagClass } from '../../composables/common/useStatus'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { notify } from '../../composables/ui/useToast'
import { useFileAssets } from '../../composables/upload/useFileAssets'
import { useFileUploadWorkflow } from '../../composables/upload/useFileUploadWorkflow'
import { useLocalDropUpload } from '../../composables'
import { findFileAssetHandle, fileAssetPreviewUrlByOptions } from '../../utils/ui/fileAssetUrls'
import { FILE_OWNER_TYPE_KEYS } from '../../types/fileAsset'
import { fmtDate, todayIso } from '../../utils/common/dateUtils'
import { digitsOnly } from '../../utils/common/textUtils'
import { isImageFile } from '../../utils/ui/fileUtils'
import { certificateFileAssetId, type CertificateFileField } from '../../utils/certificate/certificateFileAsset'
import { copyText } from '../../utils/ui/clipboard'

const props = defineProps<{
  visible: boolean
  data: CertificateDetail
  isEditMode: boolean
  isCreateMode: boolean
}>()

const emit = defineEmits<{
  close: []
  save: [payload: CertificatePayload]
  edit: []
  delete: []
  feeUpdated: []
  'preview-asset': [asset: unknown]
}>()

const modalSaving = ref(false)

const modalTitle = computed(() => {
  if (props.isCreateMode) return '新增知识产权'
  if (props.isEditMode) return '编辑知识产权'
  return props.data.name || '证书详情'
})

const modalSubtitle = computed(() => {
  if (props.isCreateMode) return ''
  const parts: string[] = []
  if (props.isEditMode && props.data.name) parts.push(props.data.name)
  if (props.data.assetCategory === '商标') parts.push('商标')
  else if (props.data.patentType) parts.push(props.data.patentType)
  if (props.data.patentNo) parts.push(props.data.patentNo)
  return parts.join(' · ')
})

const isTrademark = computed(() => modalForm.assetCategory === '商标')

const heroTypeText = computed(() => {
  const d = props.data
  if (d.assetCategory === '商标') return d.niceClass != null ? `第${d.niceClass}类` : '商标'
  return d.patentType || d.assetCategory || '—'
})

const queryNoText = computed(
  () => props.data.queryNo || digitsOnly(props.data.applicationNo) || digitsOnly(props.data.patentNo)
)

const feeDueOverdue = computed(() => !!props.data.feeDueDate && props.data.feeDueDate < todayIso())

function copyNo(value: string, label: string) {
  copyText(digitsOnly(value), `纯数字${label}`)
}

// ===== 到期倒计时（申请日 → 到期日，进度条表示已度过年限） =====
function parseDateSafe(value?: string | null): number | null {
  if (!value) return null
  const t = Date.parse(value)
  return Number.isNaN(t) ? null : t
}

const countdown = computed(() => {
  const d = props.data
  const expire = parseDateSafe(d.expireDate)
  const apply = parseDateSafe(d.applyDate)
  const end = fmtDate(d.expireDate) || ''
  const start = fmtDate(d.applyDate) || ''
  if (!expire) return { state: 'none', value: '—', unit: '未设置到期日', pct: 0, start: '', end: '' }

  let pct = 0
  if (apply != null && expire > apply) {
    const total = expire - apply
    const elapsed = Date.parse(todayIso()) - apply
    pct = Math.min(100, Math.max(3, Math.round((elapsed / total) * 100)))
  }
  if (d.daysRemaining == null) {
    return { state: 'muted', value: end, unit: '', pct, start, end }
  }
  if (d.daysRemaining < 0) {
    return { state: 'danger', value: String(-d.daysRemaining), unit: '天前已到期', pct: 100, start, end }
  }
  if (d.daysRemaining <= 180) {
    return { state: 'warn', value: String(d.daysRemaining), unit: '天后到期', pct, start, end }
  }
  return { state: 'ok', value: String(d.daysRemaining), unit: '天后到期', pct, start, end }
})

// ===== 右栏主图（商标看图样，专利看证书） =====
const mainImageField = computed<'specFile' | 'certFile'>(() => (isTrademark.value ? 'specFile' : 'certFile'))
const secondaryField = computed<'specFile' | 'certFile'>(() => (isTrademark.value ? 'certFile' : 'specFile'))

const mainImageFile = computed(() => {
  const file = props.data[mainImageField.value]
  return file && isImageFile(file) ? file : ''
})
const mainImageUrl = computed(() => (mainImageFile.value ? certificatePreviewUrl(mainImageFile.value) : ''))
const secondaryFile = computed(() => props.data[secondaryField.value] || '')
const secondaryLabel = computed(() =>
  isTrademark.value ? '注册证' : '说明书文件'
)

function extOf(fileName: string): string {
  const m = /\.([A-Za-z0-9]+)$/.exec(fileName)
  const ext = m ? m[1].toUpperCase() : '文件'
  return ext.length > 4 ? ext.slice(0, 4) : ext
}

function fileNameOf(fileName: string): string {
  const idx = fileName.lastIndexOf('/')
  return idx >= 0 ? fileName.slice(idx + 1) : fileName
}

// ===== 编辑表单 =====
const modalForm = reactive<Partial<CertificatePayload>>({})

watch(
  () => props.data,
  newData => {
    Object.keys(modalForm).forEach(k => delete (modalForm as Record<string, unknown>)[k])
    Object.assign(modalForm, newData)
  },
  { immediate: true }
)

/** 到期日只读预览：与后端口径一致（申请日 + 法定年限） */
const formExpireDate = computed(() => {
  const apply = modalForm.applyDate
  const years = modalForm.legalYears
  if (apply && years != null && Number(years) > 0) {
    const t = Date.parse(String(apply))
    if (!Number.isNaN(t)) {
      const dt = new Date(t)
      dt.setFullYear(dt.getFullYear() + Number(years))
      return fmtDate(dt.toISOString().slice(0, 10))
    }
  }
  return modalForm.expireDate ? fmtDate(String(modalForm.expireDate)) : ''
})

/** 缴费字段不在本表单维护（缴费记录登记后由后端自动回填），保存时整体剔除避免回写旧值 */
const FEE_FIELDS = ['feeStatus', 'feeQueryDate', 'feeDueDate', 'lastFeeAmount', 'feeDetail'] as const

function buildPayload(): CertificatePayload {
  const rest: Record<string, unknown> = { ...modalForm }
  for (const key of FEE_FIELDS) delete rest[key]
  return rest as unknown as CertificatePayload
}

const certificateOwnerId = computed(() => props.data?.id ?? null)
const certificateFileAssets = useFileAssets({
  ownerType: FILE_OWNER_TYPE_KEYS.certificate,
  ownerId: certificateOwnerId,
  notify,
  onChanged: async () => {}
})

const certificateFileHandles = computed(() => certificateFileAssets.items.value)

watch(
  () => [props.visible, props.data?.id] as const,
  async ([visible, certificateId]) => {
    if (!visible || !certificateId) return
    await certificateFileAssets.loadCapabilities()
    await certificateFileAssets.load()
  },
  { immediate: true }
)

const { pickFiles, uploadFiles } = useFileUploadWorkflow({
  category: 'certificate',
  notify
})

const modalUploadingField = ref<string | null>(null)

async function modalHandleFileUpload(field: CertificateFileField) {
  modalUploadingField.value = field
  try {
    const results = await pickFiles({ multiple: false })
    const result = results[0]
    if ('fileName' in result && result.fileName) {
      modalForm[field] = result.fileName
      await certificateFileAssets.load()
    }
  } finally {
    modalUploadingField.value = null
    if (field === 'specFile') specDrop.reset()
    else certDrop.reset()
  }
}

async function modalHandleDropFiles(field: CertificateFileField, files: File[]) {
  if (!files.length) return
  modalUploadingField.value = field
  try {
    const results = await uploadFiles(files.slice(0, 1), { multiple: false, source: 'drag' })
    const result = results[0] as { fileName?: string } | undefined
    if (result?.fileName) {
      modalForm[field] = result.fileName
      await certificateFileAssets.load()
    }
  } finally {
    modalUploadingField.value = null
    if (field === 'specFile') specDrop.reset()
    else certDrop.reset()
  }
}

const specDrop = useLocalDropUpload({
  onFiles: files => modalHandleDropFiles('specFile', files)
})

const certDrop = useLocalDropUpload({
  onFiles: files => modalHandleDropFiles('certFile', files)
})

async function modalHandleRemoveFile(field: CertificateFileField) {
  const fileName = modalForm[field] as string
  if (!fileName) return
  const ok = await askConfirm({
    title: '删除文件',
    message: `确定要删除文件「${fileName}」吗？删除后不可恢复。`,
    confirmText: '删除',
    danger: true
  })
  if (ok !== true) return
  const handle = findCertificateFileHandle(fileName)
  if (handle) {
    await certificateFileAssets.remove(handle.assetId)
  }
  modalForm[field] = ''
  if (field === 'specFile') specDrop.reset()
  else certDrop.reset()
}

function findCertificateFileHandle(fileName: string) {
  return findFileAssetHandle(certificateFileHandles.value, fileName)
}

function certificateFileFieldForName(fileName: string): CertificateFileField | null {
  if (modalForm.specFile === fileName || props.data.specFile === fileName) return 'specFile'
  if (modalForm.certFile === fileName || props.data.certFile === fileName) return 'certFile'
  return null
}

function certificatePreviewUrl(fileName?: string | null) {
  if (!fileName) return ''
  const handle = findCertificateFileHandle(fileName)
  const field = certificateFileFieldForName(fileName)
  return (
    certificateFileAssets.previewUrl(handle) ||
    fileAssetPreviewUrlByOptions({
      ownerType: FILE_OWNER_TYPE_KEYS.certificate,
      assetId: handle?.assetId || (props.data?.id && field ? certificateFileAssetId(props.data.id, field) : null),
      category: 'certificate',
      fileName
    })
  )
}

function modalEmitPreview(field: CertificateFileField) {
  const fileName = props.isEditMode || props.isCreateMode ? (modalForm[field] as string) : (props.data[field] as string)
  if (!fileName) return
  const handle = findCertificateFileHandle(fileName)
  emit('preview-asset', {
    fileName,
    ownerType: FILE_OWNER_TYPE_KEYS.certificate,
    assetId: handle?.assetId || (props.data?.id ? certificateFileAssetId(props.data.id, field) : null),
    category: 'certificate'
  })
}

/** 查询号留空时按申请号/专利号自动提取纯数字部分，方便直接复制到官方检索框 */
function onQueryNoBlur() {
  const current = String(modalForm.queryNo ?? '').trim()
  if (current) {
    modalForm.queryNo = digitsOnly(current)
    return
  }
  const derived = digitsOnly(modalForm.applicationNo) || digitsOnly(modalForm.patentNo)
  if (derived) {
    modalForm.queryNo = derived
    setDirty('知识产权编辑')
  }
}

async function modalDoSave() {
  if (!modalForm.name) {
    notify('请填写证书名称', 'warning')
    return
  }
  modalSaving.value = true
  try {
    if (props.isCreateMode) {
      await createCertificate(buildPayload())
    } else {
      await updateCertificate(props.data.id, buildPayload())
    }
    emit('save', buildPayload())
    notify('保存成功', 'success')
  } catch (err) {
    notify.errorWithDetail('保存失败', err)
  } finally {
    modalSaving.value = false
  }
}

watch(
  () => props.visible,
  v => {
    if (v) setDirty('知识产权编辑')
    else clearDirty('知识产权编辑')
  }
)
</script>

<style scoped>
.cert-dialog-foot {
  display: flex;
  gap: var(--space-control-gap);
  margin-left: auto;
}

/* ===== 详情：顶部身份条 ===== */
.cd-hero {
  display: flex;
  align-items: stretch;
  gap: var(--space-2xl, 24px);
  padding: 14px 18px;
  margin-bottom: var(--space-lg-xl, 16px);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-xl, 10px);
  background: linear-gradient(120deg, var(--color-surface-raised) 0%, var(--color-primary-bg-light) 100%);
}

.cd-hero__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
}

.cd-hero__tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm-md, 8px);
}

.cd-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 1.5;
}

.cd-chip--muted {
  color: var(--color-text-muted);
  border-color: var(--color-border-lighter);
  font-weight: var(--fw-normal);
}

.cd-chip-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm, 6px);
}

.cd-hero__nums {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px 28px;
}

.cd-num {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.cd-num__label {
  font-size: var(--fs-xxs);
  letter-spacing: 0.04em;
  color: var(--color-text-muted);
}

.cd-num__value {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md, 8px);
}

.cd-num__value code {
  font-family: var(--font-mono);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.02em;
  color: var(--color-text-heading);
}

.cd-copy {
  padding: 1px 7px;
  font-size: var(--fs-xxs);
  color: var(--color-primary);
  background: var(--color-surface);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.cd-copy:hover {
  background: var(--color-primary-bg);
}

/* 到期倒计时 */
.cd-count {
  flex: 0 0 232px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 7px;
  padding: 11px 15px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.cd-count--warn {
  border-color: var(--color-warning-border);
  background: var(--color-warning-bg-soft);
}

.cd-count--danger {
  border-color: var(--color-danger-border);
  background: var(--color-danger-bg-soft);
}

.cd-count--muted,
.cd-count--none {
  background: var(--color-surface-alt);
}

.cd-count__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm, 6px);
}

.cd-count__value {
  font-size: var(--fs-4xl, 24px);
  font-weight: var(--fw-bold);
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--color-text-heading);
}

.cd-count--muted .cd-count__value,
.cd-count--none .cd-count__value {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--color-text-muted);
}

.cd-count--warn .cd-count__value {
  color: var(--color-warning);
}

.cd-count--danger .cd-count__value {
  color: var(--color-danger);
}

.cd-count__unit {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.cd-count__bar {
  height: 5px;
  border-radius: var(--radius-pill);
  background: var(--color-border-lighter);
  overflow: hidden;
}

.cd-count__bar > i {
  display: block;
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
}

.cd-count--warn .cd-count__bar > i {
  background: var(--color-warning);
}

.cd-count--danger .cd-count__bar > i {
  background: var(--color-danger);
}

.cd-count__foot {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-xxs);
  font-family: var(--font-mono);
  color: var(--color-text-muted);
}

/* ===== 详情：两栏布局 ===== */
.cd-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 288px;
  gap: var(--space-md-lg, 12px);
  align-items: start;
}

.cd-col {
  display: flex;
  flex-direction: column;
  gap: var(--space-md-lg, 12px);
  min-width: 0;
}

@media (max-width: 960px) {
  .cd-hero {
    flex-direction: column;
  }

  .cd-count {
    flex-basis: auto;
  }

  .cd-grid {
    grid-template-columns: 1fr;
  }
}

/* 卡片 */
.cd-card {
  padding: 13px 17px 15px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-xl, 10px);
}

.cd-card__title {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md, 8px);
  margin: 0 0 11px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.02em;
  color: var(--color-text-heading);
}

.cd-card__title::before {
  content: '';
  flex-shrink: 0;
  width: 3px;
  height: 13px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  opacity: 0.85;
}

.cd-card__hint {
  margin-left: auto;
  font-size: var(--fs-xxs);
  font-weight: var(--fw-normal);
  letter-spacing: 0;
  color: var(--color-text-faint);
}

/* 键值网格 */
.cd-kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 13px 26px;
  margin: 0;
}

.cd-kv--1 {
  grid-template-columns: 1fr;
}

.cd-kv__item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.cd-kv__item--wide {
  grid-column: 1 / -1;
}

.cd-kv dt {
  font-size: var(--fs-xxs);
  letter-spacing: 0.02em;
  color: var(--color-text-muted);
}

.cd-kv dd {
  margin: 0;
  font-size: var(--fs-base);
  line-height: var(--lh-relaxed);
  color: var(--color-text-primary);
  word-break: break-word;
}

.cd-kv__value--mono {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  letter-spacing: 0.02em;
}

.cd-kv__value--empty {
  color: var(--color-text-faint);
}

.cd-kv__value--para {
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
  color: var(--color-text-strong);
  white-space: pre-wrap;
}

/* 右栏紧凑键值 */
.cd-kv-side {
  margin: 0;
}

.cd-kv-side__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md-lg, 12px);
  padding: 6px 0;
  border-bottom: 1px dashed var(--color-border-lighter);
}

.cd-kv-side__row:last-child {
  border-bottom: none;
}

.cd-kv-side__row dt {
  flex-shrink: 0;
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.cd-kv-side__row dd {
  margin: 0;
  font-size: var(--fs-sm);
  font-family: var(--font-mono);
  text-align: right;
  color: var(--color-text-primary);
}

.cd-kv-side__row dd.is-danger {
  color: var(--color-danger);
  font-weight: var(--fw-semibold);
}

.cd-fee-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 1.5;
}

.cd-fee-tag.tag-ok {
  background: var(--color-success-bg);
  color: var(--color-success-text);
  border-color: var(--color-success-border);
}

.cd-fee-tag.tag-warn {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
  border-color: var(--color-warning-border);
}

.cd-fee-tag.tag-expired {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
  border-color: var(--color-danger-border);
}

.cd-side-note {
  margin: 8px 0 0;
  padding-top: 8px;
  border-top: 1px dashed var(--color-border-lighter);
  font-size: var(--fs-xs);
  line-height: var(--lh-loose);
  color: var(--color-text-secondary);
}

/* 文件 */
.cd-file-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm, 6px);
  max-width: 100%;
  font-size: var(--fs-sm);
  color: var(--color-primary);
  cursor: pointer;
}

.cd-file-link span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cd-file-link:hover span {
  text-decoration: underline;
}

.cd-file-ico {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--fs-3xs, 10px);
  font-weight: var(--fw-semibold);
  font-style: normal;
}

/* 证书图 */
.cd-thumb {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 3 / 4;
  overflow: hidden;
  cursor: zoom-in;
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-lg);
}

.cd-thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.cd-thumb__hint {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 4px 8px;
  font-size: var(--fs-xxs);
  text-align: center;
  color: var(--color-text-secondary);
  background: var(--color-glass-bg);
  border-top: 1px solid var(--color-border-lighter);
}

.cd-thumb--empty {
  display: grid;
  place-items: center;
  aspect-ratio: 3 / 4;
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
  background: var(--color-surface-alt);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
}

.cd-thumb__extra {
  margin-top: 8px;
}

/* ===== 编辑表单 ===== */
.cf {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.cf-sec {
  padding: 13px 17px 15px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-xl, 10px);
}

.cf-sec--tint {
  background: var(--color-surface-raised);
}

.cf-sec__head {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm-md, 8px);
  margin-bottom: 12px;
}

.cf-sec__title {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md, 8px);
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.02em;
  color: var(--color-text-heading);
}

.cf-sec__title::before {
  content: '';
  flex-shrink: 0;
  width: 3px;
  height: 13px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  opacity: 0.85;
}

.cf-sec__hint {
  font-size: var(--fs-xxs);
  color: var(--color-text-faint);
}

.cf-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px var(--space-lg-xl, 16px);
}

.cf-grid--4 {
  grid-template-columns: repeat(4, 1fr);
}

.cf-grid--2 {
  grid-template-columns: repeat(2, 1fr);
}

@media (max-width: 960px) {
  .cf-grid,
  .cf-grid--4,
  .cf-grid--2 {
    grid-template-columns: 1fr 1fr;
  }
}

.cf-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.cf-field--full {
  grid-column: 1 / -1;
}

.cf-field > label {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--color-text-secondary);
}

.cf-field > label.required::before {
  content: '*';
  color: var(--color-danger);
  font-weight: var(--fw-bold);
}

.opt-note {
  font-weight: var(--fw-normal);
  color: var(--color-text-faint);
}

/* 日期选择器内部实际是 el-date-editor，需穿透子组件确保通栏 */
.cf-field :deep(.el-date-editor) {
  width: 100%;
}

/* ===== 文件上传 ===== */
.file-upload-cell {
  margin-top: 2px;
  padding: 6px;
  border: 1px dashed transparent;
  border-radius: var(--radius-md);
  transition:
    border-color var(--transition-fast),
    background var(--transition-fast),
    box-shadow var(--transition-fast);
}

.file-upload-cell.drag-over {
  border-color: var(--color-primary);
  background: var(--color-primary-bg-light);
  box-shadow: 0 0 0 3px var(--color-primary-ring-strong);
}

.file-preview-item {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  padding: var(--space-control-gap);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.file-thumb {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.file-name-text {
  flex: 1;
  overflow: hidden;
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-clickable {
  cursor: pointer;
  color: var(--color-primary);
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: var(--fs-base);
  transition: background var(--transition-fast);
}

.btn-icon:hover {
  background: var(--color-danger);
  color: var(--color-surface);
}

.btn-sm {
  padding: 6px 12px;
  font-size: var(--fs-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-sm:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.btn-sm.btn-upload {
  border-style: dashed;
  color: var(--color-primary);
}

.btn-sm.btn-upload:hover {
  background: var(--color-primary-bg);
}
</style>
