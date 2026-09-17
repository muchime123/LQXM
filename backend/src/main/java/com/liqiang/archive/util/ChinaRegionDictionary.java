package com.liqiang.archive.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中国大陆三级行政区划字典（省 / 市 / 区县），数据源自前端已装
 * element-china-area-data（china-division pca.json），由脚本生成资源文件
 * {@code region/gazetteer.tsv}（直辖市「市辖区」层已归一为省同名，与系统存储约定一致）。
 *
 * <p>用途：地址解析在文本缺省名时，用「市 / 区县名 → 上级省、市」做低风险回填：
 * <ul>
 *   <li>城市层名全国唯一（排除「省直辖县级行政区划」伪层），命中即推断省份；</li>
 *   <li>区县名可能存在跨省重名：仅当只有唯一归属、或可用已知省份消歧时才采用，
 *       否则宁缺毋滥（不猜测），避免写错省。</li>
 * </ul>
 */
public final class ChinaRegionDictionary {

    private ChinaRegionDictionary() {
    }

    /** 直辖市（存储约定：市字段与省同名）。 */
    private static final Set<String> MUNICIPALITIES = Set.of("北京市", "天津市", "上海市", "重庆市");

    /** 数据中的伪城市层（省直辖县级行政区划），不视为真实地级市。 */
    private static final String PSEUDO_CITY = "省直辖县级行政区划";

    /** 省名简称（去除「省/市/自治区/特别行政区」等后缀的写法）→ 规范全名。
     *  用于把「浙江」「内蒙古」等客户档案写法归一到官方全名（与地图 feature 名同源）。 */
    private static final Map<String, String> PROVINCE_ALIAS = Map.ofEntries(
            Map.entry("北京", "北京市"),
            Map.entry("天津", "天津市"),
            Map.entry("上海", "上海市"),
            Map.entry("重庆", "重庆市"),
            Map.entry("河北", "河北省"),
            Map.entry("山西", "山西省"),
            Map.entry("辽宁", "辽宁省"),
            Map.entry("吉林", "吉林省"),
            Map.entry("黑龙江", "黑龙江省"),
            Map.entry("江苏", "江苏省"),
            Map.entry("浙江", "浙江省"),
            Map.entry("安徽", "安徽省"),
            Map.entry("福建", "福建省"),
            Map.entry("江西", "江西省"),
            Map.entry("山东", "山东省"),
            Map.entry("河南", "河南省"),
            Map.entry("湖北", "湖北省"),
            Map.entry("湖南", "湖南省"),
            Map.entry("广东", "广东省"),
            Map.entry("海南", "海南省"),
            Map.entry("四川", "四川省"),
            Map.entry("贵州", "贵州省"),
            Map.entry("云南", "云南省"),
            Map.entry("陕西", "陕西省"),
            Map.entry("甘肃", "甘肃省"),
            Map.entry("青海", "青海省"),
            Map.entry("台湾", "台湾省"),
            Map.entry("内蒙古", "内蒙古自治区"),
            Map.entry("广西", "广西壮族自治区"),
            Map.entry("西藏", "西藏自治区"),
            Map.entry("宁夏", "宁夏回族自治区"),
            Map.entry("新疆", "新疆维吾尔自治区"),
            Map.entry("香港", "香港特别行政区"),
            Map.entry("澳门", "澳门特别行政区"));

    /** gazetteer 未收录、但属于中国行政区划官方全名的港澳台省份。 */
    private static final Set<String> SPECIAL_REGIONS = Set.of("台湾省", "香港特别行政区", "澳门特别行政区");

    /**
     * 境外（外国）客户的省份占位值：出库单「地区」标注为「国外」、且确认为真实外贸客户时写入
     * {@code customers.province}。它与港澳台省级值同口径——都是「已被识别、不参与大陆省级治理」
     * 的地区值，区别是境外不上中国地图。
     */
    public static final String OVERSEAS_PROVINCE = "境外";

