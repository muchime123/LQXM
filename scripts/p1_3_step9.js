const fs = require("fs");
const p = "c:/Users/20676/source/LQXM/frontend/src/views/governance/MatchingCenterView.vue";
let c = fs.readFileSync(p, "utf8");

// 9) Replace entire receipt candidate <section class="panel"> block.
const OLD_RC_SECTION = `<section class="panel">
        <div class="panel-head">
          <div>
            <h2>收款金额兜底候选</h2>
            <p>未解析收款按「客户一致 + 90 天窗口 + 金额近似」生成候选，人工确认后回填分配。</p>
          </div>
          <el-radio-group v-model="receiptCandidateStatus" @change="loadReceiptCandidates"
            ><el-radio-button value="PENDING">待确认</el-radio-button
            ><el-radio-button value="CONFIRMED">已确认</el-radio-button
            ><el-radio-button value="REJECTED">已驳回</el-radio-button></el-radio-group
          >
        </div>
        <el-table :data="receiptCandidates" stripe>
          <el-table-column label="收款单" min-width="180"
            ><template #default="{ row }"
              ><b>{{ row.receiptNo }}</b
              ><div class="cell-sub">{{ row.customerName || '-' }} · {{ row.receiptDate || '-' }}</div></template
            ></el-table-column
          >
          <el-table-column prop="outboundNoSnapshot" label="引用出库编号" min-width="170" />
          <el-table-column label="金额" width="120" align="right"
            ><template #default="{ row }">{{ money(row.allocatedAmount) }}</template></el-table-column
          >
          <el-table-column label="匹配出库" min-width="180"
            ><template #default="{ row }"
              ><b>{{ row.outboundNo }}</b
              ><div class="cell-sub">未收 {{ money(row.outstandingAmount) }}</div></template
            ></el-table-column
          >
          <el-table-column prop="matchReason" label="依据" min-width="200" show-overflow-tooltip />
          <el-table-column label="置信度" width="90"
            ><template #default="{ row }">{{ row.confidence }}%</template></el-table-column
          >
          <el-table-column v-if="receiptCandidateStatus === 'PENDING'" label="操作" width="150" fixed="right"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="decideReceipt(row, true)">确认分配</el-button
              ><el-button link type="danger" @click="decideReceipt(row, false)">驳回</el-button></template
            ></el-table-column
          >
        </el-table>
        <el-empty v-if="!receiptCandidates.length" description="当前没有金额兜底候选" />
      </section>`;
if (!c.includes(OLD_RC_SECTION)) { console.error("entire rc section missing"); process.exit(1); }

const COLS = `          <el-table-column label="收款单" min-width="180"
            ><template #default="{ row }"
              ><b>{{ row.receiptNo }}</b
              ><div class="cell-sub">{{ row.customerName || '-' }} · {{ row.receiptDate || '-' }}</div></template
            ></el-table-column
          >
          <el-table-column prop="outboundNoSnapshot" label="引用出库编号" min-width="170" />
          <el-table-column label="金额" width="120" align="right"
            ><template #default="{ row }">{{ money(row.allocatedAmount) }}</template></el-table-column
          >
          <el-table-column label="匹配出库" min-width="180"
            ><template #default="{ row }"
              ><b>{{ row.outboundNo }}</b
              ><div class="cell-sub">未收 {{ money(row.outstandingAmount) }}</div></template
            ></el-table-column
          >
          <el-table-column prop="matchReason" label="依据" min-width="200" show-overflow-tooltip />
          <el-table-column label="置信度" width="130"
            ><template #default="{ row }">
              {{ row.confidence }}%
              <el-tag v-if="row.confidence >= 80" type="success" size="small" effect="plain" style="margin-left:4px">高</el-tag>
              <el-tag v-else-if="row.confidence >= 60" type="warning" size="small" effect="plain" style="margin-left:4px">中</el-tag>
              <el-tag v-else type="info" size="small" effect="plain" style="margin-left:4px">低</el-tag>
            </template></el-table-column
          >
          <el-table-column v-if="receiptCandidateStatus === 'PENDING'" label="操作" width="170" fixed="right"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="decideReceipt(row, true)">确认分配</el-button
              ><el-button link type="danger" @click="decideReceipt(row, false)">驳回</el-button></template
            ></el-table-column
          >`;

