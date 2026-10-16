// Read-only diagnostic for purchase receipt -> production order -> sales outbound traceability.
const fs = require('node:fs');
const path = require('node:path');
const mysql = require('mysql2/promise');

async function main() {
  const lines = fs.readFileSync(path.join(__dirname, '../backend/.env'), 'utf8').split(/\r?\n/);
  const env = Object.fromEntries(lines.filter(line => /^[A-Z_]+=/.test(line)).map(line => {
    const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim()];
  }));
  const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/, ''));
  const db = await mysql.createConnection({
    host: url.hostname, port: Number(url.port) || 3306, database: url.pathname.slice(1),
    user: env.DB_USERNAME || 'root', password: env.DB_PASSWORD || ''
  });
  try {
    await db.query('START TRANSACTION READ ONLY');
    const queries = {
      traceMigration: `SELECT version,success FROM flyway_schema_history WHERE version='384'`,
      traceAllocationQueryShape: `SELECT a.id,
        CASE WHEN a.status='ACTIVE' AND pi.id IS NOT NULL AND si.id IS NOT NULL
          AND pr.data_status='已生效' AND o.outbound_status='CONFIRMED'
          AND COALESCE(o.confirmed_business_type,o.inferred_business_type)='SALE'
          AND pi.production_order_no=p.work_order_no
          AND TRIM(pi.product_code)=a.product_code AND TRIM(si.product_code)=a.product_code
          AND TRIM(COALESCE(pi.unit,''))=a.unit AND TRIM(COALESCE(si.unit,''))=a.unit
          AND pi.quantity=a.receipt_quantity_snapshot AND si.quantity=a.sale_quantity_snapshot
          AND pr.purchase_date=a.receipt_date_snapshot
          AND COALESCE(o.ship_date,o.business_date)=a.sale_date_snapshot
          AND pr.source_no=a.purchase_receipt_no AND o.outbound_no=a.sales_outbound_no
          THEN 1 ELSE 0 END currentSource
        FROM production_outbound_allocations a JOIN production_orders p ON p.id=a.production_order_id
        LEFT JOIN purchase_receipt_items pi ON pi.id=a.purchase_receipt_item_id
        LEFT JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
        LEFT JOIN sales_outbound_items si ON si.id=a.sales_outbound_item_id
        LEFT JOIN sales_outbounds o ON o.id=si.sales_outbound_id LIMIT 0`,
      sourceCounts: `SELECT
        (SELECT COUNT(*) FROM purchase_receipt_items) purchase_lines,
        (SELECT COUNT(*) FROM purchase_receipt_items WHERE NULLIF(TRIM(production_order_no),'') IS NOT NULL) lines_with_order,
        (SELECT COUNT(*) FROM purchase_receipt_items WHERE NULLIF(TRIM(production_order_no),'') IS NOT NULL AND NULLIF(TRIM(product_code),'') IS NOT NULL) lines_with_order_and_code,
        (SELECT COUNT(*) FROM sales_outbound_items WHERE line_type='PRODUCT' AND quantity>0) sale_product_lines,
        (SELECT COUNT(*) FROM sales_outbound_items WHERE line_type='PRODUCT' AND quantity>0 AND NULLIF(TRIM(product_code),'') IS NOT NULL) coded_sale_lines`,
      candidateCoverage: `SELECT COUNT(*) receipt_lines,
        SUM(EXISTS(SELECT 1 FROM sales_outbound_items si JOIN sales_outbounds so ON so.id=si.sales_outbound_id
          WHERE si.line_type='PRODUCT' AND si.quantity>0 AND TRIM(si.product_code)=TRIM(pi.product_code)
          AND so.external_source='H3YUN' AND COALESCE(so.outbound_status,'')<>'DRAFT'
          AND COALESCE(so.ship_date,so.business_date)>=pr.purchase_date)) candidate_lines,
        COUNT(DISTINCT pi.production_order_no) production_orders
        FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
        WHERE NULLIF(TRIM(pi.production_order_no),'') IS NOT NULL AND NULLIF(TRIM(pi.product_code),'') IS NOT NULL
          AND pr.data_status='已生效'`,
      repeatedCodes: `SELECT COUNT(*) codes, SUM(order_count>1) codes_with_multiple_orders,
        MAX(order_count) max_orders_per_code
        FROM (SELECT TRIM(pi.product_code) code,COUNT(DISTINCT pi.production_order_no) order_count
          FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
          WHERE pr.data_status='已生效' AND NULLIF(TRIM(pi.product_code),'') IS NOT NULL
            AND NULLIF(TRIM(pi.production_order_no),'') IS NOT NULL
          GROUP BY TRIM(pi.product_code)) x`,
      purchaseStatuses: `SELECT COALESCE(data_status,'NULL') status,COUNT(*) receipts FROM purchase_receipts GROUP BY data_status`,
      outboundStatuses: `SELECT COALESCE(outbound_status,'NULL') status,COUNT(*) outbounds FROM sales_outbounds WHERE external_source='H3YUN' GROUP BY outbound_status`,
      possibleEarlySales: `SELECT COUNT(*) receipt_lines_with_earlier_sale FROM purchase_receipt_items pi
        JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
        WHERE pr.data_status='已生效' AND NULLIF(TRIM(pi.production_order_no),'') IS NOT NULL
          AND NULLIF(TRIM(pi.product_code),'') IS NOT NULL AND EXISTS (
            SELECT 1 FROM sales_outbound_items si JOIN sales_outbounds so ON so.id=si.sales_outbound_id
            WHERE si.line_type='PRODUCT' AND si.quantity>0 AND TRIM(si.product_code)=TRIM(pi.product_code)
              AND so.external_source='H3YUN' AND COALESCE(so.outbound_status,'')<>'DRAFT'
              AND COALESCE(so.ship_date,so.business_date)<pr.purchase_date)`,
      receiptOrderResolution: `SELECT COUNT(*) linked_lines,
        SUM(p.id IS NULL) missing_production_order,
        SUM(pr.purchase_date IS NULL) missing_purchase_date,
        SUM(pi.quantity IS NULL OR pi.quantity<=0) nonpositive_quantity
        FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
        LEFT JOIN production_orders p ON p.work_order_no=pi.production_order_no
        WHERE pr.data_status='已生效' AND NULLIF(TRIM(pi.production_order_no),'') IS NOT NULL`,
      candidateAmbiguity: `SELECT COUNT(*) product_codes_with_sales,
        SUM(order_count=1) codes_one_production_order,
        SUM(order_count>1) codes_multiple_production_orders,
        SUM(sale_count>1) codes_multiple_sale_lines
        FROM (SELECT x.code,x.order_count,COUNT(si.id) sale_count FROM (
          SELECT TRIM(pi.product_code) code,COUNT(DISTINCT pi.production_order_no) order_count
          FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
          WHERE pr.data_status='已生效' AND NULLIF(TRIM(pi.production_order_no),'') IS NOT NULL
            AND NULLIF(TRIM(pi.product_code),'') IS NOT NULL GROUP BY TRIM(pi.product_code)) x
          JOIN sales_outbound_items si ON TRIM(si.product_code)=x.code AND si.line_type='PRODUCT' AND si.quantity>0
          JOIN sales_outbounds so ON so.id=si.sales_outbound_id AND so.external_source='H3YUN'
            AND COALESCE(so.outbound_status,'')<>'DRAFT'
          GROUP BY x.code,x.order_count) matches`,
      candidatePolicySample: `SELECT si.id saleItemId,o.outbound_no outboundNo,
        CASE WHEN TRIM(COALESCE(si.unit,''))=TRIM(COALESCE(pi.unit,'')) THEN 1 ELSE 0 END sameUnit,
        CASE WHEN NULLIF(TRIM(o.warehouse),'') IS NOT NULL AND NULLIF(TRIM(pr.warehouse_name),'') IS NOT NULL
          AND TRIM(o.warehouse)=TRIM(pr.warehouse_name) THEN 1 ELSE 0 END sameWarehouse,
        CASE WHEN COALESCE(o.ship_date,o.business_date)>=pr.purchase_date THEN 1 ELSE 0 END dateAfterReceipt
        FROM purchase_receipt_items pi JOIN purchase_receipts pr ON pr.id=pi.purchase_receipt_id
        JOIN sales_outbound_items si ON TRIM(si.product_code)=TRIM(pi.product_code)
        JOIN sales_outbounds o ON o.id=si.sales_outbound_id
        WHERE pi.production_order_no IS NOT NULL AND pr.data_status='已生效'
          AND o.external_source='H3YUN' AND si.line_type='PRODUCT' AND si.quantity>0
          AND o.outbound_status='CONFIRMED'
          AND COALESCE(o.confirmed_business_type,o.inferred_business_type)='SALE'
        LIMIT 3`
    };
    for (const [name, sql] of Object.entries(queries)) {
      const [rows] = await db.query(sql);
      process.stdout.write(`${name}: ${JSON.stringify(rows)}\n`);
    }
    await db.rollback();
  } finally { await db.end(); }
}
main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
