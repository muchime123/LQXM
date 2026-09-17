package com.liqiang.archive.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国大陆地址 → 省 / 市 / 区县 纯文本解析器（无外部依赖、无状态，可复用）。
 *
 * <p>服务于多条「客户档案整理」链路：销售出库地址回填、客户档案表单解析联动、
 * 待整理客户预览/批量应用等，统一入口避免各写一套切分逻辑。
 *
 * <p>规则约定：
 * <ul>
 *   <li>省名在文本中任意位置识别（兼容「收货地址：广东省…」「XX公司…福建省」等带前缀写法），
 *       输出规范全名（浙江 / 广西壮族 等一律归并为「浙江省 / 广西壮族自治区」，
 *       直辖市「北京市/天津市/上海市/重庆市」视为省级，city 与省同名，符合系统存储约定；
 *       另收录历史导入的省名错字与繁体写法别名）；</li>
 *   <li>市按「市 / 自治州 / 地区 / 盟」标记切分，区县按「区 / 县 / 旗 / 市(县级市)」等切分，
 *       地名主体须为连续汉字，避免把前缀（收货地址、联系电话等）带进省市县字段；</li>
 *   <li>文本缺省名但能命中区划字典 {@link ChinaRegionDictionary} 内唯一市/区县归属时反查补省
 *       （跨省重名的区县在不明确归属时不猜测）；</li>
 *   <li>无法识别的层级置 null，不猜测。</li>
 * </ul>
 */
public final class ChinaAddressParser {

    private ChinaAddressParser() {
        // utility class
    }

    /** 解析结果。province 为规范省名（可能为 null），city/district 为原文层级名（可能为 null）。 */
    public record ParsedAddress(String province, String city, String district) {
        public boolean isEmpty() {
            return province == null && city == null && district == null;
        }

        public boolean isComplete() {
            return province != null;
        }
    }

    /**
     * 判断城市名是否为区划字典中的可选值（直辖市、地级市、县级市）。
     */
    public static boolean isKnownCity(String token) {
        return ChinaRegionDictionary.isKnownCity(token);
    }

    /**
     * 判断区县名是否为区划字典中的可选值。
     */
    public static boolean isKnownDistrict(String token) {
        return ChinaRegionDictionary.isKnownDistrict(token);
    }

    private static final ParsedAddress EMPTY = new ParsedAddress(null, null, null);

    /** 直辖市短写 → 规范全名。 */
    private static final Map<String, String> MUNICIPALITY_CANONICAL = Map.of(
            "北京", "北京市",
            "上海", "上海市",
            "天津", "天津市",
            "重庆", "重庆市");

    /** 省份短写 → 规范全名（与 ECharts 中国地图 regionName 对齐），不含直辖市。 */
    private static final List<Map.Entry<String, String>> PROVINCE_ALIASES = List.of(
            // 3 字短写在前，避免「内蒙古/黑龙江」被前两个字误判为其他省
            Map.entry("内蒙古", "内蒙古自治区"),
            Map.entry("黑龙江", "黑龙江省"),
            Map.entry("河北", "河北省"),
            Map.entry("山西", "山西省"),
            Map.entry("辽宁", "辽宁省"),
            Map.entry("吉林", "吉林省"),
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
            Map.entry("广西", "广西壮族自治区"),
            Map.entry("西藏", "西藏自治区"),
            Map.entry("宁夏", "宁夏回族自治区"),
            Map.entry("新疆", "新疆维吾尔自治区"),
            Map.entry("香港", "香港特别行政区"),
            Map.entry("澳门", "澳门特别行政区"),
            // 历史导入的省名错字（缺/误一个部首字）与繁体写法。命中规则与正式别名相同
            // （取最早位置、同位更长者优先），正文中若含正式写法会先于这些别名命中，避免误判。
            Map.entry("东省", "山东省"),
            Map.entry("江省", "浙江省"),
            Map.entry("苏省", "江苏省"),
            Map.entry("安微省", "安徽省"),
            Map.entry("福健省", "福建省"),
            Map.entry("林省", "吉林省"),
            Map.entry("南省", "河南省"),
            Map.entry("广省", "广东省"),
            Map.entry("渐江省", "浙江省"),
            Map.entry("苏州省", "江苏省"),
            Map.entry("内蒙省", "内蒙古自治区"),
            Map.entry("廣東省", "广东省"),
            Map.entry("廣東", "广东省"),
            Map.entry("廣西", "广西壮族自治区"),
            Map.entry("台灣省", "台湾省"),
            Map.entry("台灣", "台湾省"),
            Map.entry("臺灣省", "台湾省"),
            Map.entry("臺灣", "台湾省"),
            Map.entry("澳門", "澳门特别行政区"));

