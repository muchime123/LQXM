// 客户档案信息完整度只读审计（归因版）。凭据留在内存，绝不写入输出。
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('C:/Users/20676/source/LQXM/scripts/node_modules/mysql2/promise.js');
async function main() {
  const content = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8');
  const env = Object.fromEntries(content.split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => {
    const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/, ''));
  const db = await mysql.createConnection({ host: url.hostname, port: Number(url.port) || 3306, database: url.pathname.slice(1), user: env.DB_USERNAME || 'root', password: env.DB_PASSWORD || '' });
  try {
    const q = (sql) => db.query(sql).then(([r]) => r);
    const one = async (sql) => (await q(sql))[0];

    const outboundLink = await one(`SELECT COUNT(*) total, SUM(customer_id IS NULL) unlinked FROM sales_outbounds WHERE customer_name IS NOT NULL AND customer_name<>''`);

    const noProvince = await one(`SELECT COUNT(*) total_no_province,
        SUM(has_addr>0) has_outbound_addr,
        SUM(has_region>0) has_outbound_region,
        SUM(has_addr=0 AND has_region=0) no_addr_no_region
      FROM (SELECT c.id,
        (SELECT COUNT(*) FROM sales_outbounds o WHERE o.customer_id=c.id AND o.customer_address IS NOT NULL AND o.customer_address<>'') has_addr,
        (SELECT COUNT(*) FROM sales_outbounds o WHERE o.customer_id=c.id AND o.region IS NOT NULL AND o.region<>'') has_region
      FROM customers c WHERE c.deleted_at IS NULL AND (c.province IS NULL OR c.province='')) x`);

    const noContact = await one(`SELECT COUNT(*) total_no_contact,
        SUM(has_phone>0) has_outbound_phone
      FROM (SELECT c.id,
        (SELECT COUNT(*) FROM sales_outbounds o WHERE o.customer_id=c.id AND o.contact_phone IS NOT NULL AND o.contact_phone<>'') has_phone
      FROM customers c WHERE c.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM customer_contacts cc WHERE cc.customer_id=c.id)) x`);

    const noSales = await one(`SELECT COUNT(*) total_no_sales,
        SUM(has_sp>0) has_outbound_sp
      FROM (SELECT c.id,
        (SELECT COUNT(*) FROM sales_outbounds o WHERE o.customer_id=c.id AND o.salesperson_name IS NOT NULL AND o.salesperson_name<>'') has_sp
      FROM customers c WHERE c.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM customer_salesperson_relations r WHERE r.customer_id=c.id)) x`);

    const noAddress = await one(`SELECT COUNT(*) total_no_address,
        SUM(has_addr>0) has_outbound_addr,
        SUM(has_region>0) has_outbound_region,
        SUM(has_addr=0 AND has_region=0) no_addr_no_region
      FROM (SELECT c.id,
        (SELECT COUNT(*) FROM sales_outbounds o WHERE o.customer_id=c.id AND o.customer_address IS NOT NULL AND o.customer_address<>'') has_addr,
        (SELECT COUNT(*) FROM sales_outbounds o WHERE o.customer_id=c.id AND o.region IS NOT NULL AND o.region<>'') has_region
      FROM customers c WHERE c.deleted_at IS NULL AND (c.address IS NULL OR c.address='')) x`);

    console.log(JSON.stringify({ outboundLink, noProvince, noAddress, noContact, noSales }, null, 2));
  } finally { await db.end(); }
}
main().catch(e => { console.error('audit failed:', e.code || e.name, e.message); process.exitCode = 1; });
