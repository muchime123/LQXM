<template>
  <Teleport to="body">
    <LqDialog :model-value="visible" :title="dialogTitle" size="md" mode="form" :close-on-click-modal="false" :z-index="UI_LAYER.nestedModal" destroy-on-close confirm-text="保存" :confirm-disabled="submitDisabled" @close="handleClose" @cancel="handleClose" @confirm="handleSubmit">
      <div class="asset-editor-content">
        <p v-if="mode==='single'&&asset" class="asset-file-name">{{ asset.fileName }}</p>
        <section class="editor-section">
          <div class="section-heading"><strong>内容标签</strong><span>只描述画面内容，不参与机型、模具或包材关系。</span></div>
          <input v-model="contentInput" class="tag-input" placeholder="例如：设备正面、细节特写、客户现场" />
          <div v-if="tagSuggestions.length" class="tag-suggestions"><button v-for="tag in tagSuggestions" :key="tag" type="button" :class="{active:parsedContentTags.includes(tag)}" @click="toggleContentTag(tag)">{{ tag }}</button></div>
          <div v-if="mode==='batch'" class="batch-tag-actions"><label><input v-model="tagAction" type="radio" value="APPEND" />追加</label><label><input v-model="tagAction" type="radio" value="REPLACE" />替换</label><label><input v-model="tagAction" type="radio" value="REMOVE" />删除指定标签</label></div>
        </section>
        <section class="editor-section">
          <div class="section-heading"><strong>适用范围</strong><span>默认属于整个产品；只有确实存在机型差异时才指定机型档位。</span></div>
          <div class="scope-actions">
            <label><input v-model="machineTierAction" type="radio" value="KEEP" /><span><b>保持当前归属</b><small>{{ currentTierLabel }}</small></span></label>
            <label><input v-model="machineTierAction" type="radio" value="UNLINK" /><span><b>产品通用</b><small>适用于该产品所有机型</small></span></label>
            <label><input v-model="machineTierAction" type="radio" value="ASSIGN" /><span><b>指定机型</b><small>可同时选择多个机型档位</small></span></label>
          </div>
          <div v-if="machineTierAction==='ASSIGN'" class="tier-options"><label v-for="tier in machineTiers" :key="tier.id"><input v-model="selectedMachineTierIds" type="checkbox" :value="tier.id" /><span>{{ tier.tierLabel||tier.tierCode }}</span></label></div>
          <p v-if="machineTierAction==='ASSIGN'&&!machineTiers.length" class="empty-hint">当前产品没有机型档位，请先在“机型与包材”中维护。</p>
        </section>
        <section v-if="isVideoEditor" class="editor-section">
          <div class="section-heading"><strong>视频检索信息</strong><span>用途、视频类型、设备特征和展示参数分开保存，不再生成产品规格。</span></div>
          <label v-if="mode==='batch'" class="replace-video"><input v-model="replaceVideoMetadata" type="checkbox" />同时覆盖所选视频的检索信息</label>
          <template v-if="mode==='single'||replaceVideoMetadata">
            <label class="field"><span>适用对象</span><input v-model="applicationInput" class="tag-input" placeholder="豆腐、酸奶、酱料" /></label>
            <div class="tag-suggestions compact"><button v-for="tag in VIDEO_APPLICATION_SUGGESTIONS" :key="tag" type="button" :class="{active:parsedApplicationTags.includes(tag)}" @click="toggleApplicationTag(tag)">{{ tag }}</button></div>
            <div class="field-grid"><label class="field"><span>视频类型</span><select v-model="videoContentType"><option value="">未设置</option><option v-for="item in VIDEO_CONTENT_TYPES" :key="item">{{ item }}</option></select></label><label class="field"><span>内容状态</span><select v-model="videoContentStatus"><option v-for="item in VIDEO_CONTENT_STATUSES" :key="item">{{ item }}</option></select></label></div>
            <label class="field"><span>使用渠道</span><input v-model="usageChannelsInput" class="tag-input" placeholder="可发客户、销售演示、官网可用" /></label>
            <div class="tag-suggestions compact"><button v-for="tag in VIDEO_USAGE_CHANNELS" :key="tag" type="button" :class="{active:parsedUsageChannels.includes(tag)}" @click="toggleUsageChannel(tag)">{{ tag }}</button></div>
            <label class="field"><span>设备/工艺特征</span><input v-model="featureInput" class="tag-input" placeholder="按钮自动、卷膜、气动" /></label>
            <label class="field"><span>视频展示参数</span><input v-model="demonstrationInput" class="tag-input" placeholder="一出四、175×130mm（仅检索）" /></label>
          </template>
        </section>
      </div>
    </LqDialog>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import { UI_LAYER } from '@/constants/uiLayers'
