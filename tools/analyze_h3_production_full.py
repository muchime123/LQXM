from collections import Counter, defaultdict
from pathlib import Path
import openpyxl, json, sys, re

path=Path(sys.argv[1])
wb=openpyxl.load_workbook(path,read_only=True,data_only=True); ws=wb['Sheet1']; ws.reset_dimensions(); it=ws.iter_rows(values_only=True); next(it); labels=[str(x).strip() if x else '' for x in next(it)]
masters=[]; processes=[]; current=None
for raw in it:
 r={labels[i]:raw[i] if i<len(raw) else None for i in range(len(labels))}
 if r.get('生产工单流水号'):
  current=r; masters.append(r)
 if r.get('零部件制程.零部件名'):
  processes.append((str(current.get('生产工单流水号')) if current else '',r))
def s(x): return str(x).strip() if x is not None and str(x).strip() else ''
def pct(a,b): return round(a*100/b,1) if b else 0
def report(field,n=20):
 c=Counter(s(r.get(field)) for r in masters if s(r.get(field))); print(field,'filled',sum(c.values()),pct(sum(c.values()),len(masters)),'unique',len(c),'top',json.dumps(c.most_common(n),ensure_ascii=False))
print('MASTER_ORDERS',len(masters),'PROCESS_ROWS',len(processes))
for f in ['工单申请日期','计划类型','选择制程(必填)','产线选择(必填)','产品名称','盒子尺寸','模具排列','盒子形状','其他盒子形状','盒子材质','可选配置','备注','是否需要额外模具','额外模具数量','已有库存调用（模具）','入库模具名称','模具入库数量','模具入库数量_入库汇总','模具需求数量_计算','生产参考图','设计文件/图片','是否生产完成','是否入库完成','入库产品名称','关联销售订单','销售订单编号']:
 report(f)
pc=Counter(s(r.get('零部件制程.零部件名')) for _,r in processes if s(r.get('零部件制程.零部件名')))
print('PROCESS_TYPES',json.dumps(pc.most_common(),ensure_ascii=False))
final=Counter(s(r.get('零部件制程.是否最终工序')) for _,r in processes if s(r.get('零部件制程.是否最终工序')))
print('FINAL_FLAGS',json.dumps(final,ensure_ascii=False))

tokens=['易撕口','特氟龙','气流式','真空','网纹','切刀','盖板','工作面板','加热块','氧化','快换','互换','灌装','打码','落盒','输送','上锁','多高度','两个高度','隔断','片膜','卷膜','木箱']
tc=Counter()
for r in masters:
 text=' '.join(s(r.get(f)) for f in ['可选配置','备注','产品名称','填充配置项展示'])
 for t in tokens:
  if t in text: tc[t]+=1
print('FEATURE_TOKENS',json.dumps(tc.most_common(),ensure_ascii=False))

print('EXTRA_MOLD_SAMPLES')
for r in masters:
 if s(r.get('是否需要额外模具'))=='是' or '模具' in s(r.get('备注')) or '磨具' in s(r.get('备注')):
  out={f:s(r.get(f)) for f in ['生产工单流水号','产品名称','盒子尺寸','模具排列','盒子形状','盒子材质','可选配置','备注','是否需要额外模具','额外模具数量','已有库存调用（模具）','入库模具名称','模具入库数量'] if s(r.get(f))}
  print(json.dumps(out,ensure_ascii=False))
