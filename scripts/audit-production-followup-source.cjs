// Read-only source reconciliation. Never print credentials or change business rows.
const fs = require('fs');
const mysql = require('./node_modules/mysql2/promise');
const env = Object.fromEntries(fs.readFileSync('backend/.env', 'utf8').split(/\r?\n/).filter(l => l.includes('=')).map(l => { const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1).trim()]; }));
const url = new URL((env.SPRING_DATASOURCE_URL || 'jdbc:mysql://localhost:3306/product_archive').slice(5));
(async () => {
  const db = await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||'',database:url.pathname.slice(1)});
  try {
    const queries = {
      migration: "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
      source: "SELECT COUNT(*) total,SUM(actual_completion_date IS NULL) noCompletionDate,SUM(production_completed=1) completed,SUM(actual_completion_date IS NULL AND production_completed=1) doneWithoutDate,MIN(required_completion_date) firstDue,MAX(required_completion_date) lastDue FROM production_orders WHERE external_source='H3YUN'",
      statuses: 'SELECT status,COUNT(*) n FROM production_orders GROUP BY status',
      tables: "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE '%follow%'",
      firstPage: "SELECT work_order_no,product_name,production_line,required_completion_date,actual_completion_date,production_completed,production_progress FROM production_orders WHERE external_source='H3YUN' AND actual_completion_date IS NULL AND COALESCE(status,'') NOT IN ('已取消','CANCELLED') ORDER BY required_completion_date IS NULL,required_completion_date,id DESC LIMIT 5",
      progress: "SELECT production_progress,production_completed,COUNT(*) n FROM production_orders WHERE actual_completion_date IS NULL GROUP BY production_progress,production_completed ORDER BY n DESC LIMIT 15",
    };
    for (const [label, query] of Object.entries(queries)) { const [rows]=await db.query(query);console.log(label,JSON.stringify(rows)); }
    const [tables]=await db.query("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='production_followup_records'");
    if(tables.length) {const [rows]=await db.query('SELECT COUNT(*) localRecords FROM production_followup_records');console.log('local',JSON.stringify(rows));}
  } finally { await db.end(); }
})().catch(error => { console.error(error.message);process.exitCode=1; });
