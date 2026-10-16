// Read-only candidate analysis for warehouse rows whose product code is absent from H3 product info.
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');

const norm = (value) => String(value ?? '').replace(/[\s\u3000\u00a0]+/g, '').toLowerCase();
const csvCell = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`;
const key = (...parts) => parts.map(norm).join('\u0001');

async function main() {
  const lines = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/);
  const env = Object.fromEntries(lines.filter((line) => /^[A-Z_]+=/.test(line)).map((line) => {
    const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/, ''));
  const db = await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,
    database:url.pathname.slice(1),user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||''});
  try {
    await db.query('START TRANSACTION READ ONLY');
    const [catalog] = await db.query('SELECT id,product_code,product_name,specification,unit,purchase_price,last_seen_batch_id FROM h3_product_catalog');
    const [stock] = await db.query(`SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name,ws.specification,ws.unit,ws.quantity,ws.last_seen_batch_id
      FROM h3_warehouse_stock ws LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
      WHERE pc.id IS NULL ORDER BY (ws.quantity>0) DESC,ws.quantity DESC,ws.warehouse,ws.product_code`);
    const byNsu = new Map(), byNameUnit = new Map(), byName = new Map();
    for (const item of catalog) {
      if (!norm(item.product_name)) continue;
      for (const [map, index] of [[byNsu,key(item.product_name,item.specification,item.unit)],
        [byNameUnit,key(item.product_name,item.unit)],[byName,key(item.product_name)]]) {
        if (!map.has(index)) map.set(index, []);
        map.get(index).push(item);
      }
    }
    const columns = ['优先级','仓库','仓库编号','仓库名称','仓库规格','仓库单位','仓库数量','匹配层级','候选数',
      '候选1编号','候选1名称','候选1规格','候选1单位','候选1采购价','候选2编号','候选2名称','候选3编号','候选3名称',
      '仓库台账ID','仓库覆盖批次','核查结论'];
    const counts = {positive:0,zero:0,negative:0,uniqueNsu:0,ambiguousNsu:0,uniqueNameUnit:0,uniqueName:0,ambiguousOther:0,noCandidate:0};
    const output = [columns];
    for (const row of stock) {
      const qty = Number(row.quantity ?? 0);
      counts[qty>0?'positive':qty<0?'negative':'zero']++;
      let tier = '无候选', candidates = [];
      const name = norm(row.product_name);
      if (name) {
        for (const [label, matches] of [['名称+规格+单位',byNsu.get(key(row.product_name,row.specification,row.unit))],
          ['名称+单位',byNameUnit.get(key(row.product_name,row.unit))],['仅名称',byName.get(key(row.product_name))]]) {
          if (matches?.length) { tier=label; candidates=matches; break; }
        }
      }
      if (tier==='无候选') counts.noCandidate++;
      else if (tier==='名称+规格+单位') counts[candidates.length===1?'uniqueNsu':'ambiguousNsu']++;
      else if (candidates.length===1) counts[tier==='名称+单位'?'uniqueNameUnit':'uniqueName']++;
      else counts.ambiguousOther++;
      const c = candidates.slice(0,3);
      output.push([qty>0?'高':qty<0?'负数待核':'低',row.warehouse,row.product_code,row.product_name,
        row.specification,row.unit,row.quantity,tier,candidates.length,c[0]?.product_code,c[0]?.product_name,
        c[0]?.specification,c[0]?.unit,c[0]?.purchase_price,c[1]?.product_code,c[1]?.product_name,
        c[2]?.product_code,c[2]?.product_name,row.id,row.last_seen_batch_id,'']);
    }
    const target = path.join(__dirname, '../docs/氚云库存缺产品信息候选核对-20261006.csv');
    fs.writeFileSync(target,'\uFEFF'+output.map((fields)=>fields.map(csvCell).join(',')).join('\r\n')+'\r\n','utf8');
    const positiveTarget=path.join(__dirname,'../docs/氚云非零库存缺产品信息候选-20261006.csv');
    const positiveRows=output.filter((fields,index)=>index===0 || fields[0]==='高');
    fs.writeFileSync(positiveTarget,'\uFEFF'+positiveRows.map((fields)=>fields.map(csvCell).join(',')).join('\r\n')+'\r\n','utf8');
    const [priceRows] = await db.query(`SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name,ws.specification,ws.unit,ws.quantity,
        pc.purchase_price,pc.product_category,ws.last_seen_batch_id
      FROM h3_warehouse_stock ws JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
      WHERE ws.quantity>0 AND (pc.purchase_price IS NULL OR pc.purchase_price<=0)
        AND TRIM(COALESCE(pc.product_category,'')) NOT IN ('费用','制版费','税点','运费')
        AND TRIM(COALESCE(ws.product_code,'')) NOT IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费')
      ORDER BY ws.quantity DESC`);
    const priceOutput = [['仓库','仓库编号','仓库名称','规格','单位','数量','当前目录采购价','其他编号候选匹配层级',
      '其他编号候选数','候选1编号','候选1名称','候选1采购价','候选2编号','候选2名称','候选2采购价','仓库台账ID','判断']];
    let priceWithCandidate = 0;
    for (const row of priceRows) {
      let tier='无', candidates=[];
      if (norm(row.product_name)) {
        for (const [label, matches] of [['名称+规格+单位',byNsu.get(key(row.product_name,row.specification,row.unit))],
          ['名称+单位',byNameUnit.get(key(row.product_name,row.unit))],['仅名称',byName.get(key(row.product_name))]]) {
          const others=(matches||[]).filter((candidate)=>candidate.product_code!==row.product_code);
          if (others.length) {tier=label;candidates=others;break;}
        }
      }
      if (candidates.length) priceWithCandidate++;
      priceOutput.push([row.warehouse,row.product_code,row.product_name,row.specification,row.unit,row.quantity,row.purchase_price,
        tier,candidates.length,candidates[0]?.product_code,candidates[0]?.product_name,candidates[0]?.purchase_price,
        candidates[1]?.product_code,candidates[1]?.product_name,candidates[1]?.purchase_price,row.id,
        candidates.length?'核对是否错码；候选价格不可直接套用':'未发现同名其他编号；核对是否自产或确实缺价']);
    }
    const priceTarget=path.join(__dirname,'../docs/氚云库存缺价与错码交叉核对-20261006.csv');
    fs.writeFileSync(priceTarget,'\uFEFF'+priceOutput.map((fields)=>fields.map(csvCell).join(',')).join('\r\n')+'\r\n','utf8');
    console.log(JSON.stringify({missingProduct:{output:target,positiveOutput:positiveTarget,total:stock.length,counts},missingPrice:{output:priceTarget,total:priceRows.length,otherCodeCandidateRows:priceWithCandidate}},null,2));
    await db.rollback();
  } finally { await db.end(); }
}
main().catch((error)=>{console.error('Candidate audit failed:',error.code||error.name);process.exitCode=1;});
