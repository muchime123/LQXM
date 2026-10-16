// Read-only analysis of H3 sales outbound product lines for ERPNext Item planning.
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');

async function main() {
  const content = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8');
  const env = Object.fromEntries(content.split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => {
    const at = l.indexOf('='); return [l.slice(0, at), l.slice(at + 1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/, ''));
  const db = await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,database:url.pathname.slice(1),user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||''});
  try {
    await db.query('START TRANSACTION READ ONLY');
    const queries = {
      categoryGroups: `SELECT g.product_group,COUNT(*) categories FROM h3_product_category_groups g GROUP BY g.product_group`,
      salesCategoriesByGroup: `SELECT COALESCE(g.product_group,'UNASSIGNED') product_group,COUNT(DISTINCT TRIM(i.category)) categories,COUNT(*) sale_lines
        FROM sales_outbound_items i LEFT JOIN h3_product_category_groups g ON g.source_category=TRIM(i.category)
        WHERE i.line_type='PRODUCT' AND i.quantity>0 AND COALESCE(i.category,'') NOT LIKE '%费用%'
        GROUP BY COALESCE(g.product_group,'UNASSIGNED')`,
      sources: `SELECT external_source,COUNT(*) order_count FROM sales_outbounds GROUP BY external_source`,
      tableCounts: `SELECT (SELECT COUNT(*) FROM sales_outbounds) outbound_orders,
        (SELECT COUNT(*) FROM sales_outbound_items) outbound_lines,
        (SELECT COUNT(*) FROM h3_import_batches WHERE data_type='OUTBOUND' AND status='COMPLETED') completed_outbound_batches`,
      scope: `SELECT COUNT(DISTINCT o.id) orders,COUNT(*) line_count,MIN(o.ship_date) first_ship,MAX(o.ship_date) last_ship,
        SUM(i.line_type='PRODUCT') product_lines,SUM(i.line_type<>'PRODUCT') other_lines,
        SUM(i.line_type='PRODUCT' AND i.quantity>0) positive_product_lines,
        SUM(i.line_type='PRODUCT' AND i.quantity<0) negative_product_lines,
        SUM(i.line_type='PRODUCT' AND (i.quantity IS NULL OR i.quantity=0)) blank_or_zero_product_lines,
        SUM(i.line_type='PRODUCT' AND NULLIF(TRIM(i.product_code),'') IS NULL) uncoded_product_lines
        FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id WHERE o.external_source='H3YUN'`,
      lineTypes: `SELECT i.line_type,COUNT(*) line_count,COUNT(DISTINCT i.sales_outbound_id) orders
        FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id
        WHERE o.external_source='H3YUN' GROUP BY i.line_type ORDER BY line_count DESC`,
      frequency: `SELECT COUNT(*) products,SUM(orders=1) one_order,SUM(orders BETWEEN 2 AND 4) two_to_four,
        SUM(orders BETWEEN 5 AND 19) five_to_nineteen,SUM(orders>=20) twenty_plus
        FROM (SELECT TRIM(i.product_code) code,COUNT(DISTINCT i.sales_outbound_id) orders
        FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id
        WHERE o.external_source='H3YUN' AND i.line_type='PRODUCT' AND i.quantity>0
        AND NULLIF(TRIM(i.product_code),'') IS NOT NULL GROUP BY TRIM(i.product_code)) x`,
      top: `SELECT TRIM(i.product_code) code,COUNT(DISTINCT i.sales_outbound_id) orders,COUNT(*) line_count,
        COUNT(DISTINCT NULLIF(TRIM(i.product_name),'')) names,
        COUNT(DISTINCT NULLIF(TRIM(i.unit),'')) units,
        MIN(o.ship_date) first_ship,MAX(o.ship_date) last_ship,
        ROUND(SUM(COALESCE(i.sales_total,0)),2) sales_total,
        SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT TRIM(i.product_name) ORDER BY TRIM(i.product_name) SEPARATOR ' / '),' / ',3) sample_names,
        SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT TRIM(i.unit) ORDER BY TRIM(i.unit) SEPARATOR ' / '),' / ',3) sample_units,
        MAX(pc.id IS NOT NULL) in_catalog,
        SUM(ws.product_code IS NOT NULL) stock_matches
        FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id
        LEFT JOIN h3_product_catalog pc ON pc.product_code=TRIM(i.product_code)
        LEFT JOIN (SELECT DISTINCT product_code FROM h3_warehouse_stock WHERE quantity<>0) ws ON ws.product_code=TRIM(i.product_code)
        WHERE o.external_source='H3YUN' AND i.line_type='PRODUCT' AND i.quantity>0
        AND NULLIF(TRIM(i.product_code),'') IS NOT NULL
        GROUP BY TRIM(i.product_code) ORDER BY orders DESC,line_count DESC LIMIT 40`,
      conflicts: `SELECT TRIM(i.product_code) code,COUNT(DISTINCT i.sales_outbound_id) orders,
        COUNT(DISTINCT NULLIF(TRIM(i.product_name),'')) names,
        COUNT(DISTINCT NULLIF(TRIM(i.unit),'')) units,
        SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT TRIM(i.product_name) ORDER BY TRIM(i.product_name) SEPARATOR ' / '),' / ',4) sample_names
        FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id
        WHERE o.external_source='H3YUN' AND i.line_type='PRODUCT' AND i.quantity>0
        AND NULLIF(TRIM(i.product_code),'') IS NOT NULL GROUP BY TRIM(i.product_code)
        HAVING names>1 OR units>1 ORDER BY orders DESC LIMIT 25`,
    };
    const result = {};
    for (const [key, sql] of Object.entries(queries)) {
      try { result[key] = (await db.query(sql))[0]; }
      catch (e) { throw new Error(`${key}: ${e.sqlMessage || e.code || e.name}`); }
    }
    console.log(JSON.stringify(result,null,2));
    await db.rollback();
  } finally { await db.end(); }
}
main().catch(e => { console.error('Read-only audit failed:',e.message||e.code||e.name); process.exitCode=1; });

