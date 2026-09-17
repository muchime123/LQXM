<template>
  <div class="relation-page">
    <header class="relation-head">
      <div>
        <p class="eyebrow">H3YUN · OUTBOUND RELATIONS</p>
        <h1>销售出库关联工作台</h1>
        <p>一行代表一张氚云销售出库。集中检查客户、员工、产品归类和仓库关系；原始台账保持只读。</p>
      </div>
      <div class="relation-head__actions">
        <el-button type="primary" :loading="autoOrganizing" @click="runAutoOrganize">重新自动整理</el-button>
        <el-button @click="relation='incomplete';reload(1)">查看待人工处理</el-button>
        <el-button @click="rulesVisible=true">自动整理规则</el-button>
        <el-button @click="router.push({ path: '/h3-imports', query: { tab: 'ledger', ledger: 'outbound' } })">查看原始台账</el-button>
      </div>
    </header>

    <section class="metric-grid">
      <button v-for="card in cards" :key="card.key" type="button" :class="['metric-card', { active: relation === card.key }]" @click="selectRelation(card.key)">
        <span>{{ card.label }}</span><b>{{ number(card.value) }}</b><small>{{ card.hint }}</small>
      </button>
    </section>

    <section class="work-panel">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索出库单、客户、电话、地址、业务员或产品" @keyup.enter="reload(1)" @clear="reload(1)">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="reload(1)">查询</el-button>
        <el-radio-group v-model="relation" @change="reload(1)">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="incomplete">仅待处理</el-radio-button>
          <el-radio-button value="complete">已完整</el-radio-button>
        </el-radio-group>
      </div>
      <el-alert type="info" :closable="false" show-icon title="关联状态说明" description="草稿销售出库不参与关联缺失统计和自动治理，原始数据仍保留在台账中；正式出库缺少产品归类不会影响销售金额、真实收款和月度提成，只影响按产品或库存的统计。" />
      <el-table v-loading="loading" :data="items" stripe @row-dblclick="openDetail">
        <el-table-column prop="outboundNo" label="销售出库" width="175" show-overflow-tooltip />
        <el-table-column prop="shipDate" label="发货日期" width="110"><template #default="{ row }">{{ date(row.shipDate) }}</template></el-table-column>
        <el-table-column label="客户" min-width="210">
          <template #default="{ row }"><div class="source-target"><span>{{ row.customerName || '—' }}</span><RouterLink v-if="row.customerId" :to="{ path:'/customers', query:{ customerId:row.customerId, keyword:row.linkedCustomerName || row.customerName } }">{{ row.linkedCustomerName || '查看档案' }}</RouterLink><b v-else>待关联</b></div></template>
        </el-table-column>
        <el-table-column label="电话 / 地址" min-width="220"><template #default="{ row }"><div class="contact"><span>{{ row.contactPhone || '—' }}</span><small>{{ row.customerAddress || '—' }}</small></div></template></el-table-column>
        <el-table-column label="员工" width="150"><template #default="{ row }"><div class="source-target"><span>{{ row.salespersonName || '—' }}</span><em :class="{ missing: !row.employeeId }">{{ row.linkedEmployeeName || '待关联' }}</em></div></template></el-table-column>
        <el-table-column label="库存产品" width="130" align="center"><template #default="{ row }"><el-tag :type="productComplete(row) ? 'success' : 'warning'">{{ row.linkedProductItemCount || 0 }}/{{ row.productItemCount || 0 }} 行</el-tag></template></el-table-column>
        <el-table-column label="仓库" width="130"><template #default="{ row }"><div class="source-target"><span>{{ row.warehouse || '—' }}</span><em :class="{ missing: row.warehouse && !row.linkedWarehouseId }">{{ row.linkedWarehouseName || (row.warehouse ? '待关联' : '未填写') }}</em></div></template></el-table-column>
        <el-table-column prop="salesAmount" label="销售金额" width="125" align="right"><template #default="{ row }">{{ money(row.salesAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="100" align="center"><template #default="{ row }"><el-tag :type="rowComplete(row) ? 'success' : 'warning'" effect="plain">{{ rowComplete(row) ? '完整' : '待处理' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="75" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next" @current-change="reload()" @size-change="reload(1)" />
    </section>

    <el-dialog v-model="rulesVisible" title="导入后自动整理规则" width="min(820px, 94vw)">
      <el-alert type="success" :closable="false" show-icon title="销售出库确认导入后，系统会自动执行这些规则" description="规则可重复执行，不会覆盖已确认关系；重新自动整理只用于失败重试或主档补充后的再次匹配。" />
      <div class="rule-list">
        <div><b>员工与仓库</b><span>来源名称只能唯一精确命中一个在职员工或启用仓库。</span></div>
        <div><b>关联已有客户</b><span>按已确认身份簇、唯一电话或无新电话冲突的历史确认映射关联；客户名称不会单独决定客户。</span></div>
        <div><b>自动建立客户</b><span>正式出库恰好提取到一个有效电话，且该电话尚未属于任何有效客户档案；同一电话的记录合并建立一个客户。</span></div>
        <div><b>多电话客户</b><span>客户名称、地址和完整电话集合全部一致才归为一簇；号码指向多个客户时保留人工复核。</span></div>
        <div><b>库存产品</b><span>先确定仓库，再按产品编号，或名称、规格、单位组合查找；只有唯一候选才自动归类。</span></div>
      </div>
      <el-alert type="warning" :closable="false" show-icon title="这些情况不会自动处理" description="草稿出库、无有效电话、电话被多个客户共用、多个电话指向不同客户、产品存在多个候选或没有候选，都会留在待人工处理列表。" />
      <details class="advanced-actions">
        <summary>查看各步骤预览</summary>
        <div>
          <el-button size="small" @click="rulesVisible=false;openExactPreview()">员工与仓库</el-button>
          <el-button size="small" @click="rulesVisible=false;openCustomerPreview()">已有客户</el-button>
          <el-button size="small" @click="rulesVisible=false;openNewCustomerPreview()">新客户</el-button>
          <el-button size="small" @click="rulesVisible=false;openMultiPhonePreview()">多电话客户</el-button>
          <el-button size="small" @click="rulesVisible=false;openProductPreview()">库存产品</el-button>
          <el-button size="small" @click="rulesVisible=false;openCustomerExceptions()">客户异常</el-button>
        </div>
      </details>
    </el-dialog>

    <el-dialog v-model="customerVisible" title="客户安全关联预览" width="min(900px, 94vw)" destroy-on-close>
      <div v-loading="customerLoading">
        <el-alert type="info" :closable="false" show-icon title="电话优先，名称不单独决定客户" description="只处理已确认身份簇、唯一电话和已有确认映射。同名不同电话、共享电话、泛化姓名和仅地址相似的记录不会自动关联。" />
        <div class="product-summary"><span class="ok">可安全关联 <b>{{ customerPreview?.outboundCount || 0 }}</b> 张出库</span><span>电话唯一 <b>{{ customerPreview?.byMethod?.UNIQUE_PHONE || 0 }}</b></span><span>确认身份簇 <b>{{ customerPreview?.byMethod?.CONFIRMED_CLUSTER || 0 }}</b></span><span>已有映射 <b>{{ customerPreview?.byMethod?.RESOLVED_IDENTITY || 0 }}</b></span></div>
        <el-table :data="customerPreview?.items || []" size="small" border max-height="420">
          <el-table-column prop="outboundNo" label="销售出库" width="165" />
          <el-table-column label="来源客户" min-width="180"><template #default="{ row }"><div class="contact"><span>{{ row.customerName }}</span><small>{{ row.contactPhone || '无电话' }}</small></div></template></el-table-column>
          <el-table-column prop="targetName" label="关联客户档案" min-width="180" />
          <el-table-column prop="reason" label="匹配依据" min-width="170" />
        </el-table>
      </div>
      <template #footer><el-button @click="customerVisible=false">取消</el-button><el-button type="primary" :loading="customerApplying" :disabled="!customerPreview?.outboundCount" @click="applyCustomer">确认关联 {{ customerPreview?.outboundCount || 0 }} 张</el-button></template>
    </el-dialog>

    <el-dialog v-model="multiPhoneVisible" title="多电话客户身份簇预览" width="min(980px, 94vw)" destroy-on-close>
      <div v-loading="multiPhoneLoading">
        <el-alert type="warning" :closable="false" show-icon title="多个电话保留在同一个客户档案中" description="仅将客户名称、地址和完整电话集合完全一致的出库归为一簇。若其中任一电话唯一指向同一个现有档案则关联该档案；全部电话均为新号码时建立一个客户并保存全部电话；指向多个档案的冲突簇不会处理。" />
        <div class="product-summary">
          <span>共 <b>{{ multiPhonePreview?.clusterCount || 0 }}</b> 簇</span>
          <span class="ok">关联既有 <b>{{ multiPhonePreview?.linkClusters || 0 }}</b> 簇</span>
          <span class="ok">新建客户 <b>{{ multiPhonePreview?.createClusters || 0 }}</b> 个</span>
          <span class="warn">保留复核 <b>{{ multiPhonePreview?.reviewClusters || 0 }}</b> 簇</span>
          <span>覆盖出库 <b>{{ multiPhonePreview?.actionableOutbounds || 0 }}</b> 张</span>
        </div>
        <el-table :data="multiPhonePreview?.items || []" size="small" border max-height="460">
          <el-table-column prop="customerName" label="客户名称" min-width="160" show-overflow-tooltip />
          <el-table-column label="电话集合" min-width="210"><template #default="{ row }">{{ row.phones.join('、') }}</template></el-table-column>
          <el-table-column prop="address" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column prop="outboundCount" label="出库数" width="75" align="right" />
          <el-table-column label="处理建议" min-width="155"><template #default="{ row }"><el-tag :type="row.decision === 'REVIEW' ? 'warning' : 'success'" effect="plain">{{ row.decision === 'LINK' ? `关联：${row.targetName}` : row.decision === 'CREATE' ? '建立客户并保留全部电话' : '存在冲突，暂不处理' }}</el-tag></template></el-table-column>
        </el-table>
      </div>
      <template #footer><el-button @click="multiPhoneVisible=false">取消</el-button><el-button type="primary" :loading="multiPhoneApplying" :disabled="!multiPhonePreview?.actionableOutbounds" @click="applyMultiPhone">确认处理 {{ multiPhonePreview?.actionableOutbounds || 0 }} 张</el-button></template>
    </el-dialog>

    <el-dialog v-model="customerExceptionVisible" title="剩余客户关联异常" width="min(980px, 94vw)" destroy-on-close>
      <div v-loading="customerExceptionLoading">
        <el-alert type="info" :closable="false" show-icon title="这里只展示无法安全批量处理的记录" description="系统已排除可以通过确认身份、唯一电话或唯一电话建档处理的候选。请先完成前两个客户按钮；这里的记录再按异常类型处理。表格最多展示前 500 张。" />
        <div class="product-summary">
          <span class="warn">真正待人工判断 <b>{{ customerExceptionPreview?.exceptionCount || 0 }}</b> 张</span>
          <span>无有效电话 <b>{{ customerExceptionPreview?.byCategory?.NO_VALID_PHONE || 0 }}</b></span>
          <span>多个电话 <b>{{ customerExceptionPreview?.byCategory?.MULTIPLE_PHONES || 0 }}</b></span>
          <span>电话属于多个档案 <b>{{ customerExceptionPreview?.byCategory?.SHARED_PHONE || 0 }}</b></span>
        </div>
        <el-table :data="customerExceptionPreview?.items || []" size="small" border max-height="460">
          <el-table-column prop="outboundNo" label="销售出库" width="165" />
          <el-table-column prop="customerName" label="来源客户" min-width="150" show-overflow-tooltip />
          <el-table-column label="联系电话" min-width="155"><template #default="{ row }"><div class="contact"><span>{{ row.contactPhone || '—' }}</span><small>{{ row.phones.join('、') || '未识别' }}</small></div></template></el-table-column>
          <el-table-column prop="customerAddress" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="异常类型" width="130"><template #default="{ row }"><el-tag type="warning" effect="plain">{{ exceptionLabel(row.category) }}</el-tag></template></el-table-column>
          <el-table-column prop="reason" label="原因" min-width="190" />
        </el-table>
      </div>
      <template #footer><el-button type="primary" @click="customerExceptionVisible=false">知道了</el-button></template>
    </el-dialog>

    <el-dialog v-model="newCustomerVisible" title="按电话安全建立客户档案" width="min(920px, 94vw)" destroy-on-close>
      <div v-loading="newCustomerLoading">
        <el-alert type="warning" :closable="false" show-icon title="将建立新的客户档案" description="仅处理恰好包含一个有效电话、且该电话尚未属于任何客户档案的出库。相同电话归为一个客户；同名不同电话及泛化名称会附加电话尾号。下表最多展示前 500 个候选，确认按钮会处理上方统计的全部候选；多电话、无电话及冲突电话不会处理。" />
        <div class="product-summary"><span class="ok">拟新建客户 <b>{{ newCustomerPreview?.clusterCount || 0 }}</b> 个</span><span>预计关联出库 <b>{{ newCustomerPreview?.outboundCount || 0 }}</b> 张</span></div>
        <el-table :data="newCustomerPreview?.items || []" size="small" border max-height="430">
          <el-table-column prop="customerName" label="拟建客户名称" min-width="190" />
          <el-table-column prop="phone" label="唯一电话" width="145" />
          <el-table-column label="来源名称" min-width="220"><template #default="{ row }">{{ row.sourceNames.join('、') }}</template></el-table-column>
          <el-table-column prop="addressCount" label="地址数" width="80" align="right" />
          <el-table-column prop="outboundCount" label="出库数" width="80" align="right" />
        </el-table>
      </div>
      <template #footer><el-button @click="newCustomerVisible=false">取消</el-button><el-button type="primary" :loading="newCustomerApplying" :disabled="!newCustomerPreview?.clusterCount" @click="applyNewCustomers">确认建立 {{ newCustomerPreview?.clusterCount || 0 }} 个客户</el-button></template>
    </el-dialog>

    <H3LedgerDetailDrawer v-model="detailVisible" ledger-type="outbound" :loading="detailLoading" :data="detailData" :error="detailError" @updated="reloadDetail" />
    <el-dialog v-model="exactVisible" title="唯一精确关联预览" width="760px" destroy-on-close>
      <div v-loading="exactLoading">
        <el-alert type="success" :closable="false" show-icon title="只处理唯一精确匹配" description="业务员名称必须唯一命中一名在职员工；来源仓库名称必须唯一命中一条启用仓库。重名、近似名称和无候选记录不会处理，保存的规则可在重导后复用。" />
        <h3>员工关联 · {{ exactPreview?.employeeSourceCount || 0 }} 个名称 / {{ exactPreview?.employeeOutboundCount || 0 }} 张出库</h3>
        <el-table :data="exactPreview?.employees || []" size="small" border max-height="220">
          <el-table-column prop="sourceName" label="来源业务员" min-width="180" /><el-table-column prop="targetName" label="员工档案" min-width="180" />
          <el-table-column prop="outboundCount" label="影响出库" width="100" align="right" /><el-table-column label="依据" width="120"><template #default>姓名唯一精确</template></el-table-column>
        </el-table>
        <h3>仓库关联 · {{ exactPreview?.warehouseSourceCount || 0 }} 个名称 / {{ exactPreview?.warehouseOutboundCount || 0 }} 张出库</h3>
        <el-table :data="exactPreview?.warehouses || []" size="small" border max-height="180">
          <el-table-column prop="sourceName" label="来源仓库" min-width="180" /><el-table-column prop="targetName" label="正式仓库" min-width="180" />
          <el-table-column prop="outboundCount" label="影响出库" width="100" align="right" /><el-table-column label="依据" width="120"><template #default>名称唯一精确</template></el-table-column>
        </el-table>
      </div>
      <template #footer><el-button @click="exactVisible=false">取消</el-button><el-button type="primary" :loading="exactApplying" :disabled="!exactActionCount" @click="applyExact">确认关联 {{ exactActionCount }} 张出库</el-button></template>
    </el-dialog>

    <el-dialog v-model="productVisible" title="产品归类建议" width="min(900px, 94vw)" destroy-on-close>
      <div v-loading="productLoading">
        <el-alert type="info" :closable="false" show-icon title="先关联仓库，再匹配该仓库中的库存产品" description="优先匹配现有库存产品；当前余额中没有时，再校验产品信息与对应仓库明细中的氚云产品编号。历史上已出完、当前库存为零的产品可补建产品身份，但不会增加库存数量。" />
        <div class="product-summary">
          <span>未归类 <b>{{ productPreview?.groupCount || 0 }}</b> 组 / <b>{{ productPreview?.lineCount || 0 }}</b> 行</span>
          <span class="ok">可归类 <b>{{ productPreview?.suggestedGroups || 0 }}</b> 组 / <b>{{ productPreview?.suggestedLines || 0 }}</b> 行</span>
          <span class="warn">多候选 <b>{{ productPreview?.ambiguousGroups || 0 }}</b> 组</span>
          <span class="warn">无候选 <b>{{ productPreview?.notFoundGroups || 0 }}</b> 组</span>
          <span class="warn">等待仓库 <b>{{ productPreview?.waitingWarehouseGroups || 0 }}</b> 组</span>
        </div>
        <el-table :data="productPreview?.items || []" size="small" border max-height="380">
          <el-table-column label="来源产品" min-width="220">
            <template #default="{ row }">
              <div class="contact"><span>{{ row.sourceName || '—' }}</span><small>{{ row.sourceCode || '（无编码）' }}</small></div>
            </template>
          </el-table-column>
          <el-table-column label="规格 / 单位" min-width="170">
            <template #default="{ row }"><div class="contact"><span>{{ row.sourceSpec || '—' }}</span><small>{{ row.sourceUnit || '—' }}</small></div></template>
          </el-table-column>
          <el-table-column prop="lineCount" label="明细行" width="80" align="right" />
          <el-table-column label="建议归类" min-width="180">
            <template #default="{ row }">
              <span v-if="row.decision === 'SUGGEST'">{{ row.targetName }}</span>
              <el-tag v-else :type="row.decision === 'AMBIGUOUS' || row.decision === 'WAIT_WAREHOUSE' ? 'warning' : 'info'" size="small">
                {{ row.decision === 'WAIT_WAREHOUSE' ? '等待仓库关联' : row.candidateCount > 1 ? `${row.candidateCount} 个候选` : '无候选' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="依据" width="120">
            <template #default="{ row }">{{ matchLabel(row.matchMethod) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="productVisible=false">取消</el-button>
        <el-button type="primary" :loading="productApplying" :disabled="!productPreview?.suggestedLines" @click="applyProduct">确认归类 {{ productPreview?.suggestedLines || 0 }} 行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { applyOutboundCustomerRelations, applyOutboundExactRelations, applyOutboundMultiPhone, applyOutboundNewCustomers, applyOutboundProductClassify, autoOrganizeOutboundRelations, fetchLedgerDetail, fetchOutboundCustomerExceptions, fetchOutboundCustomerRelationPreview, fetchOutboundExactRelationPreview, fetchOutboundMultiPhonePreview, fetchOutboundNewCustomerPreview, fetchOutboundProductClassifyPreview, fetchOutboundRelations, type CustomerExceptionPreview, type CustomerRelationPreview, type ExactRelationPreview, type MultiPhonePreview, type NewCustomerClusterPreview, type OutboundRelationCounts, type ProductClassifyPreview } from '@/api/business/orders'
import H3LedgerDetailDrawer from '@/components/h3/H3LedgerDetailDrawer.vue'
import { notify } from '@/composables/ui/useToast'
import type { LedgerDetailResult, LedgerRow } from '@/types/h3Import'

const router = useRouter()
const rulesVisible = ref(false)
const autoOrganizing = ref(false)
const keyword = ref('')
const relation = ref('all')
const page = ref(1)
const size = ref(50)
const total = ref(0)
const items = ref<LedgerRow[]>([])
const loading = ref(false)
const counts = ref<OutboundRelationCounts>({ total:0,missingCustomer:0,missingEmployee:0,missingProduct:0,missingWarehouse:0,complete:0,draftExcluded:0 })
const cards = computed(() => [
  { key:'all',label:'全部销售出库',value:counts.value.total,hint:'氚云核心事实' },
  { key:'customer',label:'缺客户档案',value:counts.value.missingCustomer,hint:'优先按电话判断' },
  { key:'employee',label:'缺员工档案',value:counts.value.missingEmployee,hint:'业务员归属' },
  { key:'product',label:'缺库存产品',value:counts.value.missingProduct,hint:'受影响的出库单' },
  { key:'warehouse',label:'缺仓库档案',value:counts.value.missingWarehouse,hint:'来源仓库名称' },
  { key:'complete',label:'核心关联完整',value:counts.value.complete,hint:'可直接下钻' }
  ,{ key:'draft',label:'草稿暂不治理',value:counts.value.draftExcluded,hint:'保留原始台账，后续单独处理' }
])
function selectRelation(key:string){ relation.value=key; reload(1) }
function productComplete(row:LedgerRow){ return Number(row.linkedProductItemCount||0)===Number(row.productItemCount||0) }
function rowComplete(row:LedgerRow){ return !!row.customerId && !!row.employeeId && productComplete(row) && !(row.warehouse && !row.linkedWarehouseId) }
function number(v:unknown){ return Number(v||0).toLocaleString('zh-CN') }
function money(v:unknown){ return Number(v||0).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2}) }
function date(v:unknown){ return v ? String(v).slice(0,10) : '—' }
async function reload(target?:number){ if(target) page.value=target; loading.value=true; try { const r=await fetchOutboundRelations({keyword:keyword.value||undefined,relation:relation.value,page:page.value,size:size.value}); items.value=r.items||[];total.value=r.total||0;counts.value=r.counts||counts.value } catch(e){notify.error((e as Error).message||'关联工作台加载失败')} finally{loading.value=false} }
async function runAutoOrganize(){autoOrganizing.value=true;try{const r=await autoOrganizeOutboundRelations();const created=Number(r.newCustomers.createdCustomers||0)+Number(r.multiPhoneCustomers.createdCustomers||0);const linked=Number(r.customerLinks.customerOutbounds||0)+Number(r.newCustomers.customerOutbounds||0)+Number(r.multiPhoneCustomers.customerOutbounds||0);notify.success(`自动整理完成：新建客户 ${created} 个，关联客户 ${linked} 张，归类产品 ${r.products.productLines||0} 行`);await reload()}catch(e){notify.error((e as Error).message||'自动整理失败')}finally{autoOrganizing.value=false}}
const detailVisible=ref(false),detailLoading=ref(false),detailError=ref('')
const detailData=ref<LedgerDetailResult|null>(null)
async function openDetail(row:LedgerRow){ detailVisible.value=true;detailLoading.value=true;detailData.value=null;detailError.value='';try{detailData.value=await fetchLedgerDetail('outbound',Number(row.id))}catch(e){detailError.value=(e as Error).message||'详情加载失败'}finally{detailLoading.value=false} }
async function reloadDetail(){const id=Number(detailData.value?.row?.id);if(id)detailData.value=await fetchLedgerDetail('outbound',id);await reload()}
const exactVisible=ref(false),exactLoading=ref(false),exactApplying=ref(false)
const exactPreview=ref<ExactRelationPreview|null>(null)
const exactActionCount=computed(()=>Number(exactPreview.value?.employeeOutboundCount||0)+Number(exactPreview.value?.warehouseOutboundCount||0))
async function openExactPreview(){exactVisible.value=true;exactLoading.value=true;try{exactPreview.value=await fetchOutboundExactRelationPreview()}catch(e){notify.error((e as Error).message||'自动关联预览失败')}finally{exactLoading.value=false}}
async function applyExact(){exactApplying.value=true;try{const r=await applyOutboundExactRelations();notify.success(`已关联员工 ${r.employeeOutbounds} 张出库、仓库 ${r.warehouseOutbounds} 张出库`);exactVisible.value=false;await reload()}catch(e){notify.error((e as Error).message||'自动关联失败')}finally{exactApplying.value=false}}
const customerVisible=ref(false),customerLoading=ref(false),customerApplying=ref(false)
const customerPreview=ref<CustomerRelationPreview|null>(null)
async function openCustomerPreview(){customerVisible.value=true;customerLoading.value=true;try{customerPreview.value=await fetchOutboundCustomerRelationPreview()}catch(e){notify.error((e as Error).message||'客户关联预览失败')}finally{customerLoading.value=false}}
async function applyCustomer(){customerApplying.value=true;try{const r=await applyOutboundCustomerRelations();notify.success(`已安全关联 ${r.customerOutbounds} 张出库的客户档案`);customerVisible.value=false;await reload()}catch(e){notify.error((e as Error).message||'客户关联失败')}finally{customerApplying.value=false}}
const newCustomerVisible=ref(false),newCustomerLoading=ref(false),newCustomerApplying=ref(false)
const newCustomerPreview=ref<NewCustomerClusterPreview|null>(null)
async function openNewCustomerPreview(){newCustomerVisible.value=true;newCustomerLoading.value=true;try{newCustomerPreview.value=await fetchOutboundNewCustomerPreview()}catch(e){notify.error((e as Error).message||'客户建档预览失败')}finally{newCustomerLoading.value=false}}
async function applyNewCustomers(){newCustomerApplying.value=true;try{const r=await applyOutboundNewCustomers();notify.success(`已建立 ${r.createdCustomers} 个客户，关联 ${r.customerOutbounds} 张出库`);newCustomerVisible.value=false;await reload()}catch(e){notify.error((e as Error).message||'客户批量建档失败')}finally{newCustomerApplying.value=false}}
const customerExceptionVisible=ref(false),customerExceptionLoading=ref(false)
const customerExceptionPreview=ref<CustomerExceptionPreview|null>(null)
function exceptionLabel(category:string){return category==='NO_VALID_PHONE'?'无有效电话':category==='MULTIPLE_PHONES'?'多个电话':category==='SHARED_PHONE'?'电话冲突':'未解析'}
async function openCustomerExceptions(){customerExceptionVisible.value=true;customerExceptionLoading.value=true;try{customerExceptionPreview.value=await fetchOutboundCustomerExceptions()}catch(e){notify.error((e as Error).message||'客户异常加载失败')}finally{customerExceptionLoading.value=false}}
const multiPhoneVisible=ref(false),multiPhoneLoading=ref(false),multiPhoneApplying=ref(false)
const multiPhonePreview=ref<MultiPhonePreview|null>(null)
async function openMultiPhonePreview(){multiPhoneVisible.value=true;multiPhoneLoading.value=true;try{multiPhonePreview.value=await fetchOutboundMultiPhonePreview()}catch(e){notify.error((e as Error).message||'多电话身份簇加载失败')}finally{multiPhoneLoading.value=false}}
async function applyMultiPhone(){multiPhoneApplying.value=true;try{const r=await applyOutboundMultiPhone();notify.success(`已关联既有 ${r.linkedClusters} 簇、新建 ${r.createdCustomers} 个客户，覆盖 ${r.customerOutbounds} 张出库`);multiPhoneVisible.value=false;await reload()}catch(e){notify.error((e as Error).message||'多电话身份簇处理失败')}finally{multiPhoneApplying.value=false}}
const productVisible=ref(false),productLoading=ref(false),productApplying=ref(false)
const productPreview=ref<ProductClassifyPreview|null>(null)
function matchLabel(method:ProductClassifyPreview['items'][number]['matchMethod']){return method==='CODE'?'库存产品编码':method==='CATALOG_CODE'?'产品＋仓库台账编号':method==='WAREHOUSE_STOCK_CODE'?'仓库明细编号':method==='WAREHOUSE_STOCK_NSU'?'仓库明细名称规格单位':method==='WAREHOUSE_STOCK_NAME'?'仓库明细唯一名称':method==='WAREHOUSE_STOCK_CORE_NAME'?'核心名称规格单位':method==='NAME_SPEC_UNIT'?'名称规格单位':method==='NAME'?'仅名称':'—'}
async function openProductPreview(){productVisible.value=true;productLoading.value=true;try{productPreview.value=await fetchOutboundProductClassifyPreview()}catch(e){notify.error((e as Error).message||'产品归类预览失败')}finally{productLoading.value=false}}
async function applyProduct(){productApplying.value=true;try{const r=await applyOutboundProductClassify();notify.success(`已归类 ${r.productLines} 行明细，覆盖 ${r.productOutbounds} 张出库`);productVisible.value=false;await reload()}catch(e){notify.error((e as Error).message||'产品归类失败')}finally{productApplying.value=false}}
await reload()
</script>

