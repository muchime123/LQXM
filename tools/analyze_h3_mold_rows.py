from pathlib import Path
from collections import Counter
import openpyxl, json, re

def read(path):
 wb=openpyxl.load_workbook(path,read_only=True,data_only=True); ws=wb['Sheet1']; ws.reset_dimensions(); it=ws.iter_rows(values_only=True); next(it); labels=[str(x).strip() if x else '' for x in next(it)]
 for raw in it: yield {labels[i]:raw[i] if i<len(raw) else None for i in range(len(labels))}
def s(v): return str(v).strip() if v is not None else ''
base=Path(r'C:\Users\20676\Desktop\氚云\氚云')
for fname,kind in [('产品信息_2026-08-31-17-17-04-145.xlsx','product'),('仓库明细_2026-08-31-17-22-10-652.xlsx','stock'),('销售出库_2026-09-01-10-47-50-618.xlsx','outbound')]:
 found=[]; parent=''
 for r in read(base/fname):
  cat=s(r.get('产品类目') or r.get('出库产品明细.类目') or r.get('出库产品明细.产品类目（隐）'))
  if cat: parent=cat
  name=s(r.get('产品明细.产品名称') or r.get('产品名称') or r.get('出库产品明细.选择产品'))
  spec=s(r.get('产品明细.规格') or r.get('规格') or r.get('出库产品明细.规格型号'))
  unit=s(r.get('产品明细.单位') or r.get('单位') or r.get('出库产品明细.单位'))
  effective=cat or (parent if kind=='product' else '')
  if name and (effective=='模具' or '模具' in name): found.append((effective,name,spec,unit))
 print('\n',kind,'count',len(found),'unique',len(set(x[1] for x in found)))
 print('units',Counter(x[3] for x in found).most_common())
 print('specs',Counter(x[2] for x in found).most_common(10))
 for x in found[:120]: print(json.dumps(x,ensure_ascii=False))
