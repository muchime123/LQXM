package com.liqiang.archive.service;

import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.infra.IdempotencyService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导回：把**本系统导出的生产跟单 Excel**（导出 → 线下改 → 导回）写回跟单字段。
 *
 * <p>与「导入生产跟单进度表」是两条不同的链路，勿混：
 * <ul>
 *   <li>进度表导入认的是**氚云进度表模板**（固定 sheet 名、表头第 2 行、阶段名第 3 行、行 4 起）；</li>
 *   <li>导回认的是**本系统自己的导出件**：每个车间一个子表、第 1 行表头（列位置不敏感，一律按表头文字定位），
 *       按「任务号」精确匹配工单。</li>
 * </ul>
 *
 * <p><b>可回写列只有四类（LQ 2026-10-12 定）</b>：跟单备注、车间预计完成（＝延期至 / forecast_finish_date）、
 * 延期原因、各节点完成日期。其余列（产品/规格/数量/各类日期/交付结果…）是源数据或派生值，改了也会被忽略 ——
 * 否则「导出件」会变成绕过氚云源数据与派生口径的后门。
 *
 * <p><b>空白＝不修改</b>：只有填了值的单元格才写回，空白保持库内原值；漏填一列不会静默清空已登记的跟单。
 *
 * <p><b>写入一律复用 {@link ProductionTrackingService#add} 这条唯一的手工跟单写入路径</b>
 * （授权 → 版本校验 → 状态 upsert → 节点 upsert → 跟单历史 → 审计），导回不另写 SQL，
 * 因此导回产生的条目与手工跟进完全同源、同样可追溯；而「只能写本人可见任务」的授权也自动继承。
 */
@Service
public class ProductionTrackingRoundTripService {
    private static final int MAX_BYTES=8*1024*1024;
    /** 与导出上限同量级：一次导回不处理超大批次（导出侧 EXPORT_MAX_ROWS=5000）。 */
    private static final int MAX_ROWS=5000;
    private static final int SQL_BATCH=500;
    /** 导出件表头文字（与前端 EXPORT_COLUMNS 的 label 一一对应）。 */
    static final String COL_ORDER_NO="任务号";
    static final String COL_PRODUCT="产品";
    static final String COL_WORKSHOP="车间";
    static final String COL_FORECAST="车间预计完成";
    static final String COL_DELAY_REASON="延期原因";
    static final String COL_REMARK="跟单备注";
    private static final Pattern DATE_TEXT=Pattern.compile("(\\d{4})[-/.]?(\\d{1,2})[-/.]?(\\d{1,2})");
    /** 表示「本单不走这道按需工序」的写法（与导出、进度表一致）：一律视为「不修改」。 */
    private static final Set<String> NOT_APPLICABLE=Set.of("无","-","--","/","—","不适用","N/A","NA");
    private static final String CLEARED="（清空）";

    private final JdbcTemplate jdbc;
    private final ProductionTrackingService tracking;
    private final ActorResolver actors;
    private final IdempotencyService idem;

    public ProductionTrackingRoundTripService(JdbcTemplate jdbc,ProductionTrackingService tracking,
            ActorResolver actors,@Qualifier("idempotencyService") IdempotencyService idem) {
        this.jdbc=jdbc;this.tracking=tracking;this.actors=actors;this.idem=idem;
    }

    /** 逐项变更（用于预检展示与跟单历史文案）。 */
    public record Change(String field,String before,String after) {}
    /** 预检行：status ∈ WRITE / UNCHANGED / UNMATCHED / FORBIDDEN / DUPLICATE / INVALID。 */
    public record RowPreview(String sheet,int excelRow,String orderNo,String productName,String workshop,
                             String status,String note,List<Change> changes) {}
    public record Preview(String sha256,String planHash,int total,int write,int unchanged,int unmatched,
                          int forbidden,int duplicate,int invalid,List<RowPreview> rows) {}
    public record ApplyResult(String sha256,int written,int unchanged,int skipped,List<RowPreview> rows) {}

    /** 解析出来的一行（只带**可回写列**与匹配用的任务号；日期已归一）。 */
    record ParsedRow(String sheet,int excelRow,String orderNo,String fileProduct,String fileWorkshop,
                     LocalDate fileForecast,String delayReason,String remark,
                     Map<String,LocalDate> stageDates,List<String> problems) {}

    /** 库内当前状态（快照；不可见的行也留着，好把「不存在」与「无权」分开报）。 */
    record OrderState(long id,String orderNo,String productName,String workshop,LocalDate sourceExpected,LocalDate dueDate,
                      long version,LocalDate forecast,String delayReason,String remark,String deliveryResult,
                      String shipmentStatus,boolean stopped,Map<String,LocalDate> stageDates,Set<String> stageDone) {}

    /** 纯函数结果：变更明细 + 结构化目标值（避免用「（清空）」这类展示文案往返解析）。 */
    record Diff(LocalDate forecast,boolean forecastChanged,String delayReason,boolean delayReasonChanged,
                String remark,boolean remarkChanged,List<String> stageCodes,List<Change> changes) {}

    private record MilestoneRow(String code,String status,LocalDate date) {}
    private record Base(long id,String orderNo,String productName,String workshop,LocalDate sourceExpected,LocalDate dueDate) {}

    /** 计划里的一行：预检展示 + 写入所需的一切（版本、目标值、节点、历史文案）。 */
    record Planned(RowPreview preview,long orderId,long version,boolean stateChanged,LocalDate forecast,
                   String delayReason,String remark,List<ProductionTrackingService.Milestone> milestones,String content) {}

    // ---------------------------------------------------------------- 对外

    public Preview preview(MultipartFile file) throws IOException {
        var bytes=bytes(file);
        var planned=plan(parse(bytes),fileName(file));
        int write=0,unchanged=0,unmatched=0,forbidden=0,duplicate=0,invalid=0;
        for(var row:planned){switch(row.preview().status()){
            case "WRITE" -> write++;case "UNCHANGED" -> unchanged++;case "UNMATCHED" -> unmatched++;
            case "FORBIDDEN" -> forbidden++;case "DUPLICATE" -> duplicate++;default -> invalid++;}}
        return new Preview(sha256(bytes),planHash(planned),planned.size(),write,unchanged,unmatched,forbidden,
                duplicate,invalid,planned.stream().map(Planned::preview).toList());
    }

    /**
     * 应用：文件哈希（文件是否变过）与 planHash（工单匹配／库内跟单是否变过）都要与预检一致；
     * 整个批次在同一事务里写入 —— 任何一行失败即整批回滚，不留半批结果。
     */
    public ApplyResult apply(MultipartFile file,String expectedHash,String expectedPlanHash,String key) throws IOException {
        var bytes=bytes(file);
        String hash=sha256(bytes);
        if(!hash.equals(expectedHash))throw new IllegalArgumentException("文件已变化，请重新预检再导入");
        var parsed=parse(bytes);
        String name=fileName(file);
        var actor=actors.current();
        var executed=new ArrayList<Planned>();
        String result=idem.execute(actor,key,"production.tracking.roundtrip.import",Map.of("sha256",hash),()->{
            var planned=plan(parsed,name);
            if(!planHash(planned).equals(expectedPlanHash))
                throw new IllegalArgumentException("工单匹配或库内跟单已变化，请重新预检再导入");
            executed.addAll(planned);
            int written=0;
            for(var row:planned){
                if(!"WRITE".equals(row.preview().status()))continue;
                // ⚠️ 只推节点的行必须传 state=null —— 否则 add() 会把 remark/delay_reason 这些
                // 「本次没改」的字段按 clean(null)→"" 写进去（COALESCE 选中空串），等于清空库内值。
                var state=row.stateChanged()
                        ? new ProductionTrackingService.State(null,row.forecast(),null,row.delayReason(),null,null,null,null,row.remark())
                        : null;
                tracking.add(row.orderId(),new ProductionTrackingService.EntryRequest(row.version(),row.content(),
                        state,row.milestones()),key+"-"+(written+1));
                written++;
            }
            return String.valueOf(written);
        });
        // 幂等回放时业务体没有重新执行，计划要重算一次才能回报明细（结果与首次一致，planHash 已校验过）。
        var rows=executed.isEmpty()?plan(parsed,name):executed;
        int written=result==null?0:Integer.parseInt(result);
        int unchanged=0,skipped=0;
        for(var row:rows){switch(row.preview().status()){
            case "WRITE" -> {}case "UNCHANGED" -> unchanged++;default -> skipped++;}}
        return new ApplyResult(hash,written,unchanged,skipped,rows.stream().map(Planned::preview).toList());
    }

    // ---------------------------------------------------------------- 解析（纯函数，可脱库单测）

    /**
     * 读导出件：逐子表取「任务号 / 产品 / 车间 / 车间预计完成 / 延期原因 / 跟单备注 / 各阶段列」。
     * 列位置不敏感（一律按表头文字定位）；缺「任务号」列的子表直接跳过（导出件的「导出说明」子表即如此被忽略）。
     */
    static List<ParsedRow> parse(byte[] bytes) throws IOException {
        var rows=new ArrayList<ParsedRow>();
        try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            for(Sheet sheet:workbook){
                Row header=sheet.getRow(0);
                if(header==null)continue;
                var columns=headerColumns(header);
                if(!columns.containsKey(headerKey(COL_ORDER_NO)))continue;
                var stages=new LinkedHashMap<String,Integer>();
                for(int c=0;c<header.getLastCellNum();c++){
                    String code=ProductionTrackingPolicy.stageCodeOfLabel(cellText(header,c));
                    if(code!=null)stages.putIfAbsent(code,c);
                }
                for(int index=1;index<=sheet.getLastRowNum();index++){
                    Row row=sheet.getRow(index);
                    if(row==null||blank(row))continue;
                    if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("数据行超过 "+MAX_ROWS+" 行，请分批导回");
                    rows.add(readRow(sheet.getSheetName(),index+1,row,columns,stages));
                }
            }
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("无法读取该 Excel（请用本系统导出的 .xlsx，且不要改表头文字）",e);}
        if(rows.isEmpty())throw new IllegalArgumentException("未找到生产跟单导出表的数据行（表头需含「任务号」列）");
        return rows;
    }

    private static ParsedRow readRow(String sheet,int excelRow,Row row,Map<String,Integer> columns,
            Map<String,Integer> stages){
        var problems=new ArrayList<String>();
        String orderNo=cellText(row,columns.getOrDefault(headerKey(COL_ORDER_NO),-1));
        String product=text(row,columns,COL_PRODUCT);
        String workshop=text(row,columns,COL_WORKSHOP);
        String forecastRaw=text(row,columns,COL_FORECAST);
        String delayReason=text(row,columns,COL_DELAY_REASON);
        String remark=text(row,columns,COL_REMARK);
        if(orderNo==null||orderNo.isBlank())problems.add("缺少任务号");
        LocalDate forecast=forecastRaw==null?null:parseDate(forecastRaw,COL_FORECAST,problems);
        var stageDates=new LinkedHashMap<String,LocalDate>();
        for(var entry:stages.entrySet()){
            String raw=cellText(row,entry.getValue());
            if(raw==null)continue;
            LocalDate date=parseDate(raw,ProductionTrackingPolicy.stageLabel(entry.getKey(),true),problems);
            if(date!=null)stageDates.put(entry.getKey(),date);
        }
        return new ParsedRow(sheet,excelRow,orderNo==null?null:orderNo.trim(),product,workshop,forecast,
                delayReason,remark,stageDates,problems);
    }

    /** 表头归一：去空白（含全角空格/换行）与括号补充说明。 */
    static String headerKey(String text){
        return text==null?null:text.replaceAll("[\\s\\u3000]","").replaceAll("[（(][^）)]*[）)]","");
    }
    private static Map<String,Integer> headerColumns(Row row){
        var found=new HashMap<String,Integer>();
        for(int c=0;c<row.getLastCellNum();c++){
            String key=headerKey(cellText(row,c));
            if(key!=null)found.putIfAbsent(key,c);
        }
        return found;
    }
    private static String text(Row row,Map<String,Integer> columns,String header){
        Integer index=columns.get(headerKey(header));
        return index==null?null:cellText(row,index);
    }
    private static boolean blank(Row row){for(int i=0;i<16;i++)if(cellText(row,i)!=null)return false;return true;}
    private static String cellText(Row row,int index){
        if(index<0)return null;
        Cell cell=row.getCell(index);if(cell==null)return null;
        CellType type=cell.getCellType()==CellType.FORMULA?cell.getCachedFormulaResultType():cell.getCellType();
        String value=switch(type){
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        return value==null||value.isBlank()?null:value.trim();
    }

    /** 日期归一：Excel 日期序列 / 2026-10-08 / 2026/10/8 / 2026.10.8 都认；「无」等＝不适用（不修改）。 */
    static LocalDate parseDate(String raw,String field,List<String> problems){
        String text=raw==null?null:raw.trim();
        if(text==null||text.isEmpty())return null;
        if(NOT_APPLICABLE.contains(text.toUpperCase()))return null;
        Matcher match=DATE_TEXT.matcher(text.split("[\\s]")[0]);
        if(!match.find()||match.start()!=0){
            problems.add("「"+field+"」的日期无法识别："+text);
            return null;
        }
        try{
            return LocalDate.of(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)));
        }catch(Exception e){
            problems.add("「"+field+"」的日期无法识别："+text);
            return null;
        }
    }

    // ---------------------------------------------------------------- 预检计划

    List<Planned> plan(List<ParsedRow> parsed,String fileName){
        var orderNos=parsed.stream().map(ParsedRow::orderNo).filter(Objects::nonNull).toList();
        var snapshot=snapshot(orderNos);
        var visible=tracking.visibleOrderIdsByNos(orderNos);
        var seen=new HashSet<String>();
        var duplicated=new HashSet<String>();
        for(var row:parsed)
            if(row.orderNo()!=null&&!seen.add(row.orderNo()))duplicated.add(row.orderNo());
        var planned=new ArrayList<Planned>();
        for(var row:parsed)
            planned.add(plannedRow(row,snapshot.get(row.orderNo()),visible,duplicated,fileName));
        return planned;
    }

    private Planned plannedRow(ParsedRow row,OrderState current,Map<String,Long> visible,Set<String> duplicated,
            String fileName){
        var diff=current==null?null:diff(row,current);
        String status;
        var note=new ArrayList<String>();
        var changes=new ArrayList<Change>();
        var stageCodes=new ArrayList<String>();
        if(!row.problems().isEmpty()){
            status="INVALID";
            note.addAll(row.problems());
        }else if(duplicated.contains(row.orderNo())){
            status="DUPLICATE";
            note.add("文件里同一任务号出现多次，请合并后再导回");
        }else if(current==null){
            status="UNMATCHED";
            note.add("任务号在本系统找不到对应生产任务（尚未导入氚云生产任务，或任务号被改动）");
        }else if(!visible.containsKey(row.orderNo())){
            status="FORBIDDEN";
            note.add("该任务不在你的可见范围（跟单员只能导回本人负责车间的任务）");
        }else if(current.stopped()){
            status="INVALID";
            note.add("该任务已停止跟单，请先在详情恢复后再导回");
        }else{
            changes.addAll(diff.changes());
            stageCodes.addAll(diff.stageCodes());
            if(changes.isEmpty()){
                status="UNCHANGED";
                note.add("与库内跟单一致，无需写入");
            }else status="WRITE";
            // 任务号是匹配键，产品/车间不一致不影响写入，但值得让人看一眼（只提示、不阻断）。
            if(row.fileProduct()!=null&&!Objects.equals(ProductionTrackingPolicy.normalizeKey(row.fileProduct()),
                    ProductionTrackingPolicy.normalizeKey(current.productName())))
                note.add("提示：文件里的产品与系统不一致（"+row.fileProduct()+"）");
            if(row.fileWorkshop()!=null&&!row.fileWorkshop().isBlank()
                    &&!Objects.equals(ProductionTrackingPolicy.normalizeKey(row.fileWorkshop()),
                            ProductionTrackingPolicy.normalizeKey(current.workshop())))
                note.add("提示：文件里的车间与系统不一致（"+row.fileWorkshop()+"）");
        }
        var milestones=new ArrayList<ProductionTrackingService.Milestone>();
        if("WRITE".equals(status))
            for(String code:stageCodes)
                milestones.add(new ProductionTrackingService.Milestone(code,"DONE",row.stageDates().get(code),null,null,null));
        boolean stateChanged=diff!=null&&(diff.forecastChanged()||diff.delayReasonChanged()||diff.remarkChanged());
        return new Planned(new RowPreview(row.sheet(),row.excelRow(),row.orderNo(),
                current==null?row.fileProduct():current.productName(),
                current==null?row.fileWorkshop():current.workshop(),status,String.join("；",note),changes),
                current==null?0:current.id(),current==null?0:current.version(),stateChanged,
                diff!=null&&diff.forecastChanged()?diff.forecast():(current==null?null:current.forecast()),
                diff!=null&&diff.delayReasonChanged()?diff.delayReason():(current==null?null:current.delayReason()),
                diff!=null&&diff.remarkChanged()?diff.remark():(current==null?null:current.remark()),
                milestones,content(fileName,row,changes));
    }

    /**
     * 纯函数：文件行 vs 库内状态 → 变更明细。
     *
     * <p>「车间预计完成」这一列在导出件里是**交付基准**（＝COALESCE(延期至, 车间给出的预计完成)），
     * 回写时必须分辨两件事：
     * <ul>
     *   <li>填的日期 ≠ 车间给出的预计完成（p.expected_completion_date）⇒ 跟单员另订的<b>延期至</b>；</li>
     *   <li>填的日期 ＝ 车间给出的预计完成 ⇒ 视为<b>取消延期</b>（清空 forecast_finish_date）——
     *       手工跟进的 add() 明确拒绝「延期至与车间预计完成相同」，导回必须自己把它翻译成「取消」，
     *       否则用户想撤销延期时只会拿到一条校验失败。</li>
     * </ul>
     */
    static Diff diff(ParsedRow row,OrderState current){
        var changes=new ArrayList<Change>();
        var stageCodes=new ArrayList<String>();
        LocalDate forecast=current.forecast();
        boolean forecastChanged=false;
        LocalDate baselineSource=current.sourceExpected()!=null?current.sourceExpected():current.dueDate();
        if(row.fileForecast()!=null){
            LocalDate target=row.fileForecast();
            if(Objects.equals(target,baselineSource)){
                if(current.forecast()!=null){
                    forecast=null;forecastChanged=true;
                    changes.add(new Change("延期至",str(current.forecast()),CLEARED));
                }
            }else if(!Objects.equals(target,current.forecast())){
                forecast=target;forecastChanged=true;
                changes.add(new Change("延期至",str(current.forecast()),str(target)));
            }
        }
        String reason=current.delayReason();
        boolean reasonChanged=false;
        if(row.delayReason()!=null&&!row.delayReason().equals(current.delayReason())){
            reason=row.delayReason();reasonChanged=true;
            changes.add(new Change("延期原因",str(current.delayReason()),row.delayReason()));
        }
        String remark=current.remark();
        boolean remarkChanged=false;
        if(row.remark()!=null&&!row.remark().equals(current.remark())){
            remark=row.remark();remarkChanged=true;
            changes.add(new Change("跟单备注",str(current.remark()),row.remark()));
        }
        for(var entry:row.stageDates().entrySet()){
            LocalDate before=current.stageDates().get(entry.getKey());
            if(Objects.equals(before,entry.getValue())&&current.stageDone().contains(entry.getKey()))continue;
            stageCodes.add(entry.getKey());
            changes.add(new Change(ProductionTrackingPolicy.stageLabel(entry.getKey(),true),str(before),
                    "已完成 "+entry.getValue()));
        }
        return new Diff(forecast,forecastChanged,reason,reasonChanged,remark,remarkChanged,stageCodes,changes);
    }

    /** 跟单历史（entries）里显示的内容：写清「这次导回改了哪几项」，逐行可追溯。 */
    private static String content(String fileName,ParsedRow row,List<Change> changes){
        if(changes.isEmpty())return "";
        var parts=new ArrayList<String>();
        for(var change:changes)parts.add(change.field()+"→"+clip(change.after(),60));
        return clip("导入回写（"+clip(fileName,40)+" 第"+row.excelRow()+"行）："+String.join("；",parts),900);
    }

    // ---------------------------------------------------------------- 库内快照

    Map<String,OrderState> snapshot(List<String> orderNos){
        var result=new LinkedHashMap<String,OrderState>();
        var unique=orderNos.stream().filter(no->no!=null&&!no.isBlank()).distinct().toList();
        for(var slice:slices(unique)){
            var base=jdbc.query("SELECT id,work_order_no,product_name,production_line,expected_completion_date,"
                    +"required_completion_date FROM production_orders WHERE work_order_no IN ("+marks(slice.size())+")",
                    (r,n)->base(r),slice.toArray());
            if(base.isEmpty())continue;
            var ids=base.stream().map(Base::id).toList();
            var states=states(ids);
            var milestones=milestones(ids);
            for(var order:base){
                var state=states.get(order.id());
                var rows=milestones.getOrDefault(order.id(),Map.of());
                var dates=new LinkedHashMap<String,LocalDate>();
                var done=new LinkedHashSet<String>();
                for(var entry:rows.entrySet()){
                    if(!Objects.equals(entry.getValue().status(),"DONE"))continue;
                    done.add(entry.getKey());
                    if(entry.getValue().date()!=null)dates.put(entry.getKey(),entry.getValue().date());
                }
                result.put(order.orderNo(),new OrderState(order.id(),order.orderNo(),order.productName(),order.workshop(),
                        order.sourceExpected(),order.dueDate(),state==null?0:state.version(),
                        state==null?null:state.forecast(),state==null?null:state.delayReason(),
                        state==null?null:state.remark(),state==null?null:state.deliveryResult(),
                        state==null?null:state.shipmentStatus(),state!=null&&state.stopped(),dates,done));
            }
        }
        return result;
    }

    private static Base base(ResultSet r)throws SQLException{
        return new Base(r.getLong("id"),r.getString("work_order_no"),r.getString("product_name"),
                r.getString("production_line"),date(r.getDate("expected_completion_date")),
                date(r.getDate("required_completion_date")));
    }
    private record StateRow(long version,LocalDate forecast,String delayReason,String remark,
                            String deliveryResult,String shipmentStatus,boolean stopped) {}
    private Map<Long,StateRow> states(List<Long> ids){
        var out=new HashMap<Long,StateRow>();
        for(var slice:slices(ids))
            // ⚠️ lambda 必须是「块体且无 return」：表达式形态会被解析成 ResultSetExtractor
            // （整表只回调一次且不自动 next()）⇒ 运行期 "Illegal operation on empty result set"。
            jdbc.query("SELECT production_order_id,version,forecast_finish_date,delay_reason,remark,delivery_result,"
                    +"shipment_status,stopped_at FROM production_tracking_state WHERE production_order_id IN ("+marks(slice.size())+")",
                    r->{out.put(r.getLong(1),new StateRow(r.getLong(2),date(r.getDate(3)),r.getString(4),r.getString(5),
                            r.getString(6),r.getString(7),r.getTimestamp(8)!=null));},slice.toArray());
        return out;
    }
    private Map<Long,Map<String,MilestoneRow>> milestones(List<Long> ids){
        var out=new HashMap<Long,Map<String,MilestoneRow>>();
        for(var slice:slices(ids))
            jdbc.query("SELECT production_order_id,stage_code,stage_status,completed_date FROM "
                    +"production_tracking_milestones WHERE production_order_id IN ("+marks(slice.size())+")",
                    r->{out.computeIfAbsent(r.getLong(1),k->new LinkedHashMap<>())
                            .put(r.getString(2),new MilestoneRow(r.getString(2),r.getString(3),date(r.getDate(4))));},
                    slice.toArray());
        return out;
    }

    private static LocalDate date(java.sql.Date value){return value==null?null:value.toLocalDate();}
    private static String str(Object value){return value==null?"":String.valueOf(value);}
    private static String clip(String text,int max){
        String value=text==null?"":text;
        return value.length()<=max?value:value.substring(0,max)+"…";
    }
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private static <T> List<List<T>> slices(List<T> values){
        var out=new ArrayList<List<T>>();
        for(int from=0;from<values.size();from+=SQL_BATCH)
            out.add(values.subList(from,Math.min(values.size(),from+SQL_BATCH)));
        return out;
    }

    // ---------------------------------------------------------------- 指纹 / 入参

    /** 计划指纹：任一行状态、变更内容、节点数或库内版本变了，都要重新预检（与进度表导入同一套路）。 */
    static String planHash(List<Planned> planned){
        var fingerprint=new StringBuilder();
        for(var row:planned){
            fingerprint.append(row.preview().orderNo()).append('\t').append(row.preview().status()).append('\t')
                    .append(row.version()).append('\t').append(row.milestones().size()).append('\t');
            for(var change:row.preview().changes())
                fingerprint.append(change.field()).append('=').append(change.after()).append(',');
            fingerprint.append('\n');
        }
        return sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        if(file==null||file.isEmpty())throw new IllegalArgumentException("请选择 .xlsx 导出件");
        if(!fileName(file).toLowerCase().endsWith(".xlsx"))
            throw new IllegalArgumentException("仅支持 .xlsx 导出件（网页导出的 .html 请改用 Excel 导出后再导回）");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("表格不能超过 8 MB");
        return file.getBytes();
    }
    private static String fileName(MultipartFile file){
        String name=file.getOriginalFilename();
        return name==null||name.isBlank()?"导出件.xlsx":name;
    }
    private static String sha256(byte[] bytes){
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("SHA-256 不可用",e);}
    }
}
