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
    const [rows] = await db.query('SELECT product_code,product_name,specification,unit,parse_issue FROM h3_preparation_rows WHERE batch_id=1 LIMIT 20');
    console.log(JSON.stringify(rows));
for (const table of ['product_models','product_variants','packaging_models','packaging_variants','packaging_molds']) {
const [r]=await db.query('SELECT * FROM '+table+' WHERE deleted_at IS NULL LIMIT 2');
console.log(table,JSON.stringify(r.map(x=>Object.fromEntries(Object.entries(x).filter(([k])=>/name|code|spec|unit|model_id/.test(k))))));
}
const [stats]=await db.query("SELECT COUNT(*) n,SUM(unit='') missingUnit,SUM(specification='') missingSpec,COUNT(DISTINCT product_code) codes,COUNT(DISTINCT source_id) ids FROM h3_preparation_rows WHERE batch_id=1");console.log(stats);    await db.rollback();
  } finally { await db.end(); }
}
main().catch(e => { console.error('Read-only audit failed:', e.code || e.name); process.exitCode=1; });