    /** 是否境外占位值（「境外」；出库单侧的「国外」写法另行归一）。 */
    public static boolean isOverseasProvince(String value) {
        if (value == null) return false;
        String v = simplify(value.trim());
        return OVERSEAS_PROVINCE.equals(v) || "国外".equals(v);
    }

    /** 繁体 → 简体 常见区划字（仅用于区划名匹配归一，不改变原文输出）。 */
    private static final Map<Character, Character> T2S = buildT2S();

    // ==================== 用户维护的写法别名（字典的持久化扩展） ====================
    // 数据来自 customer_region_aliases 表，由 CustomerRegionService 在启动与变更后
    // 通过 replaceUserAliases 灌入。仅当官方字典无法识别该写法时兜底命中；
    // 城市/区县别名必须显式声明归属省份，只在档案省份与该归属一致时生效（避免跨省误归并）。

    /** 写法别名条目（kind ∈ PROVINCE/CITY/DISTRICT）。 */
    public record UserRegionAlias(String kind, String alias, String province, String target) {
    }

    private record ScopedTarget(String province, String target) {
    }

    private static final Map<String, String> ALIAS_PROVINCE = new LinkedHashMap<>();
    private static final Map<String, List<ScopedTarget>> ALIAS_CITY = new LinkedHashMap<>();
    private static final Map<String, List<ScopedTarget>> ALIAS_DISTRICT = new LinkedHashMap<>();

    /** 全量替换用户别名（启动加载 / CRUD 后刷新；线程安全）。 */
    public static synchronized void replaceUserAliases(List<UserRegionAlias> aliases) {
        ensureLoaded();
        ALIAS_PROVINCE.clear();
        ALIAS_CITY.clear();
        ALIAS_DISTRICT.clear();
        if (aliases == null) return;
        for (UserRegionAlias a : aliases) {
            if (a == null || a.alias() == null) continue;
            String norm = simplify(a.alias().trim());
            if (norm.isEmpty()) continue;
            switch (a.kind() == null ? "" : a.kind()) {
                case "PROVINCE" -> {
                    if (a.target() != null && !a.target().isBlank()) {
                        ALIAS_PROVINCE.putIfAbsent(norm, simplify(a.target().trim()));
                    }
                }
                case "CITY" -> addScopedTarget(ALIAS_CITY, norm, a.province(), a.target());
                case "DISTRICT" -> addScopedTarget(ALIAS_DISTRICT, norm, a.province(), a.target());
                default -> { }
            }
        }
    }

    private static void addScopedTarget(Map<String, List<ScopedTarget>> map, String norm, String province, String target) {
        if (target == null || target.isBlank()) return;
        String scope = province == null ? "" : simplify(province.trim());
        map.computeIfAbsent(norm, k -> new ArrayList<>()).add(new ScopedTarget(scope, simplify(target.trim())));
    }

    /** 按省份作用域查找别名目标（作用域必须与档案省份一致才命中）。 */
    private static String lookupScopedAlias(Map<String, List<ScopedTarget>> map, String norm, String provinceHint) {
        List<ScopedTarget> list = map.get(norm);
        if (list == null || list.isEmpty()) return null;
        String hint = provinceHint == null ? null : simplify(provinceHint.trim());
        for (ScopedTarget st : list) {
            if (hint != null && st.province().equals(hint)) return st.target();
        }
        return null;
    }

    private record RegionCity(String city, String province) {
    }