const SEG = function(name, fnName, segKey) {
  const confTag = name === "高" ? "high" : name === "中" ? "mid" : "low";
  const rangeLabel = name === "高" ? "≥80%" : name === "中" ? "60~79%" : "<60%";
  const tip = name === "高" ? "建议一键确认" : name === "中" ? "建议人工复核" : "建议逐条核对";
  return `          <div v-if="${fnName}().length" class="conf-segment conf-segment--${confTag}">
            <span class="dot"></span>
            <b>${name}置信度 ${rangeLabel}</b>（{{ ${fnName}().length }} 条 · ${tip}）
          </div>
          <el-table v-if="${fnName}().length" :data="${fnName}()" stripe @selection-change="syncReceiptSelection('${segKey}', $event)">
            <el-table-column type="selection" width="44" />
${COLS}
          </el-table>`;
};

const NEW_RC_SECTION = `<section class="panel">
        <div class="panel-head">
          <div>
            <h2>收款金额兜底候选</h2>
            <p>未解析收款按「客户一致 + 90 天窗口 + 金额近似」生成候选，人工确认后回填分配。≥80% 建议一键批量确认。</p>
          </div>
          <el-radio-group v-model="receiptCandidateStatus" @change="loadReceiptCandidates"
            ><el-radio-button value="PENDING">待确认 <el-badge v-if="receiptCandidateStatus === 'PENDING'" :value="receiptHighConf().length" type="success" class="inline-badge" /></el-radio-button
            ><el-radio-button value="CONFIRMED">已确认</el-radio-button
            ><el-radio-button value="REJECTED">已驳回</el-radio-button></el-radio-group
          >
        </div>
        <div v-if="receiptCandidateStatus === 'PENDING'" class="batch-bar">
          <el-button link type="primary" @click="toggleReceiptSelectAll">全选</el-button>
          <el-button link type="success" :disabled="!receiptHighConf().length" @click="selectReceiptByConfidence(80, null)">≥80% 一键勾选（{{ receiptHighConf().length }}）</el-button>
          <el-button link :disabled="!receiptMidConf().length" @click="selectReceiptByConfidence(60, 80)">60~79%（{{ receiptMidConf().length }}）</el-button>
          <el-button link type="warning" :disabled="!receiptLowConf().length" @click="selectReceiptByConfidence(null, 60)"><60%（{{ receiptLowConf().length }}）</el-button>
          <span class="batch-sep"></span>
          <span class="batch-count">已选 <b>{{ selectedReceiptCandidates.length }}</b> 条</span>
          <el-button size="small" type="primary" :disabled="!selectedReceiptCandidates.length" :loading="receiptBatchDeciding" @click="batchDecideReceipt(true)">批量确认分配</el-button>
          <el-button size="small" type="danger" plain :disabled="!selectedReceiptCandidates.length" :loading="receiptBatchDeciding" @click="batchDecideReceipt(false)">批量驳回</el-button>
        </div>
        <template v-if="receiptCandidateStatus === 'PENDING'">
${SEG("高", "receiptHighConf", "high")}
${SEG("中", "receiptMidConf", "mid")}
${SEG("低", "receiptLowConf", "low")}
        </template>
        <el-table v-else :data="receiptCandidates" stripe>
${COLS}
        </el-table>
        <el-empty v-if="!receiptCandidates.length" description="当前没有金额兜底候选" />
      </section>`;
c = c.replace(OLD_RC_SECTION, NEW_RC_SECTION);
console.log("9 rc panel ok");
fs.writeFileSync(p, c, "utf8");
