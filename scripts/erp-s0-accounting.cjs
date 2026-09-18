'use strict';
// S0 verification implementation, not a production ERP service.
const SCALE = 1000000000n;
const MAX = 10n ** 24n - 1n;
function decimal(value) {
  const s = String(value);
  if (!/^-?\d+(\.\d{1,9})?$/.test(s)) throw error('INVALID_AMOUNT');
  const [whole, fraction = ''] = s.replace(/^-/, '').split('.');
  const n = (BigInt(whole) * SCALE + BigInt(fraction.padEnd(9, '0'))) * (s.startsWith('-') ? -1n : 1n);
  if (n > MAX || n < -MAX) throw error('AMOUNT_OVERFLOW');
  return n;
}
function format(n) {
  const a = n < 0n ? -n : n;
  return `${n < 0n ? '-' : ''}${a / SCALE}.${String(a % SCALE).padStart(9, '0')}`;
}
function error(code) { return Object.assign(new Error(code), { code }); }
async function transaction(db, fn) {
  await db.beginTransaction();
  try { const result = await fn(); await db.commit(); return result; }
  catch (e) { await db.rollback(); throw e; }
}
async function allocate(db, fundId, itemId, amount, allocationId) {
  const q = decimal(amount);
  if (q <= 0n) throw error('INVALID_AMOUNT');
  return transaction(db, async () => {
    // Every allocation locks fund first, then item. Shared funds OR shared items serialize.
    const [[f]] = await db.query('SELECT * FROM fund_doc WHERE id=? FOR UPDATE', [fundId]);
    const [[i]] = await db.query('SELECT * FROM ar_ap_item WHERE id=? FOR UPDATE', [itemId]);
    if (!f || !i) throw error('INVALID_STATE');
    if (String(f.company_id) !== String(i.company_id) || String(f.party_id) !== String(i.party_id)
        || f.currency !== i.currency || !((f.direction === 'IN' && i.direction === 'AR')
          || (f.direction === 'OUT' && i.direction === 'AP'))) throw error('ALLOCATION_SCOPE_MISMATCH');
    const available = decimal(f.amount) - decimal(f.refunded_amount) - decimal(f.allocated_amount) - decimal(f.held_amount);
    const outstanding = decimal(i.original_amount) + decimal(i.adjusted_amount) - decimal(i.settled_amount);
    if (q > available || q > outstanding) throw error('ALLOCATION_EXCEEDED');
    await db.query("INSERT INTO settlement_allocation(id,fund_id,item_id,amount,status) VALUES(?,?,?,?,'ACTIVE')",
      [allocationId, fundId, itemId, format(q)]);
    await db.query('UPDATE fund_doc SET allocated_amount=allocated_amount+CAST(? AS DECIMAL(24,9)) WHERE id=?', [format(q), fundId]);
    await db.query('UPDATE ar_ap_item SET settled_amount=settled_amount+CAST(? AS DECIMAL(24,9)) WHERE id=?', [format(q), itemId]);
    return allocationId;
  });
}
async function adjustItem(db, itemId, amount) {
  const delta = decimal(amount);
  return transaction(db, async () => {
    const [[i]] = await db.query('SELECT * FROM ar_ap_item WHERE id=? FOR UPDATE', [itemId]);
    if (!i) throw error('INVALID_STATE');
    const outstanding = decimal(i.original_amount) + decimal(i.adjusted_amount) + delta - decimal(i.settled_amount);
    if (outstanding < 0n) throw error('REFUND_EXCEEDED');
    await db.query('UPDATE ar_ap_item SET adjusted_amount=adjusted_amount+CAST(? AS DECIMAL(24,9)) WHERE id=?', [format(delta), itemId]);
    return itemId;
  });
}
function rejectionMatches(e, expectedCode) { return Boolean(e && expectedCode && e.code === expectedCode); }
module.exports = { decimal, format, allocate, adjustItem, rejectionMatches };
