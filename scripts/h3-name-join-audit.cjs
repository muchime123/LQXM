// Read-only comparison of code-based and name-based H3 stock/catalog joins.
const fs=require('node:fs'),path=require('node:path'),mysql=require('mysql2/promise');
const norm=(v)=>String(v??'').replace(/[\s\u3000\u00a0]+/g,'').toLowerCase();
async function main(){
  const env=Object.fromEntries(fs.readFileSync(path.join(__dirname,'../backend/.env'),'utf8').split(/\r?\n/)
    .filter(x=>/^[A-Z_]+=/.test(x)).map(x=>{const i=x.indexOf('=');return [x.slice(0,i),x.slice(i+1).trim()]}));
  const url=new URL((env.SPRING_DATASOURCE_URL||'jdbc:mysql://localhost:3306/product_archive').replace(/^jdbc:/,''));
  const db=await mysql.createConnection({host:url.hostname,port:Number(url.port)||3306,database:url.pathname.slice(1),user:env.DB_USERNAME||'root',password:env.DB_PASSWORD||''});
  try{
    await db.query('START TRANSACTION READ ONLY');
    const [catalog]=await db.query('SELECT id,product_code,product_name,specification,unit,purchase_price FROM h3_product_catalog');
    const [stock]=await db.query('SELECT id,warehouse,product_code,product_name,specification,unit,quantity FROM h3_warehouse_stock');
    const byCode=new Map(),byName=new Map(),byNsu=new Map();
    for(const c of catalog){byCode.set(norm(c.product_code),c);if(norm(c.product_name)){
      const n=norm(c.product_name),nsu=[n,norm(c.specification),norm(c.unit)].join('\u0001');
      if(!byName.has(n))byName.set(n,[]);byName.get(n).push(c);
      if(!byNsu.has(nsu))byNsu.set(nsu,[]);byNsu.get(nsu).push(c);
    }}
    const counts={stockRows:stock.length,codeMatchSameName:0,codeMatchDifferentName:0,codeMissing:0,
      nameUnique:0,nameAmbiguous:0,nameMissing:0,nsuUnique:0,nsuAmbiguous:0,nsuMissing:0,
      codeWrongNameNsuUnique:0,codeWrongNameNsuAmbiguous:0,codeMissingNsuUnique:0,
      positiveCodeWrongName:0,positiveCodeMissing:0};
    const examples=[];
    for(const s of stock){
      const c=byCode.get(norm(s.product_code)),n=norm(s.product_name),nu=byName.get(n)||[],
        nsu=byNsu.get([n,norm(s.specification),norm(s.unit)].join('\u0001'))||[];
      if(!c) {counts.codeMissing++;if(Number(s.quantity)>0)counts.positiveCodeMissing++;if(nsu.length===1)counts.codeMissingNsuUnique++;}
      else if(n && norm(c.product_name)!==n){counts.codeMatchDifferentName++;if(Number(s.quantity)>0)counts.positiveCodeWrongName++;
        if(nsu.length===1)counts.codeWrongNameNsuUnique++;else if(nsu.length>1)counts.codeWrongNameNsuAmbiguous++;
        if(examples.length<12)examples.push({stockCode:s.product_code,stockName:s.product_name,catalogCode:c.product_code,catalogName:c.product_name,
          nsuCandidates:nsu.map(x=>x.product_code).slice(0,4),quantity:s.quantity});
      }else counts.codeMatchSameName++;
      counts[nu.length===0?'nameMissing':nu.length===1?'nameUnique':'nameAmbiguous']++;
      counts[nsu.length===0?'nsuMissing':nsu.length===1?'nsuUnique':'nsuAmbiguous']++;
    }
    const catalogNameGroups=[...byName.values()].filter(x=>x.length>1);
    console.log(JSON.stringify({counts,catalogDuplicateNames:catalogNameGroups.length,maxCatalogNameMultiplicity:Math.max(0,...catalogNameGroups.map(x=>x.length)),examples},null,2));
    await db.rollback();
  }finally{await db.end()}
}
main().catch(e=>{console.error('Name audit failed:',e.code||e.name);process.exitCode=1});