    private static Map<Character, Character> buildT2S() {
        String from = "廣東灣龍縣區鎮寧連雲貴陽濟島臺夢萬鳳岡從雙樓磯豐濤頭橋裡鳳偉勝匯廣漢東灣廈門銀蘭臨麗婁樂齊達進遷連遠遼鐵輝華興慶安德島建福羅密鬆鶴熱壺威州廊坊滄德島東煙濰臨泰聊菏銀榆寧回西寶鹹渭漢十襄荊鄂黃湘株潭衡邵岳常張益郴永懷婁吉廣南柳桂梧玉百欽河北海防崇來賀貴容瀘綿廣南樂宜瀘南充眉宜廣雅巴資黔遵安畢銅興貴昆曲玉保昭麗普臨楚紅文版迪瀘山布爾金甬";
        String to = "广东湾龙县区镇宁连云贵阳济岛台梦万凤冈从双楼矶丰涛头桥里凤伟胜汇广汉东湾厦门银兰临丽娄乐齐达进迁连远辽铁辉华兴庆安德岛建福罗密松鹤热壶威州廊坊沧德岛东烟潍临泰聊菏银榆宁回西宝咸渭汉十襄荆鄂黄湘株潭衡邵岳常张益郴永怀娄吉广南柳桂梧玉百钦河北海防崇来贺贵容泸绵广南乐宜泸南充眉宜广雅巴资黔遵安毕铜兴贵昆曲玉保昭丽普临楚红文版迪泸山布尔金甬";
        Map<Character, Character> m = new LinkedHashMap<>();
        for (int i = 0; i < from.length(); i++) {
            m.put(from.charAt(i), to.charAt(i));
        }
        return m;
    }

