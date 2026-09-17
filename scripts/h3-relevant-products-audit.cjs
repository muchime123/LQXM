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
    const [rows]=await db.query(`WITH refs AS (
      SELECT DISTINCT product_code FROM sales_outbound_items WHERE line_type='PRODUCT' AND NULLIF(TRIM(product_code),'') IS NOT NULL
      UNION
      SELECT DISTINCT product_code FROM h3_preparation_rows WHERE batch_id=2 AND CAST(quantity AS DECIMAL(40,18))<>0
    ) SELECT
      (SELECT COUNT(*) FROM refs) referenced_codes,
      COUNT(DISTINCT r.product_code) referenced_in_export,
      COUNT(DISTINCT p.product_code) referenced_already_sales_product,
      COUNT(DISTINCT CASE WHEN p.product_code IS NULL THEN r.product_code END) referenced_missing_sales_product
      FROM refs x LEFT JOIN h3_preparation_rows r ON r.batch_id=1 AND r.product_code=x.product_code
      LEFT JOIN sales_products p ON p.product_code=x.product_code AND p.deleted_at IS NULL`);
    console.log(JSON.stringify(rows,null,2));    await db.rollback();
  } finally { await db.end(); }
}
main().catch(e => { console.error('Read-only audit failed:', e.code || e.name); process.exitCode=1; });


