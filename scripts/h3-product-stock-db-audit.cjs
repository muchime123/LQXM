// Read-only audit. Credentials stay in memory and are never included in output.
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');
async function main() {
  const content = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8');
  const env = Object.fromEntries(content.split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => {
    const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/,''));
  const db = await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,database:url.pathname.slice(1),user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||''});
  try {
    await db.query('START TRANSACTION READ ONLY');
    const queries = {
      batches: `SELECT id,kind,file_name,row_count,product_batch_id FROM h3_preparation_batches ORDER BY id`,
      salesProducts: `SELECT COUNT(*) total,COUNT(DISTINCT product_code) codes,SUM(deleted_at IS NULL) active FROM sales_products`,
      salesProductStatus: `SELECT source,status,COUNT(*) count FROM sales_products WHERE deleted_at IS NULL GROUP BY source,status ORDER BY count DESC`,
      outboundCoverage: `SELECT COUNT(*) item_rows,COUNT(DISTINCT NULLIF(TRIM(product_code),'')) item_codes,SUM(sales_product_id IS NULL) unlinked_rows FROM sales_outbound_items WHERE line_type='PRODUCT'`,
      productExportVsSalesProducts: `SELECT COUNT(DISTINCT NULLIF(TRIM(r.product_code),'')) export_codes,COUNT(DISTINCT p.product_code) matched_sales_product_codes FROM h3_preparation_rows r LEFT JOIN sales_products p ON p.product_code=r.product_code AND p.deleted_at IS NULL WHERE r.batch_id=1`,
      outboundVsProductExport: `SELECT COUNT(DISTINCT NULLIF(TRIM(i.product_code),'')) outbound_codes,COUNT(DISTINCT r.product_code) matched_export_codes FROM sales_outbound_items i LEFT JOIN h3_preparation_rows r ON r.batch_id=1 AND r.product_code=i.product_code WHERE i.line_type='PRODUCT'`,
      stockNonzeroVsSalesProducts: `SELECT COUNT(DISTINCT r.product_code) nonzero_codes,COUNT(DISTINCT p.product_code) matched_sales_product_codes FROM h3_preparation_rows r LEFT JOIN sales_products p ON p.product_code=r.product_code AND p.deleted_at IS NULL WHERE r.batch_id=(SELECT MAX(id) FROM h3_preparation_batches WHERE kind='STOCK' AND product_batch_id=1) AND CAST(r.quantity AS DECIMAL(40,18))<>0`,
      exactSalesProductIdentity: `SELECT COUNT(*) export_rows,SUM(p.id IS NOT NULL) exact_sales_product_identity FROM h3_preparation_rows r LEFT JOIN sales_products p ON p.product_code=r.product_code AND p.product_name=r.product_name AND COALESCE(p.specification,'')=r.specification AND COALESCE(p.unit,'')=r.unit AND p.deleted_at IS NULL WHERE r.batch_id=1`
    };
    const out={}; for (const [name,sql] of Object.entries(queries)) { const [rows]=await db.query(sql); out[name]=rows; }
    console.log(JSON.stringify(out,null,2));    await db.rollback();
  } finally { await db.end(); }
}
main().catch(e => { console.error('Read-only audit failed:', e.code || e.name); process.exitCode=1; });


