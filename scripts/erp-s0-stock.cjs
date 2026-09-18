'use strict';
const { decimal, format } = require('./erp-s0-accounting.cjs');
// Caller MUST own the transaction encompassing document, event, balance and quota.
async function consumeAvailable(db, company, warehouse, sku, unit, quantity) {
  const q = decimal(quantity);
  if (q <= 0n) throw Object.assign(new Error('INVALID_QUANTITY'),{code:'INVALID_QUANTITY'});
  const [result] = await db.query(`UPDATE stock_balance
    SET base_quantity=base_quantity-CAST(? AS DECIMAL(24,9))
    WHERE company_id=? AND warehouse_id=? AND sku_id=? AND base_unit=?
      AND quality_status='AVAILABLE' AND base_quantity>=CAST(? AS DECIMAL(24,9))`,
    [format(q),company,warehouse,sku,unit,format(q)]);
  if(result.affectedRows!==1) throw Object.assign(new Error('INSUFFICIENT_AVAILABLE_STOCK'),{code:'INSUFFICIENT_AVAILABLE_STOCK'});
}
module.exports={consumeAvailable};
