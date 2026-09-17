from pathlib import Path
from collections import Counter
import openpyxl, json, sys, re

def rows(path):
    wb=openpyxl.load_workbook(path,read_only=True,data_only=True)
    ws=wb['Sheet1']; ws.reset_dimensions(); it=ws.iter_rows(values_only=True)
    codes=next(it); labels=next(it)
    labels=[str(x).strip() if x is not None else '' for x in labels]
    for raw in it:
        yield {labels[i]: raw[i] if i<len(raw) else None for i in range(len(labels))}

def val(x): return str(x).strip() if x is not None and str(x).strip() else ''
def pct(a,b): return round(a*100/b,1) if b else 0
def top(counter,n=20): return counter.most_common(n)

base=Path(r'C:\Users\20676\Desktop\氚云')
files={
 'product':base/'氚云'/'产品信息_2026-08-31-17-17-04-145.xlsx',
 'stock':base/'氚云'/'仓库明细_2026-08-31-17-22-10-652.xlsx',
 'outbound':base/'氚云'/'销售出库_2026-09-01-10-47-50-618.xlsx',
 'production':base/'1、生产任务单管理_2026-08-21-14-49-23-412.xlsx'}

equipment_words=re.compile(r'机|封口|灌装|输送|贴标|称重|转盘|按钮自动|气动|气流式')
custom_words=re.compile(r'订做|定做|一出[一二三四五六七八九十]|模具|特氟龙|易撕口|气流式|片膜|卷膜|双拼|多拼')

for key in ('product','stock','outbound'):
    total=0; equipment=[]; cats=Counter(); units=Counter(); custom=Counter()
    for r in rows(files[key]):
        name=val(r.get('产品明细.产品名称') or r.get('产品名称') or r.get('出库产品明细.选择产品') or r.get('出库产品明细.产品名称（隐）'))
        cat=val(r.get('产品类目') or r.get('出库产品明细.类目') or r.get('出库产品明细.产品类目（隐）'))
        unit=val(r.get('产品明细.单位') or r.get('单位') or r.get('出库产品明细.单位'))
        if not name: continue
        total+=1; cats[cat]+=1; units[unit]+=1
        if unit in ('台','套') or equipment_words.search(cat) or equipment_words.search(name):
            equipment.append((cat,name,unit,val(r.get('产品明细.规格') or r.get('规格') or r.get('出库产品明细.规格型号'))))
            for m in custom_words.findall(name): custom[m]+=1
    print('\nDATASET',key,'rows',total,'equipment_like',len(equipment),pct(len(equipment),total))
    print('TOP_EQUIPMENT_CATEGORIES',json.dumps(top(Counter(x[0] for x in equipment),25),ensure_ascii=False))
    print('TOP_EQUIPMENT_UNITS',json.dumps(top(Counter(x[2] for x in equipment),10),ensure_ascii=False))
    print('CUSTOM_TOKENS',json.dumps(top(custom,20),ensure_ascii=False))
    print('SAMPLES',json.dumps(equipment[:25],ensure_ascii=False))

prod=list(rows(files['production']))
fields=['产品名称','盒子尺寸','模具排列','盒子形状','其他盒子形状','盒子材质','可选配置','备注','是否需要额外模具','额外模具数量','入库模具名称','模具入库数量','生产参考图','设计文件/图片','选择制程(必填)','零部件制程.零部件名']
print('\nPRODUCTION rows',len(prod))
for f in fields:
    c=Counter(val(r.get(f)) for r in prod if val(r.get(f)))
    print('FIELD',f,'filled',sum(c.values()),'rate',pct(sum(c.values()),len(prod)),'unique',len(c),'top',json.dumps(top(c,20),ensure_ascii=False))

patterns=Counter()
for r in prod:
    note=' '.join(val(r.get(x)) for x in ('备注','可选配置','产品名称','盒子尺寸','模具排列'))
    for label,pat in [('易撕口','易撕口'),('特氟龙','特氟龙'),('气流式','气流式'),('灌装','灌装'),('可互换','互换'),('加热','加热'),('切刀','切刀'),('扣盖','扣盖'),('上锁','上锁'),('氧化','氧化'),('盖盖子','盖'),('片膜','片膜'),('卷膜','卷膜')]:
        if pat in note: patterns[label]+=1
print('PRODUCTION_FEATURE_TOKENS',json.dumps(top(patterns,30),ensure_ascii=False))
print('PRODUCTION_SAMPLES')
for r in prod[:30]:
    print(json.dumps({f:val(r.get(f)) for f in fields if val(r.get(f))},ensure_ascii=False))
