// Read-only snapshot of the latest H3 product and warehouse imports.
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');

async function main() {
  const lines = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/);
  const env = Object.fromEntries(lines.filter((line) => /^[A-Z_]+=/.test(line)).map((line) => {
    const at = line.indexOf('=');
    return [line.slice(0, at), line.slice(at + 1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/, ''));
  const db = await mysql.createConnection({
    host: url.hostname, port: Number(url.port) || 3306, database: url.pathname.slice(1),
    user: env.DB_USERNAME || 'root', password: env.DB_PASSWORD || '',
  });
  try {
    await db.query('START TRANSACTION READ ONLY');
    const queries = {
      batches: `SELECT id,data_type,status,total_count,created_count,updated_count,unchanged_count,created_at,completed_at
                FROM h3_import_batches WHERE data_type IN ('PRODUCT_INFO','WAREHOUSE_STOCK') ORDER BY id DESC LIMIT 12`,
      counts: `SELECT (SELECT COUNT(*) FROM h3_product_catalog) catalog_rows,
                     (SELECT COUNT(*) FROM h3_warehouse_stock) stock_rows,
                     (SELECT COUNT(*) FROM h3_warehouse_stock WHERE quantity>0) positive_rows,
                     (SELECT COUNT(*) FROM h3_warehouse_stock WHERE quantity=0) zero_rows,
                     (SELECT COUNT(*) FROM h3_warehouse_stock WHERE quantity<0) negative_rows`,
      warehouses: `SELECT warehouse,COUNT(*) rows_count,SUM(quantity>0) positive_rows,SUM(quantity<0) negative_rows
                   FROM h3_warehouse_stock GROUP BY warehouse ORDER BY rows_count DESC`,
      issues: `SELECT COUNT(*) stock_rows,
                      SUM(pc.id IS NULL) missing_product,
                      SUM(ws.quantity>0 AND pc.id IS NULL) positive_missing_product,
                      SUM(ws.quantity>0 AND pc.id IS NOT NULL AND (pc.purchase_price IS NULL OR pc.purchase_price<=0)) positive_missing_price,
                      SUM(ws.quantity>0 AND pc.id IS NOT NULL AND TRIM(COALESCE(ws.unit,''))<>'' AND TRIM(COALESCE(pc.unit,''))<>'' AND TRIM(ws.unit)<>TRIM(pc.unit)) positive_unit_mismatch,
                      SUM(ws.quantity>0 AND pc.id IS NOT NULL AND TRIM(COALESCE(ws.product_name,''))<>'' AND TRIM(COALESCE(pc.product_name,''))<>'' AND TRIM(ws.product_name)<>TRIM(pc.product_name)) positive_name_mismatch,
                      SUM(ws.quantity>0 AND (TRIM(COALESCE(ws.unit,''))='')) positive_blank_unit
               FROM h3_warehouse_stock ws LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)`,
      missingProduct: `SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name,ws.specification,ws.unit,ws.quantity
                       FROM h3_warehouse_stock ws LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
                       WHERE ws.quantity>0 AND pc.id IS NULL ORDER BY ws.quantity DESC LIMIT 50`,
      unitMismatch: `SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name,ws.unit stock_unit,pc.unit catalog_unit,ws.quantity,pc.purchase_price
                      FROM h3_warehouse_stock ws JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
                      WHERE ws.quantity>0 AND TRIM(COALESCE(ws.unit,''))<>'' AND TRIM(COALESCE(pc.unit,''))<>'' AND TRIM(ws.unit)<>TRIM(pc.unit)
                      ORDER BY ws.quantity DESC LIMIT 50`,
      negative: `SELECT id,warehouse,product_code,product_name,specification,unit,quantity FROM h3_warehouse_stock WHERE quantity<0 ORDER BY quantity LIMIT 50`,
      freshness: `SELECT 'catalog' source,MAX(last_seen_at) latest_seen,MIN(last_seen_at) earliest_seen FROM h3_product_catalog
                  UNION ALL SELECT 'stock',MAX(last_seen_at),MIN(last_seen_at) FROM h3_warehouse_stock`,
      oldStockRows: `SELECT id,warehouse,product_code,product_name,unit,quantity,last_seen_batch_id,last_seen_at
                     FROM h3_warehouse_stock
                     WHERE last_seen_batch_id<>(SELECT MAX(id) FROM h3_import_batches WHERE data_type='WAREHOUSE_STOCK' AND status='COMPLETED')
                        OR last_seen_batch_id IS NULL ORDER BY id LIMIT 100`,
      oldStockCount: `SELECT COUNT(*) count FROM h3_warehouse_stock
                      WHERE last_seen_batch_id<>(SELECT MAX(id) FROM h3_import_batches WHERE data_type='WAREHOUSE_STOCK' AND status='COMPLETED')
                         OR last_seen_batch_id IS NULL`,
      missingPrice: `SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name,ws.unit,ws.quantity,pc.product_category,pc.purchase_price
                     FROM h3_warehouse_stock ws JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
                     WHERE ws.quantity>0 AND (pc.purchase_price IS NULL OR pc.purchase_price<=0)
                     ORDER BY ws.quantity DESC LIMIT 50`,
      topPositive: `SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name,ws.product_category,ws.unit,ws.quantity,
                           pc.product_category catalog_category,pc.purchase_price
                    FROM h3_warehouse_stock ws LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
                    WHERE ws.quantity>0 ORDER BY ws.quantity DESC LIMIT 25`,
      positiveNameMismatch: `SELECT ws.id,ws.warehouse,ws.product_code,ws.product_name stock_name,pc.product_name catalog_name,ws.unit,ws.quantity
                             FROM h3_warehouse_stock ws JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
                             WHERE ws.quantity>0 AND TRIM(COALESCE(ws.product_name,''))<>'' AND TRIM(COALESCE(pc.product_name,''))<>''
                               AND TRIM(ws.product_name)<>TRIM(pc.product_name) ORDER BY ws.quantity DESC`,
      feeAndScope: `SELECT SUM(ws.quantity>0 AND (TRIM(COALESCE(pc.product_category,ws.product_category,'')) IN ('费用','制版费','税点','运费')
                            OR TRIM(COALESCE(ws.product_code,'')) IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费'))) positive_fee_rows,
                           SUM(ws.quantity>0 AND ws.warehouse='代发仓') positive_dropship_rows,
                           SUM(ws.quantity>0 AND ws.warehouse<>'代发仓' AND NOT (TRIM(COALESCE(pc.product_category,ws.product_category,'')) IN ('费用','制版费','税点','运费')
                            OR TRIM(COALESCE(ws.product_code,'')) IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费'))) positive_regular_rows
                    FROM h3_warehouse_stock ws LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)`,
      disputedCodes: `SELECT 'catalog' source,id,product_code,product_name,specification,unit,NULL warehouse,NULL quantity,last_seen_batch_id,last_seen_at
                      FROM h3_product_catalog WHERE product_code IN ('1733YX','1736YX')
                      UNION ALL
                      SELECT 'stock',id,product_code,product_name,specification,unit,warehouse,quantity,last_seen_batch_id,last_seen_at
                      FROM h3_warehouse_stock WHERE product_code IN ('1733YX','1736YX')
                      ORDER BY source,product_code`,
      disputedOutboundUsage: `SELECT product_code,product_name,COUNT(*) rows_count,MIN(created_at) first_seen,MAX(created_at) last_seen
                              FROM sales_outbound_items WHERE product_code IN ('1733YX','1736YX')
                              GROUP BY product_code,product_name ORDER BY product_code,rows_count DESC LIMIT 30`,
      disputedStaging: `SELECT batch_id,row_seq,group_key,source_hash,JSON_EXTRACT(main_json,'$.productCode') product_code,
                              JSON_EXTRACT(main_json,'$.productName') product_name,JSON_EXTRACT(main_json,'$.warehouse') warehouse,
                              JSON_EXTRACT(main_json,'$.quantity') quantity,main_json
                       FROM h3_import_staging_rows WHERE batch_id=229 AND group_key LIKE '%1733YX%' LIMIT 5`,
    };
    const out = {};
    for (const [name, sql] of Object.entries(queries)) {
      const [rows] = await db.query(sql);
      out[name] = rows;
    }
    if (process.argv.includes('--worklist')) {
      const [rows] = await db.query(`SELECT ws.id stock_id,ws.last_seen_batch_id stock_batch_id,ws.warehouse,ws.product_code,
          ws.product_name stock_name,ws.specification stock_spec,ws.unit stock_unit,ws.quantity,
          pc.id catalog_id,pc.last_seen_batch_id catalog_batch_id,pc.product_name catalog_name,
          pc.specification catalog_spec,pc.unit catalog_unit,pc.purchase_price,pc.product_category catalog_category,
          ws.product_category stock_category
        FROM h3_warehouse_stock ws LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
        WHERE ws.quantity<>0 AND (
          ws.quantity<0 OR pc.id IS NULL OR (pc.purchase_price IS NULL OR pc.purchase_price<=0)
          OR (TRIM(COALESCE(ws.unit,''))<>'' AND TRIM(COALESCE(pc.unit,''))<>'' AND TRIM(ws.unit)<>TRIM(pc.unit))
          OR (TRIM(COALESCE(ws.product_name,''))<>'' AND TRIM(COALESCE(pc.product_name,''))<>'' AND TRIM(ws.product_name)<>TRIM(pc.product_name))
        ) ORDER BY ws.warehouse,ws.product_code,ws.unit`);
      const feeCategories = new Set(['费用','制版费','税点','运费']);
      const feeCodes = new Set(['运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费']);
      const columns = ['问题类型','建议优先级','仓库','产品编号','仓库产品名称','产品信息名称','仓库规格','产品信息规格','仓库单位','产品信息单位','仓库数量','产品信息采购价','仓库台账ID','产品信息台账ID','仓库覆盖批次','产品信息覆盖批次','建议核查动作','处理结论'];
      const records = [];
      for (const row of rows) {
        const fee = feeCategories.has((row.catalog_category || row.stock_category || '').trim()) || feeCodes.has((row.product_code || '').trim());
        const issues = [];
        if (Number(row.quantity) < 0) issues.push(['负库存','高','核对氚云进出记录与实物盘点，不直接改台账']);
        if (row.catalog_id == null && Number(row.quantity) > 0) issues.push(['缺产品信息','高','核对编号与产品信息导出范围；确认后在氚云补正']);
        if (row.catalog_id != null && row.stock_name?.trim() && row.catalog_name?.trim() && row.stock_name.trim() !== row.catalog_name.trim() && Number(row.quantity) > 0)
          issues.push(['同编号名称冲突','高','核对两份来源的产品身份；不得按名称自动改码']);
        if (row.catalog_id != null && row.stock_unit?.trim() && row.catalog_unit?.trim() && row.stock_unit.trim() !== row.catalog_unit.trim() && Number(row.quantity) > 0)
          issues.push(['单位冲突','高','核对仓库计量单位、采购价单位及换算关系']);
        // Fees are intentionally stored as products in H3 and are excluded from the inventory view.
        // A blank fee price is therefore not an actionable inventory-price exception.
        if (!fee && row.catalog_id != null && Number(row.quantity) > 0 && (row.purchase_price == null || Number(row.purchase_price) <= 0))
          issues.push(['缺采购价','中','先确认是否为实物商品及价格适用口径']);
        for (const [type, priority, action] of issues) records.push([
          type,priority,row.warehouse,row.product_code,row.stock_name,row.catalog_name,row.stock_spec,row.catalog_spec,
          row.stock_unit,row.catalog_unit,row.quantity,row.purchase_price,row.stock_id,row.catalog_id,
          row.stock_batch_id,row.catalog_batch_id,action,
          type === '同编号名称冲突' && row.product_code === '1733YX' && row.stock_name === '手抓卤虾黄底'
            ? '产品信息中 1733YX=公主的渔大连海鲜、1736YX=手抓卤虾黄底；优先核对仓库明细该行是否误填编号，应为 1736YX 时只修正该行编号并保留 50 kg，待源单确认'
            : ''
        ]);
      }
      const csv = [columns,...records].map((fields) => fields.map((value) => `"${String(value ?? '').replaceAll('"','""')}"`).join(',')).join('\r\n') + '\r\n';
      const output = path.join(__dirname, '../docs/氚云产品库存待核对清单-20261006.csv');
      fs.writeFileSync(output, '\uFEFF' + csv, 'utf8');
      console.log(JSON.stringify({output,issues:records.length,stockRows:new Set(records.map((record) => record[12])).size,byType:records.reduce((acc,r) => ((acc[r[0]]=(acc[r[0]]||0)+1),acc),{})},null,2));
    } else {
      console.log(JSON.stringify(out, null, 2));
    }
    await db.rollback();
  } finally {
    await db.end();
  }
}
main().catch((error) => { console.error('Read-only audit failed:', error.code || error.name); process.exitCode = 1; });
