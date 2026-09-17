from pathlib import Path
from collections import Counter, defaultdict
import openpyxl, json, sys

def load(path):
 wb=openpyxl.load_workbook(path,read_only=True,data_only=True); ws=wb['Sheet1']; ws.reset_dimensions(); it=ws.iter_rows(values_only=True); next(it); labels=[str(x).strip() if x else '' for x in next(it)]
 return labels,list(it)
def s(x): return str(x).strip() if x is not None and str(x).strip() else ''

process, templates, configs=map(Path,sys.argv[1:4])

labels,raws=load(process); rows=[{labels[i]:r[i] if i<len(r) else None for i in range(len(labels))} for r in raws]
print('PROCESS_MASTER_ROWS',len(rows))
for r in rows: print(json.dumps({k:s(r.get(k)) for k in ['制程名称','库存数量','工艺图片','报工二维码','数据ID(不可修改)','数据状态']},ensure_ascii=False))

labels,raws=load(templates); current=None; groups=defaultdict(list); heads={}
for raw in raws:
 r={labels[i]:raw[i] if i<len(raw) else None for i in range(len(labels))}
 if s(r.get('制程名称')):
  current=s(r.get('制程名称')); heads[current]=r
 if current and s(r.get('工艺制程.零部件名')):
  groups[current].append(r)
print('\nTEMPLATE_COUNT',len(heads),'OPERATION_ROWS',sum(map(len,groups.values())))
for name,h in heads.items():
 ops=[{'seq':s(r.get('工艺制程.序号')),'name':s(r.get('工艺制程.零部件名')),'coefficient':s(r.get('工艺制程.需求系数')),'final':s(r.get('工艺制程.是否最终工序')),'final2':s(r.get('工艺制程.是否为最终'))} for r in groups[name]]
 print(json.dumps({'template':name,'hasOperations':s(h.get('是否有制程')),'tailCheck':s(h.get('校验是否含尾道工序')),'operations':ops},ensure_ascii=False))

labels,raws=load(configs); rows=[{labels[i]:r[i] if i<len(r) else None for i in range(len(labels))} for r in raws]
print('\nCONFIG_ROWS',len(rows))
print('BY_WORKSHOP',json.dumps(Counter(s(r.get('车间类型')) for r in rows).most_common(),ensure_ascii=False))
print('BY_TYPE',json.dumps(Counter(s(r.get('配置类型')) for r in rows).most_common(),ensure_ascii=False))
for workshop in sorted(set(s(r.get('车间类型')) for r in rows)):
 for typ in ['产品名称','可选配置']:
  vals=[s(r.get('配置名称')) for r in rows if s(r.get('车间类型'))==workshop and s(r.get('配置类型'))==typ]
  print(json.dumps({'workshop':workshop,'type':typ,'count':len(vals),'values':vals},ensure_ascii=False))