    /** 规范省名集合（含短写别名），用于判定档案省字段是否已是标准地区值。 */
    private static final Set<String> STANDARD_PROVINCE_NAMES = buildStandardProvinces();

    private static Set<String> buildStandardProvinces() {
        Set<String> set = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : PROVINCE_ALIASES) {
            set.add(e.getKey());
            set.add(e.getValue());
        }
        set.addAll(MUNICIPALITY_CANONICAL.keySet());
        set.addAll(MUNICIPALITY_CANONICAL.values());
        return set;
    }

    /**
     * 判断某文本是否为「规范省名/省名短写」。用于档案清洗：省字段只允许存放标准省名，
     * 历史把整段地址/备注塞进省字段的脏值将被清理为空（进入待标注）。
     */
    public static boolean isStandardProvince(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        return !v.isEmpty() && STANDARD_PROVINCE_NAMES.contains(v);
    }

    /** 全角/半角括号（含「浙江省（浙）」这类别名括注）与一切空白，均不参与层级切分。 */
    private static final Pattern NOISE_RE = Pattern.compile("[（(][^（）()]*[）)]|\\s+");

    /** 地址中常见的无意义括注：「（ xxx ）」「【转 xxx 】」「[ xxx ]」「< xxx >」等。 */
    private static final Pattern BRACKET_NOISE_RE = Pattern.compile(
            "[（(][^（）()]*[）)]|【[^】]*】|\\[[^\\]]*\\]|<[^>]*>");

    /** 开头/结尾的非地址字符（如逗号、分号、邮编 000000、连接符等）。地址体必须以汉字/字母/数字开始/结束。 */
    private static final Pattern LEADING_TRAILING_JUNK_RE = Pattern.compile(
            "^[^\\p{IsHan}\\p{L}\\p{N}]++|[^\\p{IsHan}\\p{L}\\p{N}]++$");

    /** 末尾 6 位邮编（前面可带逗号/分号/句号等分隔符）。 */
    private static final Pattern POSTAL_SUFFIX_RE = Pattern.compile("[,，;；。\\.\\s]*\\d{6}$");

    /** 市名主体：连续汉字（2~14）+ 市/地区/盟/自治州 后缀。 */
    private static final Pattern CITY_TOKEN_RE = Pattern.compile("[\\p{IsHan}]{2,14}?(?:自治州|地区|盟|市)");

    /** 市后缀列表（按此顺序扫描，用于字典感知的最短有效市名提取）。 */
    private static final List<String> CITY_SUFFIXES = List.of("自治州", "地区", "盟", "市");

    /** 区县名主体：连续汉字（2~18）+ 区/县/旗/市/镇 后缀（含简繁写法与「自治县」，县级市以「市」结尾）。 */
    private static final Pattern DISTRICT_TOKEN_RE = Pattern.compile(
            "[\\p{IsHan}]{2,18}?(?:自治县|自治縣|县|縣|区|區|旗|市|镇|鎮)");

    /** 直辖市下常见的「市辖区」中间层，不视为有效区县名。 */
    private static final String MUNI_DISTRICT_PLACEHOLDER = "市辖区";

    /** 命中结果（含在整段文本中的绝对位置，便于多处候选取最早/最长）。 */
    private record ProvinceHit(String canonical, int start, int end, boolean municipality) {
    }

    /** 城市命中：市名 + 其后剩余文本。 */
    private record CityToken(String token, String rest) {
    }

    /** 解析一段中文地址文本。空输入或无法识别任意层级时返回全部为 null 的结果。 */
    public static ParsedAddress parse(String raw) {
        if (raw == null) return EMPTY;
        String s = raw.trim();
        if (s.isEmpty()) return EMPTY;
        s = BRACKET_NOISE_RE.matcher(s).replaceAll("").trim();
        s = POSTAL_SUFFIX_RE.matcher(s).replaceAll("").trim();
        s = LEADING_TRAILING_JUNK_RE.matcher(s).replaceAll("").trim();
        s = NOISE_RE.matcher(s).replaceAll("").trim();
        if (s.isEmpty()) return EMPTY;

        ProvinceHit province = findProvinceAnywhere(s);
        if (province != null) {
            return parseWithProvince(s, province);
        }
        // 整段无省名：按 市→区县 或 仅区县 解析；命中区划字典内唯一归属时反查补省
        CityToken city = findCityToken(s);
        if (city != null) {
            String district = findDistrictToken(city.rest());
            return inferWithoutProvince(city.token(), district);
        }
        return inferWithoutProvince(null, findDistrictToken(s));
    }

    /** 文本已命中省名（含直辖市）时的解析：市/区县按层级切分，并回填缺省的城市层。 */
    private static ParsedAddress parseWithProvince(String s, ProvinceHit province) {
        String rest = s.substring(province.end());
        if (province.municipality()) {
            // 直辖市：市字段与省同名（系统存储约定）；省下直接是区县（跳过「市辖区」占位层）。
            // 若地址在省名后又重复了一次市名（如「天津市天津市静海区…」），先剥掉再取区县，
            // 避免把重复的「天津市」当区县 token。
            if (rest.startsWith(province.canonical())) {
                rest = rest.substring(province.canonical().length());
            }
            return new ParsedAddress(province.canonical(), province.canonical(), findDistrictToken(rest));
        }
        CityToken city = findCityToken(rest);
        if (city != null) {
            String canonicalCity = canonicalCityToken(city.token());
            String district = findDistrictToken(city.rest());
            String effectiveCity = canonicalCity != null ? canonicalCity : city.token();
            // 县级市上提：「广东省湛江廉江市」应拆成 湛江市 / 廉江市（县级市作区县）
            if (ChinaRegionDictionary.isCountyLevelCity(effectiveCity, province.canonical())) {
                String prefecture = ChinaRegionDictionary.cityForDistrict(effectiveCity, province.canonical());
                if (prefecture != null) {
                    int idx = rest.indexOf(city.token());
                    boolean prefixIsPrefecture = false;
                    if (idx > 0) {
                        String prefix = rest.substring(0, idx);
                        String prefectureStem = prefecture.endsWith("市")
                                ? prefecture.substring(0, prefecture.length() - 1)
                                : prefecture;
                        prefixIsPrefecture = prefix.endsWith(prefecture) || prefix.endsWith(prefectureStem);
                    }
                    // 前缀就是地级市（或其省略后缀写法）、地址以县级市开头、或下方再无区县级文本时，
                    // 一律上提：city=归属地级市，district=县级市（之后的街道/镇不再进入区县字段）
                    if (prefixIsPrefecture || idx == 0 || district == null) {
                        return new ParsedAddress(province.canonical(), prefecture, effectiveCity);
                    }
                }
            }
            return new ParsedAddress(province.canonical(), effectiveCity, district);
        }
        // 少见：省后直接跟县/区而无「市」层（如「山东省博兴县…」）→ 用字典反查该县归属城市
        String district = findDistrictToken(rest);
        if (district != null) {
            String cityFromDict = ChinaRegionDictionary.cityForDistrict(district, province.canonical());
            if (cityFromDict != null) {
                return new ParsedAddress(province.canonical(), cityFromDict, district);
            }
        }
        return new ParsedAddress(province.canonical(), null, district);
    }

    /** 无省名文本：按 城市/区县 token 在区划字典中反查唯一省份。歧义/未收录则仍置 null，不猜测。 */
    private static ParsedAddress inferWithoutProvince(String cityToken, String districtToken) {
        if (cityToken != null) {
            // 地级市（直辖市省名已在省命中环节处理）→ 省唯一，city 用字典规范名
            ChinaRegionDictionary.RegionHit hit = ChinaRegionDictionary.matchCityToken(cityToken);
            if (hit != null) {
                String district = districtToken;
                if (district != null && district.equals(cityToken)) district = null;
                String city = hit.city();
                // 县级市作区县：如「义乌市」→ 浙江省 / 金华市 / 义乌市
                if (district == null
                        && ChinaRegionDictionary.isCountyLevelCity(city, hit.province())) {
                    String prefecture = ChinaRegionDictionary.cityForDistrict(city, hit.province());
                    if (prefecture != null) {
                        district = city;
                        city = prefecture;
                    }
                }
                return new ParsedAddress(hit.province(), city, district);
            }
            // 县级市等区县级城市名（如 义乌市/昆山市）→ 补省，并上提归属地级市为 city、县级市为 district
            ChinaRegionDictionary.DistrictResolve county =
                    ChinaRegionDictionary.resolveDistrictToken(cityToken, null);
            if (county != null) {
                String city = county.city();
                String district = county.district();
                if (city != null && city.equals(district)) city = null;
                return new ParsedAddress(county.province(), city, district);
            }
        }
        if (districtToken != null) {
            // 区县名反查省/市；命中即顺带归一区县为规范写法（如「武汉武昌区」→ 武昌区）
            ChinaRegionDictionary.DistrictResolve d =
                    ChinaRegionDictionary.resolveDistrictToken(districtToken, null);
            if (d != null) {
                String city = d.city();
                if (city != null && city.equals(d.district())) city = null;
                return new ParsedAddress(d.province(), city, d.district());
            }
            // 无法补省（跨省重名歧义）时仍尽量归一区县为规范写法（如「广州白云区」→ 白云区）
            String canonical = ChinaRegionDictionary.canonicalDistrictToken(districtToken);
            if (canonical != null) districtToken = canonical;
        }
        return new ParsedAddress(null, cityToken, districtToken);
    }

    /** 城市 token 规范化：精确命中或带少量前缀杂质（≤3 汉字）命中地级市字典时返回规范写法，否则原样。 */
    private static String canonicalCityToken(String token) {
        ChinaRegionDictionary.RegionHit hit = ChinaRegionDictionary.matchCityToken(token);
        return hit == null ? null : hit.city();
    }

    // ==================== 宽松解析（字典文本扫描兜底） ====================

    /** 区县主干匹配的排除词：这些词在路名/市场名里高频出现，命中不可信。 */
    private static final Set<String> AMBIGUOUS_DISTRICT_STEMS = Set.of(
            "新区", "城区", "东区", "西区", "南区", "北区", "中区", "市区", "郊区", "开发区",
            "工业区", "园区", "矿区", "林区", "特区", "口岸", "地区", "片区", "景区", "港区",
            "农场", "华侨", "高新", "经济");

    /**
     * 宽松解析：在 {@link #parse(String)} 的切分结果不完整时，用区划字典对原文做文本扫描兜底。
     * <p>
     * 覆盖三类 {@code parse} 切不出来的写法：
     * <ol>
     *   <li>城市名不带行政后缀（「河北石家庄…」「温州乐清…」「烟台开发区…」）；</li>
     *   <li>省市同名被省名抢先消耗（「吉林市船营区…」→ 省=吉林省后余下「市船营区」）；</li>
     *   <li>省/市已定但区县缺失（「山东省威海市经区…」这类功能区写法只补到市）。</li>
     * </ol>
     * 兜底只补解析器没切出来的层级，不覆盖已解析出的值；扫描未命中或存在歧义时留空，不猜测。
     */
    public static ParsedAddress parseLoose(String raw) {
        ParsedAddress base = parse(raw);
        String text = raw == null ? "" : ChinaRegionDictionary.simplify(raw);
        if (text.isEmpty()) return base;

        String province = base.province();
        String city = base.city();
        String district = base.district();

        if (province == null) {
            String scanned = scanCity(text, null);
            if (scanned != null) {
                city = scanned;
                province = ChinaRegionDictionary.provinceOfCity(scanned);
                district = null; // 省市换了，原区县结论作废
            }
        }
        // 城市与省对不上（如「安徽省马鞍山市…」被切成 马 + 鞍山市）：按省重新扫描
        if (province != null && city != null) {
            String cityProvince = ChinaRegionDictionary.provinceOfCity(city);
            if (cityProvince != null && !cityProvince.equals(province)) {
                String rescanned = scanCity(text, province);
                city = rescanned;
                district = null;
            }
        }
        if (province != null && city == null) {
            city = scanCity(text, province);
        }
        if (province != null && city != null
                && (district == null || !ChinaRegionDictionary.districtBelongsTo(district, province, city))) {
            district = scanDistrict(text, province, city);
        }
        return new ParsedAddress(province, city, district);
    }

    /** 在原文中扫描城市名（长名优先）；province 非空时只在该省城市名单内扫描。 */
    private static String scanCity(String text, String province) {
        if (province == null) {
            for (String m : ChinaRegionDictionary.officialProvinces()) {
                if (!ChinaRegionDictionary.isMunicipalityProvince(m)) continue;
                if (containsName(text, m)) return m;
            }
        }
        List<String> pool = province == null
                ? ChinaRegionDictionary.allCities()
                : ChinaRegionDictionary.citiesOfProvince(province);
        String best = null;
        for (String name : pool) {
            if (!containsName(text, name)) continue;
            if (best == null || name.length() > best.length()) best = name;
        }
        return best;
    }

    /**
     * 在原文中扫描所属区县名：先试全名，再试去后缀主干。
     * 主干匹配会命中「乐清市→乐清」「万江街道→万江」这类省略后缀的写法，
     * 但路名市场名里高频出现的泛化词（新区/城区/开发区…）一律排除，避免误判。
     */
    private static String scanDistrict(String text, String province, String city) {
        List<String> pool = ChinaRegionDictionary.districtsOfCity(province, city);
        String best = null;
        for (String name : pool) {
            if (!text.contains(name)) continue;
            if (best == null || name.length() > best.length()) best = name;
        }
        if (best != null) return best;
        // 与城市同名的区县（长沙县 / 南昌县）不能靠主干匹配——原文里的「长沙市」会被误当成「长沙县」
        String cityStem = stripCitySuffix(city);
        int bestStem = 0;
        for (String name : pool) {
            String stem = stripDistrictSuffix(name);
            if (stem.length() < 2 || stem.length() <= bestStem) continue;
            if (stem.equals(cityStem)) continue;
            if (AMBIGUOUS_DISTRICT_STEMS.contains(stem) || !text.contains(stem)) continue;
            best = name;
            bestStem = stem.length();
        }
        return best;
    }

    /** 名称是否出现在原文中：全名命中，或去掉行政后缀后的主干（≥2 字）命中。 */
    private static boolean containsName(String text, String name) {
        if (text.contains(name)) return true;
        String stem = stripCitySuffix(name);
        return stem.length() >= 2 && text.contains(stem);
    }

    private static String stripCitySuffix(String name) {
        for (String suffix : CITY_SUFFIXES) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private static String stripDistrictSuffix(String name) {
        for (String suffix : List.of("自治县", "自治旗", "自治州", "特区", "林区", "地区", "旗", "区", "县", "市", "盟")) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /** 在整段文本中定位省名/直辖市名（任意位置，取最先出现者；同位取更长匹配）。 */
    private static ProvinceHit findProvinceAnywhere(String s) {
        ProvinceHit best = null;
        for (Map.Entry<String, String> e : PROVINCE_ALIASES) {
            String full = e.getValue();
            int i = s.indexOf(full);
            if (i >= 0) best = pick(best, new ProvinceHit(full, i, i + full.length(), false));
            String key = e.getKey();
            int j = s.indexOf(key);
            if (j >= 0) best = pick(best, new ProvinceHit(full, j, j + key.length(), false));
        }
        for (Map.Entry<String, String> e : MUNICIPALITY_CANONICAL.entrySet()) {
            String full = e.getValue();
            int i = s.indexOf(full);
            if (i >= 0) best = pick(best, new ProvinceHit(full, i, i + full.length(), true));
            String key = e.getKey();
            int j = s.indexOf(key);
            if (j >= 0) best = pick(best, new ProvinceHit(full, j, j + key.length(), true));
        }
        return best;
    }

    private static ProvinceHit pick(ProvinceHit current, ProvinceHit candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        if (candidate.start() < current.start()) return candidate;
        if (candidate.start() > current.start()) return current;
        // 同位取更长匹配（「浙江省」优先于「浙江」），避免短写把后缀留给市/区县误判
        int lenDiff = (candidate.end() - candidate.start()) - (current.end() - current.start());
        return lenDiff > 0 ? candidate : current;
    }

    /**
     * 在文本中定位第一个「字典内有效」的市名 token。
     * 扫描每个市后缀位置，取以该后缀结尾的最短有效市名（如「湛江廉江市」优先命中「廉江市」
     * 而不是「湛江廉江市」，便于后续在 parseWithProvince 中把县级市上提为区县）。
     */
    private static CityToken findCityToken(String text) {
        if (text == null || text.isEmpty()) return null;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            for (String suffix : CITY_SUFFIXES) {
                int suffixEnd = i + suffix.length();
                if (suffixEnd > n) continue;
                if (!text.regionMatches(i, suffix, 0, suffix.length())) continue;
                int runStart = startOfHanRun(text, i);
                if (runStart < 0 || runStart >= i) continue;
                int maxLen = Math.min(14, i - runStart);
                for (int len = 2; len <= maxLen; len++) {
                    String token = text.substring(i - len, suffixEnd);
                    if (isKnownCityToken(token)) {
                        return new CityToken(token, text.substring(suffixEnd));
                    }
                }
            }
        }
        return null;
    }

    private static int startOfHanRun(String text, int beforeIndex) {
        int i = beforeIndex - 1;
        while (i >= 0 && isHan(text.charAt(i))) i--;
        return i + 1;
    }

    private static boolean isHan(char c) {
        return c >= '\u4E00' && c <= '\u9FFF';
    }

    /** 有效市名 token：地级市/直辖市命中，或归属唯一的县级市（如 廉江市/义乌市）。 */
    private static boolean isKnownCityToken(String token) {
        if (ChinaRegionDictionary.matchCityToken(token) != null) return true;
        return ChinaRegionDictionary.resolveDistrictToken(token, null) != null;
    }

    private static String findDistrictToken(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = DISTRICT_TOKEN_RE.matcher(text);
        if (!m.find()) return null;
        String token = m.group();
        if (MUNI_DISTRICT_PLACEHOLDER.equals(token)) {
            // 跳过「市辖区」占位层，继续向后找真实区县
            String tail = text.substring(m.end());
            return findDistrictToken(tail);
        }
        return token;
    }
}
