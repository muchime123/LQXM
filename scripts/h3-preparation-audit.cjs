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
    const [rows] = await db.query(`SELECT COUNT(*) outboundCount,
      COALESCE(SUM(o.customer_id IS NULL),0) missingCustomerLink,
      COALESCE(SUM(o.customer_id IS NOT NULL AND (c.id IS NULL OR c.deleted_at IS NOT NULL)),0) invalidCustomerLink,
      COALESCE(SUM(NULLIF(TRIM(o.customer_name),'') IS NULL),0) missingCustomerName,
      COALESCE(SUM(NULLIF(TRIM(o.contact_phone),'') IS NULL),0) missingPhone,
      COALESCE(SUM(NULLIF(TRIM(o.customer_address),'') IS NULL),0) missingAddress
      FROM sales_outbounds o LEFT JOIN customers c ON c.id=o.customer_id`);
    console.log(JSON.stringify(rows[0]));
    await db.rollback();
  } finally { await db.end(); }
}
main().catch(e => { console.error('Read-only audit failed:', e.code || e.name); process.exitCode=1; });