import { VIDEO_APPLICATION_SUGGESTIONS, VIDEO_CONTENT_TYPES, VIDEO_USAGE_CHANNELS, VIDEO_CONTENT_STATUSES } from '@/constants/videoMetadata'
import { parseCatalogTags } from '../../utils/catalog/specificationTags'
import { fetchMachineTiers, type CatalogMachineTier } from '@/api/catalog/machineTiers'

interface Asset { id:number;fileName:string;tags?:string;contentTags?:string;category:string;machineTierId?:number|null;machineTierIds?:number[];machineTierLabel?:string;machineTierLabels?:string[];applicationTags?:string;videoContentType?:string;videoUsageChannels?:string;videoContentStatus?:string;featureTags?:string;demonstrationTags?:string }
type TierAction='KEEP'|'ASSIGN'|'UNLINK'
const props=withDefaults(defineProps<{visible:boolean;mode:'single'|'batch';asset:Asset|null;modelId?:number|null;selectedCount:number;batchTagReplace:boolean;tagSuggestions:string[]}>(),{asset:null,modelId:null,selectedCount:0,batchTagReplace:false,tagSuggestions:()=>[]})
const emit=defineEmits<{
  close:[]
  'submit-single':[payload:{id:number;contentTags:string;machineTierAction:TierAction;machineTierIds:number[];applicationTags:string;videoContentType:string;videoUsageChannels:string;videoContentStatus:string;featureTags:string;demonstrationTags:string}]
  'submit-batch':[payload:{contentTags:string;tagAction:'APPEND'|'REPLACE'|'REMOVE';machineTierAction:TierAction;machineTierIds:number[];applicationTags:string;videoContentType:string;videoUsageChannels:string;videoContentStatus:string;featureTags:string;demonstrationTags:string;replaceVideoMetadata:boolean}]
}>()
const contentInput=ref(''),applicationInput=ref(''),featureInput=ref(''),demonstrationInput=ref(''),usageChannelsInput=ref(''),videoContentType=ref(''),videoContentStatus=ref('待处理')
const tagAction=ref<'APPEND'|'REPLACE'|'REMOVE'>('APPEND'),machineTierAction=ref<TierAction>('KEEP')
const selectedMachineTierIds=ref<number[]>([]),machineTiers=ref<CatalogMachineTier[]>([]),replaceVideoMetadata=ref(false)
const parsedContentTags=computed(()=>parseCatalogTags(contentInput.value)),parsedApplicationTags=computed(()=>parseCatalogTags(applicationInput.value)),parsedUsageChannels=computed(()=>parseCatalogTags(usageChannelsInput.value))
const isVideoEditor=computed(()=>props.mode==='batch'||['device_video','operation_video'].includes(props.asset?.category||''))
const dialogTitle=computed(()=>props.mode==='batch'?`批量编辑 ${props.selectedCount} 个素材`:'编辑素材信息')
const submitDisabled=computed(()=>machineTierAction.value==='ASSIGN'&&!selectedMachineTierIds.value.length)
const currentTierLabel=computed(()=>props.asset?.machineTierLabels?.join('、')||props.asset?.machineTierLabel||'当前为产品通用素材')
watch(()=>props.visible,async visible=>{if(!visible)return;contentInput.value=props.mode==='single'?(props.asset?.contentTags??props.asset?.tags??''):'';tagAction.value=props.batchTagReplace?'REPLACE':'APPEND';machineTierAction.value='KEEP';selectedMachineTierIds.value=props.asset?.machineTierIds?.length?[...props.asset.machineTierIds]:(props.asset?.machineTierId?[props.asset.machineTierId]:[]);applicationInput.value=props.mode==='single'?(props.asset?.applicationTags||''):'';videoContentType.value=props.mode==='single'?(props.asset?.videoContentType||''):'';usageChannelsInput.value=props.mode==='single'?(props.asset?.videoUsageChannels||''):'';videoContentStatus.value=props.mode==='single'?(props.asset?.videoContentStatus||'待处理'):'待处理';featureInput.value=props.mode==='single'?(props.asset?.featureTags||''):'';demonstrationInput.value=props.mode==='single'?(props.asset?.demonstrationTags||''):'';replaceVideoMetadata.value=false;if(props.modelId){try{machineTiers.value=await fetchMachineTiers(props.modelId)}catch{machineTiers.value=[]}}},{immediate:true})
function toggle(input:{value:string},tag:string){const tags=parseCatalogTags(input.value),index=tags.indexOf(tag);if(index>=0)tags.splice(index,1);else tags.push(tag);input.value=tags.join('，')}
function toggleContentTag(tag:string){toggle(contentInput,tag)}function toggleApplicationTag(tag:string){toggle(applicationInput,tag)}function toggleUsageChannel(tag:string){toggle(usageChannelsInput,tag)}
function handleSubmit(){if(submitDisabled.value)return;const common={contentTags:parsedContentTags.value.join('，'),machineTierAction:machineTierAction.value,machineTierIds:machineTierAction.value==='ASSIGN'?[...selectedMachineTierIds.value]:[],applicationTags:parsedApplicationTags.value.join('，'),videoContentType:videoContentType.value,videoUsageChannels:parsedUsageChannels.value.join('，'),videoContentStatus:videoContentStatus.value,featureTags:parseCatalogTags(featureInput.value).join('，'),demonstrationTags:parseCatalogTags(demonstrationInput.value).join('，')};if(props.mode==='single'&&props.asset)emit('submit-single',{id:props.asset.id,...common});else emit('submit-batch',{...common,tagAction:tagAction.value,replaceVideoMetadata:replaceVideoMetadata.value});handleClose()}
function handleClose(){emit('close')}
</script>