<style scoped>
.relation-head__actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}.el-dialog h3{margin:16px 0 8px;font-size:14px}.rule-list{display:grid;gap:10px;margin:16px 0}.rule-list>div{display:grid;grid-template-columns:120px 1fr;gap:12px;padding:11px 12px;border:1px solid var(--el-border-color-lighter);border-radius:8px}.rule-list span{color:var(--el-text-color-secondary);line-height:1.6}.advanced-actions{margin-top:16px;color:var(--el-text-color-secondary)}.advanced-actions summary{cursor:pointer}.advanced-actions>div{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.relation-page{padding:22px;display:flex;flex-direction:column;gap:16px}.relation-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.relation-head h1{margin:2px 0 6px;font-size:25px}.relation-head p{margin:0;color:var(--el-text-color-secondary)}.eyebrow{font-size:11px!important;color:var(--el-color-primary)!important;font-weight:700;letter-spacing:.12em}.metric-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px}.metric-card{text-align:left;border:1px solid var(--el-border-color-lighter);border-radius:10px;background:var(--el-bg-color);padding:12px;cursor:pointer}.metric-card:hover,.metric-card.active{border-color:var(--el-color-primary);box-shadow:0 0 0 1px var(--el-color-primary-light-5)}.metric-card span,.metric-card small{display:block;color:var(--el-text-color-secondary)}.metric-card b{display:block;margin:6px 0;font-size:24px;color:var(--el-text-color-primary)}.work-panel{padding:16px;border:1px solid var(--el-border-color-lighter);border-radius:12px;background:var(--el-bg-color)}.toolbar{display:flex;gap:10px;align-items:center;margin-bottom:12px}.toolbar .el-input{width:390px}.work-panel>.el-alert{margin-bottom:12px}.el-pagination{justify-content:flex-end;margin-top:14px}.source-target,.contact{display:flex;flex-direction:column;gap:3px}.source-target a{color:var(--el-color-primary);text-decoration:none;font-size:12px}.source-target b,.source-target em.missing{color:var(--el-color-warning);font-size:12px;font-style:normal}.source-target em{color:var(--el-color-success);font-size:12px;font-style:normal}.contact small{color:var(--el-text-color-secondary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.product-summary{display:flex;flex-wrap:wrap;gap:18px;margin:12px 0;font-size:13px;color:var(--el-text-color-secondary)}.product-summary b{color:var(--el-text-color-primary)}.product-summary .ok b{color:var(--el-color-success)}.product-summary .warn b{color:var(--el-color-warning)}@media(max-width:1200px){.metric-grid{grid-template-columns:repeat(3,1fr)}}
</style>
