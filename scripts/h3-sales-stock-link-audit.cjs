// Read-only check for the sales-analysis nonzero-inventory join.
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');
async function main() {
  const env = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8')
    .split(/\r?\n/).filter(x => /^[A-Z_]+=/.test(x)).map(x => { const i=x.indexOf('='); return [x.slice(0,i),x.slice(i+1).trim()]; }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/,''));
  const db = await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,database:url.pathname.slice(1),user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||''});
  try {
    await db.query('START TRANSACTION READ ONLY');
    const [rows] = await db.query(`SELECT TRIM(ws.product_code) productCode,COUNT(*) nonZeroStockRows,
      COUNT(DISTINCT COALESCE(m.warehouse_id,w.id)) stockWarehouseCount
      FROM h3_warehouse_stock ws
      LEFT JOIN h3_product_catalog pc ON pc.product_code=TRIM(ws.product_code)
      LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
      LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
      WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL AND COALESCE(ws.quantity,0)<>0
      AND TRIM(COALESCE(NULLIF(TRIM(pc.product_category),''),ws.product_category,'')) NOT IN ('制版费','税点','运费','费用')
      AND TRIM(ws.product_code) NOT IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费')
      GROUP BY TRIM(ws.product_code)`);
    const [matches] = await db.query(`SELECT COUNT(DISTINCT TRIM(i.product_code)) matchedCodes
      FROM sales_outbound_items i WHERE i.line_type='PRODUCT' AND i.quantity>0
      AND TRIM(i.product_code) IN (?)`, [rows.map(r=>r.productCode)]);
    console.log(JSON.stringify({nonZeroStockCodes:rows.length,matchedSalesCodes:matches[0].matchedCodes}));
    await db.rollback();
  } finally { await db.end(); }
}
main().catch(e=>{console.error(e.sqlMessage||e.code||e.name);process.exitCode=1;});
