'use strict';
const fs = require('node:fs');
const path = require('node:path');
const SCHEMAS = new Set(['erp_s0_verify', 'erp_s0_hardening']);
function schemaName(value) {
  if (!SCHEMAS.has(value)) throw new Error('Unsafe verification schema');
  return value;
}
function config() {
  const file = path.join(__dirname, '../backend/.env');
  const local = fs.existsSync(file) ? Object.fromEntries(fs.readFileSync(file, 'utf8')
    .split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => {
      const i = l.indexOf('='); return [l.slice(0,i),l.slice(i+1).trim()];
    })) : {};
  // Explicit verification overrides support disposable CI MySQL without production secrets.
  const url = new URL((local.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/,''));
  return {
    host:process.env.ERP_VERIFY_HOST || url.hostname,
    port:Number(process.env.ERP_VERIFY_PORT || url.port || 3306),
    user:process.env.ERP_VERIFY_USER || local.DB_USERNAME || 'root',
    password:process.env.ERP_VERIFY_PASSWORD ?? local.DB_PASSWORD ?? '',
  };
}
async function acquire(db, schema) {
  const name = `${schemaName(schema)}_runner`;
  const [[row]] = await db.query('SELECT GET_LOCK(?,0) acquired',[name]);
  if (row.acquired !== 1) throw new Error('Verification schema is in use');
  return name;
}
module.exports = { config, schemaName, acquire };
