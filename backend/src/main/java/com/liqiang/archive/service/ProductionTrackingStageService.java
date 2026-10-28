package com.liqiang.archive.service;

import com.liqiang.archive.service.ProductionTrackingStageCatalog.StageDef;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 制程节点管理：车间类别下「有哪些跟单节点、什么顺序、哪些是按需工序」的增删改。
 *
 * <p>节点原先硬编码在 {@link ProductionTrackingPolicy}，加一个节点要改常量 + 写迁移 + 改测试 + 发版。
 * 落表后在这里维护，保存后整体替换 {@link ProductionTrackingStageCatalog} 的进程内快照，
 * 列表 / 导出 / 筛选 / 日期推算立即按新制程走。
 *
 * <p><b>两条硬约束</b>（都是为了避免历史跟单数据失联）：
 * <ol>
 *   <li><b>节点编码不可修改</b>：历史里程碑行靠 {@code stage_code} 关联，改编码等于把所有记录变成
 *       孤儿（且基准表也按编码存）。要换编码就停用旧的、新建一条。</li>
 *   <li><b>有记录的节点不可删除，只能停用</b>：停用它不进完成分母、不显示在节点条上，历史记录原样保留。</li>
 * </ol>
 *
 * <p><b>影响面</b>：改制程会当场改写所有在制工单的「已完成 x/y」判定——新增一个必经节点，
 * 原本已判完成的单会集体退回「跟单中」。所以页面在保存前会调 {@link #impact(String)} 给出受影响单数。
 */
@Service
public class ProductionTrackingStageService {
    private static final Logger log=LoggerFactory.getLogger(ProductionTrackingStageService.class);
    private static final String SEALER=ProductionTrackingStageCatalog.SEALER;
    private static final String MACHINE=ProductionTrackingStageCatalog.MACHINE;
    private static final String SELECT="SELECT workshop_kind,stage_code,stage_label,sort_no,optional,"
            +"match_keywords,exclude_keywords,header_aliases,enabled FROM production_tracking_stage_defs "
            +"ORDER BY workshop_kind,sort_no,id";

    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public ProductionTrackingStageService(JdbcTemplate jdbc,AuditLogService audit) {this.jdbc=jdbc;this.audit=audit;}

    /**
     * 启动时载入快照。失败时<b>保留内置默认值</b>而不是让应用起不来——
     * 制程是配置，配错了页面改回来即可；起不来则整个跟单功能不可用。
     */
    @PostConstruct
    public void reload() {
        try{
            var defs=jdbc.query(SELECT,(r,n)->new StageDef(r.getString("workshop_kind"),r.getString("stage_code"),
                    r.getString("stage_label"),r.getInt("sort_no"),r.getInt("optional")==1,
                    r.getString("match_keywords"),r.getString("exclude_keywords"),
                    splitAliases(r.getString("header_aliases")),r.getInt("enabled")==1));
            if(defs.isEmpty()){log.warn("制程节点表为空，沿用内置默认制程");return;}
            ProductionTrackingStageCatalog.replace(defs);
        }catch(RuntimeException e){
            log.error("制程节点表加载失败，沿用内置默认制程",e);
        }
    }

    /** 制程列表（含已停用的），带每条节点已产生的跟单记录数——决定它能不能被删除。 */
    public List<Map<String,Object>> list(String kind) {
        checkKind(kind);
        return jdbc.queryForList("SELECT d.id,d.workshop_kind AS workshopKind,d.stage_code AS stageCode,"
                +"d.stage_label AS stageLabel,d.sort_no AS sortNo,d.optional,"
                +"d.match_keywords AS matchKeywords,d.exclude_keywords AS excludeKeywords,"
                +"d.header_aliases AS headerAliases,d.enabled,"
                +"("+usageCountSql(kind)+") AS usageCount "
                +"FROM production_tracking_stage_defs d WHERE d.workshop_kind=? ORDER BY d.sort_no,d.id",kind);
    }

    @Transactional
    public Map<String,Object> create(StageSave req) {
        checkKind(req.kind());
        String code=normalizeCode(req.code());
        String label=requireLabel(req.label());
        if(count("SELECT COUNT(*) FROM production_tracking_stage_defs WHERE workshop_kind=? AND stage_code=?",
                req.kind(),code)>0)throw new IllegalArgumentException("该车间类别下已有节点编码 "+code);
        if(count("SELECT COUNT(*) FROM production_tracking_stage_defs WHERE workshop_kind=? AND stage_label=?",
                req.kind(),label)>0)throw new IllegalArgumentException("该车间类别下已有同名节点："+label);
        boolean optional=Boolean.TRUE.equals(req.optional());
        if(optional&&blank(req.matchKeywords()))
            throw new IllegalArgumentException("按需工序必须填写命中关键词，否则没有任何工单会被判为要走这道工序");
        // 跨类别一致性（10.4）：不带车间的 isOptionalStage 判定依赖"同编码按需属性跨类别一致"，
        // 这里强制该不变量，防止类别间按需属性不一致导致跨类别判定误判。
        Integer otherOptional=countOtherKindOptional(req.kind(),code);
        if(otherOptional!=null&&otherOptional!=(optional?1:0))
            throw new IllegalArgumentException("节点编码 "+code+" 已在其他车间类别存在且按需属性不一致，"
                    +"会导致跨类别按需判定误判；请与既有类别保持一致或改编码");
        int sortNo=jdbc.queryForObject("SELECT COALESCE(MAX(sort_no),0)+1 FROM production_tracking_stage_defs "
                +"WHERE workshop_kind=?",Integer.class,req.kind());
        jdbc.update("INSERT INTO production_tracking_stage_defs(workshop_kind,stage_code,stage_label,sort_no,"
                +"optional,match_keywords,exclude_keywords,header_aliases,enabled) VALUES(?,?,?,?,?,?,?,?,?)",
                req.kind(),code,label,sortNo,optional?1:0,trimOrNull(req.matchKeywords()),
                trimOrNull(req.excludeKeywords()),trimOrNull(req.headerAliases()),enabledOf(req.enabled()));
        reload();
        audit.log("STAGE_DEF",0L,code,"新增节点",req.kind()+" 新增「"+label+"」"+(optional?"（按需）":""));
        return Map.of("stageCode",code,"sortNo",sortNo);
    }

    @Transactional
    public Map<String,Object> update(long id,StageSave req) {
        var current=jdbc.queryForMap("SELECT workshop_kind,stage_code,stage_label FROM production_tracking_stage_defs "
                +"WHERE id=?",id);
        String kind=(String)current.get("workshop_kind");
        String label=requireLabel(req.label());
        // 编码不可改：改了会让历史里程碑行与基准行全部失联（要换编码请停用旧的、新建一条）。
        if(req.code()!=null&&!req.code().isBlank()
                &&!normalizeCode(req.code()).equals(current.get("stage_code")))
            throw new IllegalArgumentException("节点编码不可修改；如需更换请停用本节点并新建一条");
        if(count("SELECT COUNT(*) FROM production_tracking_stage_defs WHERE workshop_kind=? AND stage_label=? AND id<>?",
                kind,label,id)>0)throw new IllegalArgumentException("该车间类别下已有同名节点："+label);
        boolean optional=Boolean.TRUE.equals(req.optional());
        if(optional&&blank(req.matchKeywords()))
            throw new IllegalArgumentException("按需工序必须填写命中关键词，否则没有任何工单会被判为要走这道工序");
        // 跨类别一致性（10.4）：同编码按需属性须跨类别一致，否则不带车间的 isOptionalStage 判定会误判。
        Integer otherOptional=countOtherKindOptional(kind,(String)current.get("stage_code"));
        if(otherOptional!=null&&otherOptional!=(optional?1:0))
            throw new IllegalArgumentException("节点编码 "+current.get("stage_code")+" 已在其他车间类别存在且按需属性不一致，"
                    +"会导致跨类别按需判定误判；请与既有类别保持一致");
        jdbc.update("UPDATE production_tracking_stage_defs SET stage_label=?,optional=?,match_keywords=?,"
                +"exclude_keywords=?,header_aliases=?,enabled=? WHERE id=?",
                label,optional?1:0,trimOrNull(req.matchKeywords()),trimOrNull(req.excludeKeywords()),
                trimOrNull(req.headerAliases()),enabledOf(req.enabled()),id);
        reload();
        audit.log("STAGE_DEF",id,String.valueOf(current.get("stage_code")),"更新节点","更新「"+label+"」"+(optional?"（按需）":""));
        return Map.of("id",id);
    }

    /** 上移/下移一位：与相邻节点交换 sort_no（制程顺序＝节点条顺序＝链式推算顺序）。 */
    @Transactional
    public void move(long id,boolean up) {
        var current=jdbc.queryForMap("SELECT workshop_kind,sort_no FROM production_tracking_stage_defs WHERE id=?",id);
        String kind=(String)current.get("workshop_kind");
        int sortNo=((Number)current.get("sort_no")).intValue();
        var neighbour=jdbc.query("SELECT id,sort_no FROM production_tracking_stage_defs WHERE workshop_kind=? "
                +"AND sort_no "+(up?"<":">")+" ? ORDER BY sort_no "+(up?"DESC":"ASC")+" LIMIT 1",
                (r,n)->Map.of("id",(Object)r.getLong("id"),"sortNo",(Object)r.getInt("sort_no")),
                kind,sortNo).stream().findFirst().orElse(null);
        if(neighbour==null)return; // 已经在首/末位
        int otherSort=((Number)neighbour.get("sortNo")).intValue();
        jdbc.update("UPDATE production_tracking_stage_defs SET sort_no=? WHERE id=?",otherSort,id);
        jdbc.update("UPDATE production_tracking_stage_defs SET sort_no=? WHERE id=?",sortNo,neighbour.get("id"));
        reload();
        audit.log("STAGE_DEF",id,String.valueOf(current.get("stage_code")),"移动节点",(up?"上移":"下移")+"至 sort_no="+otherSort);
    }

    @Transactional
    public void remove(long id) {
        var current=jdbc.queryForMap("SELECT workshop_kind,stage_code,stage_label FROM production_tracking_stage_defs "
                +"WHERE id=?",id);
        int usage=count("SELECT COUNT(*) FROM production_tracking_milestones m "
                +"JOIN production_orders p ON p.id=m.production_order_id WHERE m.stage_code=? AND "+lineCondition(
                        (String)current.get("workshop_kind")),current.get("stage_code"));
        if(usage>0)throw new IllegalArgumentException("「"+current.get("stage_label")+"」已有 "+usage
                +" 条跟单记录，不能删除；请改为「停用」，历史记录会原样保留");
        jdbc.update("DELETE FROM production_tracking_stage_defs WHERE id=?",id);
        reload();
        audit.log("STAGE_DEF",id,String.valueOf(current.get("stage_code")),"删除节点","删除「"+current.get("stage_label")+"」");
    }

    /**
     * 改制程的影响面：该类别下正在跟单（未停工、未结案、已有节点记录）的工单数，
     * 以及其中当前已判「跟单完成」的数量——新增必经节点时，这批次会集体退回「跟单中」。
     */
    public Map<String,Object> impact(String kind) {
        checkKind(kind);
        var row=jdbc.queryForMap("SELECT COUNT(*) AS openOrders,SUM(CASE WHEN "
                +ProductionTrackingService.TRACKING_COMPLETE+" THEN 1 ELSE 0 END) AS completedOrders"
                +ProductionTrackingService.TRACKING_FROM
                +" WHERE "+lineCondition(kind)+" AND t.stopped_at IS NULL AND t.closed_at IS NULL "
                +"AND COALESCE(ms.node_count,0)>0");
        long open=row.get("openOrders") instanceof Number n?n.longValue():0L;
        long completed=row.get("completedOrders") instanceof Number n?n.longValue():0L;
        return Map.of("workshopKind",kind,"openOrders",open,"completedOrders",completed,
                "requiredStages",ProductionTrackingPolicy.requiredStages(sealerSample(kind)).size());
    }

    /** 保存入参；{@code code} 仅新建时有效（更新时忽略，编码不可改）。 */
    public record StageSave(String kind,String code,String label,Boolean optional,String matchKeywords,
                            String excludeKeywords,String headerAliases,Boolean enabled) {}

    /* ============================ 内部工具 ============================ */

    /** 该类别车间名的 SQL 判定：与 ProductionTrackingPolicy.kindOf 同一个口径。 */
    private static String lineCondition(String kind) {
        return SEALER.equals(kind)?"p.production_line LIKE '%封口%'"
                :"COALESCE(p.production_line,'') NOT LIKE '%封口%'";
    }
    /** 某编码在本类别车间下已产生的跟单记录数（list / remove 共用同一口径）。 */
    private static String usageCountSql(String kind) {
        return "SELECT COUNT(*) FROM production_tracking_milestones m "
                +"JOIN production_orders p ON p.id=m.production_order_id WHERE m.stage_code=d.stage_code "
                +"AND "+lineCondition(kind);
    }
    private static void checkKind(String kind) {
        if(!SEALER.equals(kind)&&!MACHINE.equals(kind))throw new IllegalArgumentException("车间类别无效");
    }
    private static String sealerSample(String kind) {return SEALER.equals(kind)?"封口机":"全自动气调机";}
    private static String normalizeCode(String code) {
        if(code==null||code.isBlank())throw new IllegalArgumentException("请填写节点编码");
        String value=code.trim().toUpperCase().replaceAll("[\\s\\u3000]","");
        if(!value.matches("[A-Z][A-Z0-9_]{0,29}"))
            throw new IllegalArgumentException("节点编码只能是大写字母、数字与下划线，且以字母开头");
        return value;
    }
    private static String requireLabel(String label) {
        if(label==null||label.isBlank())throw new IllegalArgumentException("请填写节点名称");
        String value=label.trim();
        if(value.length()>50)throw new IllegalArgumentException("节点名称不能超过 50 个字");
        return value;
    }
    private static boolean blank(String value) {return value==null||value.isBlank();}
    private static String trimOrNull(String value) {return blank(value)?null:value.trim();}
    private static int enabledOf(Boolean enabled) {return enabled==null||enabled?1:0;}
    private static List<String> splitAliases(String raw) {
        if(raw==null||raw.isBlank())return List.of();
        var out=new ArrayList<String>();
        for(String part:raw.split("[,，]"))if(!part.isBlank())out.add(part.trim());
        return out;
    }
    private int count(String sql,Object... args) {
        Integer value=jdbc.queryForObject(sql,Integer.class,args);
        return value==null?0:value;
    }
    /** 其它车间类别下同一编码的 optional（仅看一条）：为 null 表示没有跨类别同编码，无需一致性约束。 */
    private Integer countOtherKindOptional(String kind,String code) {
        var rows=jdbc.queryForList(
                "SELECT optional FROM production_tracking_stage_defs WHERE workshop_kind<>? AND stage_code=? LIMIT 1",
                kind,code);
        return rows.isEmpty()?null:((Number)rows.getFirst().get("optional")).intValue();
    }
}
