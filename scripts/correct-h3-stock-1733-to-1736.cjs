// One-off, guarded local sync after the source warehouse code was corrected in H3.
// Run only against the 2026-10-06 import; the original batch preview is preserved.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const mysql = require('mysql2/promise');

const EXCLUDED = new Set(['id','action','changes','sourceObjectId','sourceTitle','ownerName',
  'departmentName','createdByName','dataStatus','sourceCreatedAt','sourceModifiedAt']);

function hashRow(json, code) {
  const row = JSON.parse(json);
  row.productCode = code;
  // JSON.parse loses BigDecimal scale. Keep the original numeric token as Java does.
  const quantityToken = json.match(/"quantity":(-?\d+(?:\.\d+)?)/)?.[1];
  const priceToken = json.match(/"purchasePrice":(-?\d+(?:\.\d+)?)/)?.[1];
  const parts = Object.entries(row).filter(([key]) => !EXCLUDED.has(key)).map(([key, value]) => {
    if (key === 'quantity' && quantityToken) return `${key}=${quantityToken}`;
    if (key === 'purchasePrice' && priceToken) return `${key}=${priceToken}`;
    return `${key}=${value == null ? '' : value}`;
  });
  return crypto.createHash('sha256').update(parts.join('|')).digest('hex');
}

async function main() {
  const lines = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/);
  const env = Object.fromEntries(lines.filter((line) => /^[A-Z_]+=/.test(line)).map((line) => {
    const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/, ''));
  const db = await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,
    database:url.pathname.slice(1),user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||''});
  try {
    await db.beginTransaction();
    const [source] = await db.query('SELECT id,warehouse,product_code,product_name,specification,unit,quantity,source_hash,last_seen_batch_id FROM h3_warehouse_stock WHERE id=82310 FOR UPDATE');
    if (source.length !== 1) throw new Error('Expected source row 82310 is missing');
    const row = source[0];
    if (row.product_code === '1736YX') {
      await db.rollback(); console.log('Already corrected: stock row 82310 is 1736YX'); return;
    }
    if (row.product_code !== '1733YX' || row.warehouse !== '温州仓' || row.product_name !== '手抓卤虾黄底'
        || row.specification !== '冷冻' || row.unit !== 'kg' || Number(row.quantity) !== 50
        || Number(row.last_seen_batch_id) !== 229) throw new Error('Source row no longer matches the reviewed snapshot');
    const [target] = await db.query('SELECT id FROM h3_warehouse_stock WHERE warehouse=? AND product_code=? AND unit=? FOR UPDATE', ['温州仓','1736YX','kg']);
    if (target.length) throw new Error('Target warehouse/code/unit already exists');
    const [catalog] = await db.query('SELECT product_code,product_name,unit FROM h3_product_catalog WHERE product_code IN (?,?) ORDER BY product_code', ['1733YX','1736YX']);
    if (catalog.length !== 2 || catalog[0].product_name !== '公主的渔大连海鲜' || catalog[1].product_name !== '手抓卤虾黄底')
      throw new Error('Catalog identities do not match the reviewed snapshot');
    const [staged] = await db.query('SELECT main_json,source_hash FROM h3_import_staging_rows WHERE batch_id=229 AND row_seq=736');
    if (staged.length !== 1 || hashRow(staged[0].main_json,'1733YX') !== row.source_hash || staged[0].source_hash !== row.source_hash)
      throw new Error('Original import hash does not match; refusing manual correction');
    const correctedHash = hashRow(staged[0].main_json,'1736YX');
    const [result] = await db.query('UPDATE h3_warehouse_stock SET product_code=?,source_hash=? WHERE id=? AND product_code=? AND source_hash=?',
      ['1736YX',correctedHash,82310,'1733YX',row.source_hash]);
    if (result.affectedRows !== 1) throw new Error('Update did not change exactly one row');
    await db.commit();
    console.log(JSON.stringify({corrected:true,id:82310,warehouse:'温州仓',from:'1733YX',to:'1736YX',quantity:'50 kg',sourceBatch:229}));
  } catch (error) {
    await db.rollback(); throw error;
  } finally { await db.end(); }
}
main().catch((error) => { console.error('Correction failed:', error.message); process.exitCode=1; });
