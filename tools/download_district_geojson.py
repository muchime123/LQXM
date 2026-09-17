# -*- coding: utf-8 -*-
"""按客户库内出现的省-市组合，批量下载有客户城市的区县 GeoJSON 到
frontend/public/map/cities/{adcode}.json，供「客户区域分布」地图第三级（市→区县）下钻使用。

数据源与现有 public/map/provinces/*.json 同源（DataV geo-atlas areas_v3 _full）。
已下钻的城市有客户新增到新城市时，可再次运行本脚本增量补齐。

用法: python tools/download_district_geojson.py
依赖: pymysql（连本机 product_archive 库）；无网络时跳过并提示。
"""
import json
import os
import re
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

MAP_ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..', 'frontend', 'public', 'map'))
CHINA_JSON = os.path.join(MAP_ROOT, 'china.json')
PROV_DIR = os.path.join(MAP_ROOT, 'provinces')
OUT_DIR = os.path.join(MAP_ROOT, 'cities')
BASE_URL = 'https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json'
DB = dict(host='127.0.0.1', port=3306, user='root', password='123456', database='product_archive', charset='utf8mb4')

ADMIN_SUFFIX = ['特别行政区', '维吾尔自治区', '壮族自治区', '回族自治区', '自治区', '自治州', '自治县', '地区', '省', '市', '县', '区', '盟']
ETHNIC_RE = re.compile(r'(?:蒙古族?|回族?|藏族?|维吾尔族?|苗族?|彝族?|壮族?|布依族?|朝鲜族?|满族?|侗族?|瑶族?|白族?|土家族?|哈尼族?|哈萨克族?|傣族?|黎族?|傈僳族?|佤族?|畲族?|高山族?|拉祜族?|水族?|东乡族?|纳西族?|景颇族?|柯尔克孜族?|土族?|达斡尔族?|仫佬族?|羌族?|布朗族?|撒拉族?|毛南族?|仡佬族?|锡伯族?|阿昌族?|普米族?|怒族?|鄂温克族?|鄂伦春族?|赫哲族?|门巴族?|珞巴族?|塔吉克族?|乌孜别克族?|俄罗斯族?|裕固族?|保安族?|京族?|塔塔尔族?|德昂族?|独龙族?|基诺族?)+$')


def region_base(name):
    s = (name or '').strip()
    changed = True
    while changed:
        changed = False
        for suf in ADMIN_SUFFIX:
            if s.endswith(suf) and len(s) > len(suf):
                s = s[:-len(suf)]
                changed = True
                break
        m = ETHNIC_RE.search(s)
        if m:
            s = ETHNIC_RE.sub('', s)
            changed = True
    return s


def fetch_pairs():
    import pymysql
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    cur.execute("""
        SELECT DISTINCT TRIM(province), TRIM(city) FROM customers
        WHERE deleted_at IS NULL AND province IS NOT NULL AND province<>'' AND province<>'待标注'
          AND city IS NOT NULL AND city<>''""")
    rows = [(r[0], r[1]) for r in cur.fetchall()]
    cur.close(); conn.close()
    return rows


def build_city_lookup():
    china = json.load(open(CHINA_JSON, encoding='utf-8'))
    prov_name = {
        str(f['properties']['adcode']): f['properties']['name']
        for f in china['features']
        if f.get('properties', {}).get('level') == 'province' and f['properties'].get('adcode')
    }
    lookup = {}
    for fn in os.listdir(PROV_DIR):
        if not fn.endswith('.json'):
            continue
        ad = fn[:-5]
        if ad not in prov_name:
            continue
        geo = json.load(open(os.path.join(PROV_DIR, fn), encoding='utf-8'))
        for f in geo.get('features', []):
            p = f.get('properties') or {}
            if p.get('level') == 'city' and p.get('adcode') and p.get('name'):
                lookup.setdefault((region_base(prov_name[ad]), region_base(p['name'])), []).append((str(p['adcode']), p['name']))
    return lookup


def main():
    pairs = fetch_pairs()
    lookup = build_city_lookup()
    need, matched, unmatched = {}, 0, 0
    for rp, rc in pairs:
        cand = lookup.get((region_base(rp), region_base(rc)))
        if not cand:
            unmatched += 1
            continue
        for cad, cname in cand:
            need[cad] = cname
        matched += 1
    print(f'distinctPairs={len(pairs)} matchedPairs={matched} unmatchedPairs={unmatched} needCities={len(need)}')
    os.makedirs(OUT_DIR, exist_ok=True)

    def download(adcode):
        req = urllib.request.Request(BASE_URL.format(adcode=adcode), headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
        geo = json.loads(raw.decode('utf-8'))
        target = os.path.join(OUT_DIR, f'{adcode}.json')
        json.dump(geo, open(target, 'w', encoding='utf-8'), ensure_ascii=False)
        return adcode, len(geo.get('features', []))

    done, failed = [], []
    with ThreadPoolExecutor(max_workers=10) as ex:
        futs = {ex.submit(download, ad): ad for ad in need}
        for fut in as_completed(futs):
            try:
                done.append(fut.result())
            except Exception as e:
                failed.append((futs[fut], f'{type(e).__name__}: {str(e)[:80]}'))
    done.sort()
    size = sum(os.path.getsize(os.path.join(OUT_DIR, f'{a}.json')) for a, _ in done)
    print(f'downloaded={len(done)} totalFeatures={sum(n for _, n in done)} totalMB={size/1024/1024:.2f}')
    if failed:
        print('failed(不设区地级市等无资源属正常):', failed)


if __name__ == '__main__':
    main()