<style scoped>
.asset-editor-content{display:flex;flex-direction:column;gap:16px}.asset-file-name{margin:0;overflow:hidden;color:var(--color-text-muted);font-size:12px;text-overflow:ellipsis;white-space:nowrap}.editor-section{padding:16px;border:1px solid var(--color-border);border-radius:var(--radius-lg)}.section-heading strong,.section-heading span{display:block}.section-heading span{margin-top:3px;color:var(--color-text-muted);font-size:12px}.tag-input,.scope-select,.field select{width:100%;min-height:40px;margin-top:10px;padding:0 11px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface);color:var(--color-text)}.tag-suggestions,.batch-tag-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.tag-suggestions button{padding:6px 10px;border:1px solid var(--color-border);border-radius:999px;background:var(--color-surface);cursor:pointer}.tag-suggestions button.active{border-color:var(--color-primary);background:var(--color-primary-bg);color:var(--color-primary)}.batch-tag-actions label,.replace-video{display:flex;align-items:center;gap:5px;font-size:13px}.scope-actions{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:12px}.scope-actions label{display:flex;align-items:flex-start;gap:8px;padding:11px;border:1px solid var(--color-border);border-radius:var(--radius-md);cursor:pointer}.scope-actions span{display:flex;flex-direction:column}.scope-actions small{margin-top:3px;color:var(--color-text-muted)}.empty-hint{color:var(--color-warning);font-size:12px}.replace-video{margin-top:12px}.field{display:flex;flex-direction:column;gap:5px;margin-top:12px;color:var(--color-text-secondary);font-size:12px}.field .tag-input,.field select{margin-top:0}.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:680px){.scope-actions,.field-grid{grid-template-columns:1fr}}
.tier-options{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.tier-options label{display:flex;align-items:center;gap:6px;padding:8px 10px;border:1px solid var(--color-border);border-radius:var(--radius-md)}
</style>