    /** 繁体/异体字归一为简体（不在映射表的字符原样保留）。 */
    public static String simplify(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character d = T2S.get(c);
            sb.append(d == null ? c : d.charValue());
        }
        return sb.toString();
    }

    /** 城市层名（规范写法，不含直辖市）→ 省份；直辖市直接为省份本身。 */
    private static final Map<String, String> CITY_PROVINCE = new LinkedHashMap<>();

    /** 全部官方省份名（gazetteer 省级列 + 港澳台），分布聚合与写入口归一的口径集合。 */
    private static final Set<String> OFFICIAL_PROVINCES = new LinkedHashSet<>();

    /** 区县名（规范写法）→ 归属 (省, 市) 列表（存在跨省/跨市重名）。 */
    private static final Map<String, List<RegionCity>> DISTRICT_PARENTS = new LinkedHashMap<>();

    private static volatile boolean loaded;

    /** 幂等加载区划资源（惰性、线程安全）。 */
    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (ChinaRegionDictionary.class) {
            if (loaded) return;
            loadResource();
            loaded = true;
        }
    }

    private static void loadResource() {
        String path = "/region/gazetteer.tsv";
        try (InputStream in = ChinaRegionDictionary.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少区划资源文件: " + path);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\t", -1);
                    if (parts.length < 3) continue;
                    String province = parts[0].trim();
                    String city = parts[1].trim();
                    String district = parts[2].trim();
                    if (province.isEmpty() || district.isEmpty()) continue;
                    OFFICIAL_PROVINCES.add(province);
                    if (!MUNICIPALITIES.contains(province) && !PSEUDO_CITY.equals(city) && !city.isEmpty()) {
                        CITY_PROVINCE.putIfAbsent(city, province);
                    }
                    DISTRICT_PARENTS.computeIfAbsent(district, k -> new ArrayList<>())
                            .add(new RegionCity(city.isEmpty() ? null : city, province));
                }
            }
            OFFICIAL_PROVINCES.addAll(SPECIAL_REGIONS);
        } catch (IOException e) {
            throw new IllegalStateException("加载区划资源失败: " + path, e);
        }
    }

    /** 直辖市城市层字段取值 = 省份本身（存储约定）。 */
    public static boolean isMunicipalityProvince(String province) {
        return province != null && MUNICIPALITIES.contains(province);
    }

    /**
     * 由「城市名 token」推断唯一省份；返回规范城市名与省份。
     * 支持整 token 精确命中，也支持形如「发到长春市」的少量前缀杂质（前缀 ≤ 3 个汉字）。
     * 无唯一结果返回 null。
     */
    public static RegionHit matchCityToken(String token) {
        ensureLoaded();
        if (token == null || token.isEmpty()) return null;
        String norm = simplify(token);
        String exact = CITY_PROVINCE.get(norm);
        if (exact != null) return new RegionHit(exact, norm, true);

        // 前缀杂质：以某规范城市名结尾且杂质不超过 3 个字符
        for (Map.Entry<String, String> e : CITY_PROVINCE.entrySet()) {
            String label = e.getKey();
            int diff = norm.length() - label.length();
            if (diff >= 1 && diff <= 3 && norm.endsWith(label)) {
                return new RegionHit(e.getValue(), label, true);
            }
        }
        // 直辖市写法（北京市 等 token）
        if (MUNICIPALITIES.contains(norm)) {
            return new RegionHit(norm, norm, true);
        }
        return null;
    }

    /**
     * 由「区县名 token」推断归属（支持带少量前缀杂质的写法，如「广州白云区」「武汉武昌区」）。
     * 返回命中归属：{@link DistrictResolve#district()} 为字典规范区县名。
     * 仅当归属唯一、或 {@code provinceHint} 与某一归属省份一致时返回；否则返回 null（重名歧义不猜测）。
     */
    public static DistrictResolve resolveDistrictToken(String token, String provinceHint) {
        ensureLoaded();
        if (token == null || token.isEmpty()) return null;
        String norm = simplify(token);
        // 候选规范区县名：整 token 精确命中，或「前缀杂质(≤4 个汉字) + 规范名结尾」命中
        List<String> candidates = new ArrayList<>();
        if (DISTRICT_PARENTS.containsKey(norm)) candidates.add(norm);
        int n = norm.length();
        if (n >= 4 && n <= 12) {
            for (String key : DISTRICT_PARENTS.keySet()) {
                int diff = n - key.length();
                if (diff >= 1 && diff <= 4 && norm.endsWith(key) && isHanPrefix(norm, diff)) {
                    candidates.add(key);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        // 优先取最长的规范名（去掉更多前缀杂质）
        candidates.sort((a, b) -> b.length() - a.length());
        Set<String> seen = new LinkedHashSet<>();
        for (String label : candidates) {
            if (!seen.add(label)) continue;
            List<RegionCity> parents = DISTRICT_PARENTS.get(label);
            if (parents == null) continue;
            Set<String> provinces = new LinkedHashSet<>();
            for (RegionCity rc : parents) provinces.add(rc.province());
            String pickProvince = null;
            if (provinceHint != null && provinces.contains(provinceHint)) {
                pickProvince = provinceHint;
            } else if (provinces.size() == 1) {
                pickProvince = provinces.iterator().next();
            }
            if (pickProvince == null) continue;
            Set<String> cities = new LinkedHashSet<>();
            for (RegionCity rc : parents) {
                if (rc.province().equals(pickProvince) && rc.city() != null && !PSEUDO_CITY.equals(rc.city())) {
                    cities.add(rc.city());
                }
            }
            String city = cities.size() == 1 ? cities.iterator().next() : null;
            return new DistrictResolve(pickProvince, city, label);
        }
        return null;
    }

    /** 判断 norm 前 diff 个字符是否均为汉字（前缀杂质排除含数字/标点的整段地址）。 */
    private static boolean isHanPrefix(String norm, int diff) {
        for (int i = 0; i < diff; i++) {
            char c = norm.charAt(i);
            if (c < '\u4E00' || c > '\u9FFF') return false;
        }
        return true;
    }

    /**
     * 仅做区县名规范化（不做归属推断）：整 token 已知则返回原样；
     * 否则若其「规范区县名 + ≤4 汉字前缀」结尾则返回规范名（如「广州白云区」→「白云区」，
     * 用于在歧义无法补省时仍能清理脏前缀）；无命中返回 null。
     */
    public static String canonicalDistrictToken(String token) {
        ensureLoaded();
        if (token == null || token.isEmpty()) return null;
        String norm = simplify(token);
        if (DISTRICT_PARENTS.containsKey(norm)) return norm;
        int n = norm.length();
        if (n < 4 || n > 12) return null;
        String best = null;
        for (String key : DISTRICT_PARENTS.keySet()) {
            int diff = n - key.length();
            if (diff >= 1 && diff <= 4 && norm.endsWith(key) && isHanPrefix(norm, diff)) {
                if (best == null || key.length() > best.length()) best = key;
            }
        }
        return best;
    }

    // ==================== 规范名归一（分布聚合 / 写入口共用同一口径） ====================

    /** 是否为全国官方省级行政区划名（gazetteer 省级列 + 港澳台）。 */
    public static boolean isOfficialProvinceName(String name) {
        ensureLoaded();
        return name != null && OFFICIAL_PROVINCES.contains(simplify(name.trim()));
    }

    /**
     * 全部官方省级行政区划名快照（gazetteer 省级列 + 港澳台）。
     * 供 SQL 层判定「省级是否已标注」——上不了地图的客户才算未标注地区。
     */
    public static List<String> officialProvinces() {
        ensureLoaded();
        return List.copyOf(OFFICIAL_PROVINCES);
    }

    /**
     * 与 {@link #officialProvinces()} 配套的 SQL 占位符（{@code ?,?,…}）。
     * 供「省级是否已标注」的 IN 条件复用，避免各处硬编码省名。
     */
    public static String officialProvincePlaceholders() {
        ensureLoaded();
        return OFFICIAL_PROVINCES.stream().map(p -> "?").collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 省份原值 → 规范全名（简称补齐后缀、繁体归一，如「浙江」→「浙江省」）。
     * 无法识别写法返回简化后的原值，由调用方保留计数并交由前端判定是否可上地图。
     */
    public static String canonicalProvinceName(String raw) {
        ensureLoaded();
        String s = raw == null ? null : raw.trim();
        if (s == null || s.isEmpty()) return null;
        String norm = simplify(s);
        if (OFFICIAL_PROVINCES.contains(norm)) return norm;
        String alias = PROVINCE_ALIAS.get(norm);
        if (alias != null) return alias;
        String userAlias = ALIAS_PROVINCE.get(norm);
        if (userAlias != null) return userAlias;
        return norm;
    }

    /**
     * 氚云「地区」等省级标注 → 规范省名。
     * <p>先剥离尾部括号简称注释（「浙江省（浙）」→「浙江省」），再按 {@link #canonicalProvinceName}
     * 归一；仍不命中时按官方名简称前缀匹配截断写法（「广西壮族」→「广西壮族自治区」、
     * 「新疆维吾尔」→「新疆维吾尔自治区」）。无法识别（含「国外」）返回 null。
     */
    public static String canonicalProvinceFromRegionTag(String raw) {
        if (raw == null) return null;
        String s = simplify(raw.trim()).replaceAll("[（(][^（）()]*[)）]$", "").trim();
        if (s.isEmpty()) return null;
        String direct = canonicalProvinceName(s);
        if (isOfficialProvinceName(direct)) return direct;
        for (Map.Entry<String, String> e : PROVINCE_ALIAS.entrySet()) {
            if (s.startsWith(e.getKey()) && isOfficialProvinceName(e.getValue())) return e.getValue();
        }
        return null;
    }

    /**
     * 城市原值 → 规范城市名：命中字典地级市即返回官方全名，缺省去后缀写法
     * （如「东莞」→「东莞市」）可补全；直辖市城市字段与省同名（存储约定）。
     * 无法识别时返回简化原值（保留计数，由前端判定地图未收录）。
     */
    public static String canonicalCityName(String raw, String provinceHint) {
        ensureLoaded();
        String s = raw == null ? null : raw.trim();
        if (s == null || s.isEmpty()) return null;
        String norm = simplify(s);
        if (MUNICIPALITIES.contains(norm)) return norm;
        if (norm.length() >= 2 && !norm.endsWith("市") && CITY_PROVINCE.containsKey(norm + "市")) {
            return norm + "市";
        }
        String userAlias = lookupScopedAlias(ALIAS_CITY, norm, provinceHint);
        if (userAlias != null) return userAlias;
        return norm;
    }

    /**
     * 区县原值 → 规范区县名：整 token 命中字典即返回官方写法；否则仅当已知省份可消歧时
     * 剥离「广州白云区」式前缀杂质；未命中返回简化原值（镇/街道等三级以下写法原样保留）。
     */
    public static String canonicalDistrictName(String raw, String provinceHint) {
        ensureLoaded();
        String s = raw == null ? null : raw.trim();
        if (s == null || s.isEmpty()) return null;
        String norm = simplify(s);
        if (DISTRICT_PARENTS.containsKey(norm)) return norm;
        DistrictResolve hit = resolveDistrictToken(raw, provinceHint);
        if (hit != null) return hit.district();
        String userAlias = lookupScopedAlias(ALIAS_DISTRICT, norm, provinceHint);
        if (userAlias != null) return userAlias;
        return norm;
    }

    /** 区县名推断命中结果（含规范区县名）。 */
    public record DistrictResolve(String province, String city, String district) {
    }

    /** 在已知省份下，由区县名反查其归属地级市；城市不唯一或为伪层时返回 null。 */
    public static String cityForDistrict(String token, String province) {
        ensureLoaded();
        if (token == null || province == null) return null;
        String norm = simplify(token);
        List<RegionCity> parents = DISTRICT_PARENTS.get(norm);
        if (parents == null) return null;
        Set<String> cities = new LinkedHashSet<>();
        for (RegionCity rc : parents) {
            if (province.equals(rc.province()) && rc.city() != null && !PSEUDO_CITY.equals(rc.city())) {
                cities.add(rc.city());
            }
        }
        return cities.size() == 1 ? cities.iterator().next() : null;
    }

    /** 区县是否明确属于指定省市；用于处理“向阳区”等同一省内跨市重名区县。 */
    public static boolean districtBelongsTo(String token, String province, String city) {
        ensureLoaded();
        if (token == null || province == null || city == null) return false;
        List<RegionCity> parents = DISTRICT_PARENTS.get(simplify(token.trim()));
        if (parents == null) return false;
        for (RegionCity parent : parents) {
            if (province.equals(parent.province()) && city.equals(parent.city())) return true;
        }
        return false;
    }

    /** 区县名是否在字典中存在（精确、归一后比较）。 */
    public static boolean isKnownDistrict(String token) {
        ensureLoaded();
        return token != null && DISTRICT_PARENTS.containsKey(simplify(token));
    }

    /** 官方地级市 → 归属省份；直辖市返回自身；非官方（含县级市等）返回 null。 */
    public static String provinceOfCity(String city) {
        ensureLoaded();
        if (city == null) return null;
        String norm = simplify(city.trim());
        if (MUNICIPALITIES.contains(norm)) return norm;
        return CITY_PROVINCE.get(norm);
    }

    /** 官方区县名是否归属于给定省份（覆盖直辖市与省直辖县级行政区划的下辖区县）。 */
    public static boolean isDistrictInProvince(String district, String province) {
        ensureLoaded();
        if (district == null || province == null) return false;
        String d = simplify(district.trim());
        List<RegionCity> parents = DISTRICT_PARENTS.get(d);
        if (parents == null) return false;
        String p = simplify(province.trim());
        return parents.stream().anyMatch(rc -> p.equals(rc.province()));
    }

    /** 城市名是否在字典中存在（含地级市、县级市及直辖市）。 */
    public static boolean isKnownCity(String token) {
        ensureLoaded();
        if (token == null || token.isEmpty()) return false;
        String norm = simplify(token);
        return MUNICIPALITIES.contains(norm) || CITY_PROVINCE.containsKey(norm);
    }

    /** 该城市名是否为「县级市」（在字典中同时是区县级节点，且能归属到唯一地级市）。 */
    public static boolean isCountyLevelCity(String token, String province) {
        ensureLoaded();
        if (token == null || province == null) return false;
        String norm = simplify(token);
        if (!DISTRICT_PARENTS.containsKey(norm)) return false;
        return cityForDistrict(norm, province) != null;
    }

    // ==================== 县级市「当市级」归一 ====================
    // 客户档案与地址文本里，县级市常被直接填在「市」字段（如 湖北省/仙桃市、江苏省/江阴市）。
    // 严格按地址选择器，第二级应是归属地级市；但省直辖县级行政区划下的县级市没有真实地级市可归，
    // 故统一口径：能被字典确认归属的县级市，允许当市级使用。

    /**
     * 县级市写法当作「市」字段使用时的归一口径：
     * <ul>
     *   <li>有真实地级市父级（江阴市 → 无锡市）：返回父地级市；</li>
     *   <li>省直辖县级市（仙桃市 / 济源市 / 海南各县级市，父层为「省直辖县级行政区划」伪层）：返回自身。</li>
     * </ul>
     * 非县级市写法（普通地级市、区/县、镇）或该省下归属不唯一时返回 null。
     */
    public static String asCityOfCountyLevelCity(String token, String province) {
        ensureLoaded();
        if (token == null || province == null) return null;
        String norm = simplify(token.trim());
        if (!norm.endsWith("市")) return null;
        List<RegionCity> parents = DISTRICT_PARENTS.get(norm);
        if (parents == null) return null;
        RegionCity only = null;
        for (RegionCity rc : parents) {
            if (!province.equals(rc.province())) continue;
            if (only != null) return null; // 该省下归属不唯一，不猜测
            only = rc;
        }
        if (only == null || only.city() == null) return null;
        return PSEUDO_CITY.equals(only.city()) ? norm : only.city();
    }

    /**
     * 该写法能否作为「市」字段被地址选择器接受：官方地级市，或该省下可归一的县级市。
     * 直辖市市字段必须与省同名；省份未知时只认官方地级市（保守，不猜测县级市归属）。
     */
    public static boolean isUsableAsCity(String token, String province) {
        ensureLoaded();
        if (token == null || token.isBlank()) return false;
        String norm = simplify(token.trim());
        if (province == null || province.isBlank()) return isKnownCity(norm);
        if (isMunicipalityProvince(province)) return norm.equals(province);
        if (isKnownCity(norm)) return province.equals(provinceOfCity(norm));
        return asCityOfCountyLevelCity(norm, province) != null;
    }

    /** 全部官方地级市（规范写法；不含直辖市与「省直辖县级行政区划」伪层）。 */
    public static List<String> allCities() {
        ensureLoaded();
        return List.copyOf(CITY_PROVINCE.keySet());
    }

    /**
     * 指定省份下的全部官方地级市（规范写法；不含直辖市与「省直辖县级行政区划」伪层）。
     * 用于地址文本扫描兜底：解析器切不出城市时，拿这份名单在原文里做包含匹配。
     */
    public static List<String> citiesOfProvince(String province) {
        ensureLoaded();
        if (province == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : CITY_PROVINCE.entrySet()) {
            if (province.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** 指定省市下的全部官方区县名（规范写法）。用途同 {@link #citiesOfProvince(String)}。 */
    public static List<String> districtsOfCity(String province, String city) {
        ensureLoaded();
        if (province == null || city == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<RegionCity>> e : DISTRICT_PARENTS.entrySet()) {
            for (RegionCity rc : e.getValue()) {
                if (province.equals(rc.province()) && city.equals(rc.city())) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    /** 县级市当市级时，区县字段与其同名是否可接受（如 市=仙桃市、区=仙桃市）。 */
    public static boolean isCountyCitySelfDistrict(String district, String province, String city) {
        if (district == null || province == null || city == null) return false;
        if (!simplify(district.trim()).equals(simplify(city.trim()))) return false;
        return asCityOfCountyLevelCity(city, province) != null;
    }

    /** 字典命中结果。 */
    public record RegionHit(String province, String city, boolean cityLevel) {
    }
}
