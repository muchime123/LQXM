'use strict';
// Isolated verification DB. Never drops a schema or touches the existing S0 dataset.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const mysql = require('mysql2/promise');
const { allocate, adjustItem } = require('./erp-s0-accounting.cjs');
const verification = require('./erp-verification-config.cjs');
const {consumeAvailable} = require('./erp-s0-stock.cjs');
const SCHEMA = 'erp_s0_hardening';
async function main() {
  const cfg = verification.config();
  const admin = await mysql.createConnection({...cfg, multipleStatements:true});
  const clients = [];
  let locked = false;
  try {
    await verification.acquire(admin,SCHEMA); locked = true;
    const sql = fs.readFileSync(path.join(__dirname,'erp-s0-schema.sql'),'utf8')
      .replace('DROP DATABASE IF EXISTS erp_s0_verify;', '')
      .replaceAll('erp_s0_verify',SCHEMA)
      .replace('CREATE DATABASE ', 'CREATE DATABASE IF NOT EXISTS ')
      .replaceAll('CREATE TABLE ', 'CREATE TABLE IF NOT EXISTS ');
    assert.ok(!/\bDROP\s+(DATABASE|TABLE)\b/i.test(sql));
    await admin.query(sql);
    for(let i=0;i<3;i++) clients.push(await mysql.createConnection({...cfg,database:SCHEMA}));
    const [a,b,control]=clients;
    for(const c of clients) await c.query('SET SESSION innodb_lock_wait_timeout=5');
    async function seed(amount='1000', settled='0') {
      // This fixed schema is dedicated to this runner and its opt-in legacy regression.
      for(const t of ['settlement_allocation','fund_doc','ar_ap_item']) await control.query(`DELETE FROM ${t}`);
      for(const id of [1,2]) {
        await control.query("INSERT INTO fund_doc(id,company_id,party_id,direction,amount,occurred_at) VALUES(?,1,1,'IN',?,NOW())",[id,amount]);
        await control.query("INSERT INTO ar_ap_item(id,company_id,party_id,direction,source_doc_id,original_amount,settled_amount) VALUES(?,1,1,'AR',?,?,?)",[id,id,amount,settled]);
      }
    }
    async function race(funds,items) {
      await seed();
      await a.query('SELECT * FROM fund_doc'); await b.query('SELECT * FROM ar_ap_item');
      // Hold contested row, dispatch BOTH connections, observe both transaction requests
      // pending while lock is held, then release; this is actual MySQL lock contention.
      await control.beginTransaction();
      const table = funds[0]===funds[1] ? 'fund_doc' : 'ar_ap_item';
      await control.query(`SELECT id FROM ${table} WHERE id=1 FOR UPDATE`);
      const jobs=[a,b].map((c,i)=>allocate(c,funds[i],items[i],'600',100+i));
      const all=Promise.allSettled(jobs);
      let waiters=0;
      for(let attempt=0;attempt<50;attempt++) {
        const [[r]]=await admin.query("SELECT COUNT(*) n FROM information_schema.processlist WHERE ID IN (?,?) AND COMMAND='Query' AND (INFO LIKE '%FOR UPDATE%' OR INFO LIKE 'UPDATE stock_balance%')",[a.connection.threadId,b.connection.threadId]);
        waiters=r.n;
        if(waiters===2) break;
        await new Promise(resolve=>setTimeout(resolve,40));
      }
      await control.commit();
      const result=await all;
      assert.equal(waiters,2,'Both independent connections must be executing the contested locking statements');
      assert.equal(result.filter(r=>r.status==='fulfilled').length,1);
      const failed=result.find(r=>r.status==='rejected');
      assert.equal(failed.reason.code,'ALLOCATION_EXCEEDED');
      const [[totals]]=await control.query('SELECT (SELECT SUM(allocated_amount) FROM fund_doc) f,(SELECT SUM(settled_amount) FROM ar_ap_item) i,(SELECT SUM(amount) FROM settlement_allocation) a');
      assert.equal(totals.f,'600.000000000'); assert.equal(totals.i,totals.f); assert.equal(totals.a,totals.f);
    }
    const results=[];
    async function check(id,fn){await fn();results.push({id,status:'PASS'});console.log(`${id} PASS`);}
    await check('H01_SHARED_FUND',()=>race([1,1],[1,2]));
    await check('H02_SHARED_ITEM',()=>race([1,2],[1,1]));
    await check('H03_EXACT_DATABASE_AMOUNT',async()=>{
      await seed('999999999999999.999999999');
      await allocate(a,1,1,'999999999999999.999999998',103);
      await allocate(a,1,1,'0.000000001',104);
      const [[r]]=await control.query('SELECT amount-allocated_amount remaining FROM fund_doc WHERE id=1');
      assert.equal(r.remaining,'0.000000000');
    });
    await check('H04_ADJUST_PARTIALLY_SETTLED',async()=>{
      await seed('1000','800'); await adjustItem(a,1,'-200');
      const [[r]]=await control.query('SELECT original_amount+adjusted_amount-settled_amount outstanding FROM ar_ap_item WHERE id=1');
      assert.equal(r.outstanding,'0.000000000');
      await assert.rejects(()=>adjustItem(a,1,'-0.000000001'),{code:'REFUND_EXCEEDED'});
    });
    await check('H05_SCOPE_AND_DIRECTION',async()=>{
      await seed(); await control.query('UPDATE ar_ap_item SET company_id=2 WHERE id=1');
      await assert.rejects(()=>allocate(a,1,1,'1',105),{code:'ALLOCATION_SCOPE_MISMATCH'});
      await control.query("UPDATE ar_ap_item SET company_id=1,direction='AP' WHERE id=1");
      await assert.rejects(()=>allocate(a,1,1,'1',105),{code:'ALLOCATION_SCOPE_MISMATCH'});
    });
    await check('H06_FAILURE_ROLLBACK',async()=>{
      await seed();
      // Inject failure at the second update, after allocation INSERT and first update.
      const injected={beginTransaction:()=>a.beginTransaction(),commit:()=>a.commit(),rollback:()=>a.rollback(),query:(sql,args)=>{
        if(sql.startsWith('UPDATE ar_ap_item')) throw Object.assign(new Error('injected'),{code:'INJECTED_FAILURE'});
        return a.query(sql,args);
      }};
      await assert.rejects(()=>allocate(injected,1,1,'100',106),{code:'INJECTED_FAILURE'});
      const [[r]]=await control.query('SELECT (SELECT allocated_amount FROM fund_doc WHERE id=1) f,(SELECT settled_amount FROM ar_ap_item WHERE id=1) i,(SELECT COUNT(*) FROM settlement_allocation) n');
      assert.equal(r.f,'0.000000000'); assert.equal(r.i,'0.000000000'); assert.equal(r.n,0);
    });
    await check('H07_NONPOSITIVE_AMOUNT',async()=>{
      await seed(); for(const amount of ['0','-1']) await assert.rejects(()=>allocate(a,1,1,amount,107),{code:'INVALID_AMOUNT'});
    });
    await check('H08_CONCURRENT_STOCK',async()=>{
      await control.query('DELETE FROM stock_balance');
      await control.query("INSERT INTO stock_balance(company_id,warehouse_id,sku_id,base_unit,base_quantity) VALUES(1,1,1,'台',5)");
      await control.beginTransaction();
      await control.query('SELECT * FROM stock_balance FOR UPDATE');
      const all=Promise.allSettled([a,b].map(async c=>{
        await c.beginTransaction();
        try {await consumeAvailable(c,1,1,1,'台','4');await c.commit();}
        catch(e){await c.rollback();throw e;}
      }));
      let waiters=0;
      for(let n=0;n<50;n++) {
        const [[r]]=await admin.query("SELECT COUNT(*) n FROM information_schema.processlist WHERE ID IN (?,?) AND COMMAND='Query' AND (INFO LIKE '%FOR UPDATE%' OR INFO LIKE 'UPDATE stock_balance%')",[a.connection.threadId,b.connection.threadId]);
        waiters=r.n;if(waiters===2)break;
        await new Promise(resolve=>setTimeout(resolve,40));
      }
      await control.commit();
      const result=await all;
      assert.equal(waiters,2);
      assert.equal(result.filter(r=>r.status==='fulfilled').length,1);
      assert.equal(result.find(r=>r.status==='rejected').reason.code,'INSUFFICIENT_AVAILABLE_STOCK');
      const [[r]]=await control.query('SELECT base_quantity FROM stock_balance');
      assert.equal(r.base_quantity,'1.000000000');
    });
    fs.writeFileSync(path.join(__dirname,'erp-s0-hardening-result.json'),JSON.stringify({at:new Date().toISOString(),schema:SCHEMA,scope:'S0 accounting implementation only; no HTTP authorization or production ERP verification',results},null,2)+'\n');
  } finally {
    for(const c of clients){await c.rollback();await c.end();}
    if(locked) await admin.query("SELECT RELEASE_LOCK('erp_s0_hardening_runner')");
    await admin.end();
  }
}
main().catch(e=>{console.error('Hardening failed:',e.code||e.name, e instanceof assert.AssertionError ? e.message : '');process.exitCode=1;});
