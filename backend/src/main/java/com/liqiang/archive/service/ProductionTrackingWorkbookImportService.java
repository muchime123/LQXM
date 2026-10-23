package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Preview and import the workshop workbook without changing H3 source orders or existing follow-up records. */
@Service
public class ProductionTrackingWorkbookImportService {
    private static final int MAX_BYTES=8*1024*1024;
    private static final int MAX_ROWS=2000;
    private static final Pattern DIGITS=Pattern.compile("(\\d+)$");
    static final String TRACKED_ORDERS_SQL="SELECT production_order_id FROM production_tracking_state WHERE version>0 "
            +"UNION SELECT production_order_id FROM production_tracking_entries";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProductionTrackingService tracking;
    private final ProductionTrackingWorkbookReviewService review;
    private final ActorResolver actors;
    private final IdempotencyService idem;

    public ProductionTrackingWorkbookImportService(JdbcTemplate jdbc,ObjectMapper json,ProductionTrackingService tracking,
            ProductionTrackingWorkbookReviewService review,
            ActorResolver actors,@Qualifier("idempotencyService") IdempotencyService idem) {
        this.jdbc=jdbc;this.json=json;this.tracking=tracking;this.review=review;this.actors=actors;this.idem=idem;
    }

    public record RowPreview(String sheet,int rowNumber,String sourceOrderNo,String productName,String plannedStartDate,
                             String requiredCompletionDate,String quantity,String status,Long productionOrderId,
                             String workOrderNo,String reason) {}
    public record Preview(String sha256,String planHash,int total,int ready,int update,int existing,int review,int archived,int pending,boolean alreadyUploaded,
                          List<RowPreview> rows) {}
    public record ApplyResult(String sha256,int imported,int updated,int existing,int review,int archived,int pending,boolean alreadyUploaded) {}
    private record Parsed(String sheet,int rowNumber,String sourceOrderNo,String productName,String plannedStartDate,
                          String requiredCompletionDate,String quantity,String payload) {}
    private record Order(long id,String workOrderNo,String productName,String workshop,String plannedStartDate,
                         String dueDate,BigDecimal quantity) {}
    private record Planned(Parsed source,String status,Long targetId,String targetNo,String reason,String differenceSignature) {}

    public Preview preview(MultipartFile file) throws IOException {
        var bytes=bytes(file);
        String hash=sha256(bytes);
        var planned=plan(parse(bytes,file.getOriginalFilename(),hash));
        int ready=0,update=0,existing=0,reviewCount=0,archived=0,pending=0;
        var rows=new ArrayList<RowPreview>();
        for(var item:planned){
            switch(item.status()){case "READY" -> ready++;case "UPDATE" -> update++;case "EXISTING" -> existing++;
                case "REVIEW" -> reviewCount++;case "ARCHIVED" -> archived++;default -> pending++;}
            var source=item.source();
            rows.add(new RowPreview(source.sheet(),source.rowNumber(),source.sourceOrderNo(),source.productName(),
                    source.plannedStartDate(),source.requiredCompletionDate(),source.quantity(),item.status(),
                    item.targetId(),item.targetNo(),item.reason()));
        }
        return new Preview(hash,planHash(planned),planned.size(),ready,update,existing,reviewCount,archived,pending,alreadyUploaded(hash),rows);
    }

    public ApplyResult apply(MultipartFile file,String expectedHash,String expectedPlanHash,String key) throws IOException {
        var bytes=bytes(file);
        String hash=sha256(bytes);
        if(!hash.equals(expectedHash))throw new IllegalArgumentException("文件已变化，请重新预览再导入");
        if(alreadyUploaded(hash))return new ApplyResult(hash,0,0,0,0,0,0,true);
        var parsed=parse(bytes,file.getOriginalFilename(),hash);
        var planned=plan(parsed);
        if(!planHash(planned).equals(expectedPlanHash))
            throw new IllegalArgumentException("工单匹配或跟单记录已变化，请重新预览再导入");
        var actor=actors.current();
        String result=idem.execute(actor,key,"production.tracking.workbook.import",Map.of("sha256",hash),()->{
            if(alreadyUploaded(hash))return "already";
            var currentPlan=plan(parsed);
            if(!planHash(currentPlan).equals(expectedPlanHash))
                throw new IllegalArgumentException("工单匹配或跟单记录已变化，请重新预览再导入");
            for(String sheet:currentPlan.stream().map(item->item.source().sheet()).distinct().toList())
                jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='SUPERSEDED',"
                        +"superseded_at=NOW(),superseded_by_hash=?,match_note='已有较新版本表格' "
                        +"WHERE sheet_name=? AND source_hash<>? AND match_status IN ('PENDING','REVIEW')",
                        hash,sheet,hash);
            for(var item:currentPlan){
                var source=item.source();
                Long target=item.targetId();
                jdbc.update("INSERT INTO production_tracking_workbook_rows(source_hash,sheet_name,sheet_row,"
                                +"source_order_no,product_name,required_completion_date,payload_json,"
                                +"match_status,production_order_id,match_note,imported_at) "
                                +"VALUES(?,?,?,?,?,?,?,?,?,?,CASE WHEN ?='READY' THEN NOW() ELSE NULL END)",
                        hash,source.sheet(),source.rowNumber(),source.sourceOrderNo(),source.productName(),
                        source.requiredCompletionDate(),source.payload(),
                        ("READY".equals(item.status())||"UPDATE".equals(item.status()))?"PENDING":item.status(),
                         "PENDING".equals(item.status())?null:target,item.reason(),item.status());
                if("READY".equals(item.status())){
                    long rowId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
                    tracking.importWorkbookPayload(target,source.payload(),rowId);
                    jdbc.update("UPDATE production_tracking_workbook_rows SET match_note='自动匹配导入' WHERE id=?",rowId);
                }else if("UPDATE".equals(item.status())){
                    long rowId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
                    review.applyWorkbookOnly(target,source.payload(),rowId);
                }
            }
            return "applied";
        });
        if("already".equals(result))return new ApplyResult(hash,0,0,0,0,0,0,true);
        int ready=0,update=0,existing=0,reviewCount=0,archived=0,pending=0;
        for(var item:planned){switch(item.status()){
            case "READY" -> ready++;case "UPDATE" -> update++;case "EXISTING" -> existing++;
            case "REVIEW" -> reviewCount++;case "ARCHIVED" -> archived++;default -> pending++;}}
        return new ApplyResult(hash,ready,update,existing,reviewCount,archived,pending,false);
    }

    private List<Planned> plan(List<Parsed> sourceRows) {
        var orders=jdbc.query("SELECT id,work_order_no,product_name,production_line,application_date,"
                +"required_completion_date,required_quantity FROM production_orders WHERE external_source='H3YUN'",
                (r,n)->new Order(r.getLong("id"),r.getString("work_order_no"),r.getString("product_name"),
                        r.getString("production_line"),dateString(r.getDate("application_date")),
                        dateString(r.getDate("required_completion_date")),r.getBigDecimal("required_quantity")));
        Set<Long> tracked=new HashSet<>(jdbc.queryForList(TRACKED_ORDERS_SQL,Long.class));
        Set<Long> manuallyTracked=new HashSet<>(jdbc.queryForList("SELECT DISTINCT production_order_id "
                +"FROM production_tracking_entries WHERE created_by_employee_id>0",Long.class));
        // 归档规则：按归一化产品名记住「本来就不对应氚云工单」的表格行（模具/配件/片膜等），
        // 命中即直接归为 ARCHIVED，不再进入待处理队列，避免每次导入反复堆积同样的行。
        var archiveRules=new HashMap<String,String>();
        for(var rule:jdbc.queryForList("SELECT product_key,reason FROM production_tracking_workbook_archive_rules "
                +"WHERE revoked_at IS NULL"))
            archiveRules.put(String.valueOf(rule.get("product_key")),String.valueOf(rule.get("reason")));
        var initial=new ArrayList<Planned>();
        var counts=new HashMap<Long,Integer>();
        for(var source:sourceRows){
            var candidates=new ArrayList<Order>();
            for(var order:orders){
                if(!ProductionTrackingPolicy.normalizeKey(source.productName()).equals(ProductionTrackingPolicy.normalizeKey(order.productName()))
                        ||!ProductionTrackingPolicy.normalizeKey(source.sheet()).equals(ProductionTrackingPolicy.normalizeKey(order.workshop()))
                        ||!same(source.requiredCompletionDate(),order.dueDate()))continue;
                if(source.sourceOrderNo()!=null){
                    var match=DIGITS.matcher(order.workOrderNo());
                    if(!match.find()||!source.sourceOrderNo().equals(String.valueOf(Integer.parseInt(
                            match.group(1).substring(Math.max(0,match.group(1).length()-4))))))continue;
                }else if(!same(source.plannedStartDate(),order.plannedStartDate())
                        ||source.quantity()==null||order.quantity()==null
                        ||new BigDecimal(source.quantity()).compareTo(order.quantity())!=0)continue;
                candidates.add(order);
            }
            if(candidates.size()==1){
                var order=candidates.get(0);
                boolean hasFollowup=tracked.contains(order.id());
                var differences=hasFollowup?review.differences(order.id(),source.payload()):List.<ProductionTrackingWorkbookReviewService.Difference>of();
                int diffCount=differences.size();
                String status=hasFollowup?(diffCount>0?(manuallyTracked.contains(order.id())?"REVIEW":"UPDATE")
                        :"EXISTING"):"READY";
                initial.add(new Planned(source,status,order.id(),order.workOrderNo(),
                        diffCount>0?("REVIEW".equals(status)?"已有人工跟进，"+diffCount+" 项变化待主管确认"
                                :"仅有表格导入记录，"+diffCount+" 项变化可随本次上传更新")
                                :hasFollowup?"已有跟单，内容相同":"字段唯一一致",
                        differences.toString()));
                counts.merge(order.id(),1,Integer::sum);
            }else{
                String archiveReason=archiveRules.get(ProductionTrackingPolicy.normalizeKey(source.productName()));
                if(archiveReason!=null)
                    initial.add(new Planned(source,"ARCHIVED",null,null,"已归档："+archiveReason,""));
                else
                    initial.add(new Planned(source,"PENDING",null,null,
                            source.sourceOrderNo()==null?"缺少编号，未找到唯一的五字段匹配":"编号、产品、车间或交期未唯一匹配",""));
            }
        }
        var result=new ArrayList<Planned>();
        for(var item:initial){
            if(item.targetId()!=null&&counts.get(item.targetId())>1)
                result.add(new Planned(item.source(),"PENDING",null,null,"多行指向同一工单，请核对",""));
            else result.add(item);
        }
        return result;
    }

    private List<Parsed> parse(byte[] bytes,String fileName,String hash) throws IOException {
        var rows=new ArrayList<Parsed>();
        try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            for(Sheet sheet:workbook){
                String name=sheet.getSheetName();
                boolean sealer=name.contains("封口机");
                if(!sealer&&!name.contains("全自动气调机")&&!name.contains("半自动气调机"))continue;
                Row header=sheet.getRow(1);
                if(header==null||!"订单号".equals(cellText(header,0))||!"产品名称".equals(cellText(header,1)))
                    throw new IllegalArgumentException(name+"的表头不符合生产跟单进度表格式");
                // ⚠️ 列位一律按表头文字定位：模板增删/移动列（如 2026-10 封口机在「模具生产」后插入
                // 「氧化/喷特氟龙」、并在 H 列插入「预计生产完成日期」）不会再静默读错列，缺列直接报错。
                // 【同步范围 2026-10-10 LQ 定】只收三样：延期至（条件见 importWorkbookPayload）、延期原因、节点完成日期。
                // 计划开工仍要读——无编号行的匹配回退要用（Parsed 字段），但不再写入本地跟单；
                // 交付结果/发货状态/业务员/备注连读都不读，模板缺这些列也不再报错。
                var columns=headerColumns(header);
                int plannedStartColumn=column(columns,"计划开工",name);
                int requiredCompletionColumn=column(columns,"订单要求生产完成日期",name);
                int delayReasonColumn=column(columns,"延期原因",name);
                // 进度表「预计生产完成日期（延期时填写）」列 = 跟单员**另订的延期日**：车间在工单上给的
                // 「预计生产完成日期」实现不了时，跟单员与车间/业务员协商得到的新日期（表头括号已注明
                // 「延期时填写」，列名归一后按「预计生产完成日期」定位）。语义等同手工跟进的「延期至」
                // ⇒ 落 production_tracking_state.forecast_finish_date。
                // ⚠️ 它**不是**工单源数据 production_orders.expected_completion_date（车间填写的车间预计完成），勿混。
                Integer forecastFinishColumn=optionalColumn(columns,"预计生产完成日期");
                var stageColumns=stageColumns(sheet.getRow(2));
                for(String code:ProductionTrackingPolicy.requiredStages(name))
                    if(!stageColumns.containsKey(code))
                        throw new IllegalArgumentException(name+"的表头缺少「"
                                +ProductionTrackingPolicy.stageLabel(code,sealer)+"」阶段列，请核对模板");
                for(int rowNumber=4;rowNumber<=sheet.getLastRowNum()+1;rowNumber++){
                    Row row=sheet.getRow(rowNumber-1);
                    if(row==null||blank(row))continue;
                    if(rows.size()>=MAX_ROWS)throw new IllegalArgumentException("表格行数超过 2000 行");
                    String number=cellText(row,0);
                    String product=cellText(row,1);
                    if(product==null)throw new IllegalArgumentException(name+"第 "+rowNumber+" 行缺少产品名称");
                    var payload=new LinkedHashMap<String,Object>();
                    payload.put("sourceFile",fileName);payload.put("sourceHash",hash);
                    payload.put("sheet",name);payload.put("rowNumber",rowNumber);
                    payload.put("sourceOrderNo",number);payload.put("productName",product);
                    payload.put("model",cellText(row,2));payload.put("dimension",cellText(row,3));
                    payload.put("quantity",cellText(row,4));
                    // 计划开工只进 Parsed（匹配回退用），不进 payload —— 不再写入本地跟单。
                    payload.put("requiredCompletionDate",cellDate(row,requiredCompletionColumn));
                    payload.put("forecastFinishDate",forecastFinishColumn==null?null:cellDate(row,forecastFinishColumn));
                    payload.put("delayReason",cellText(row,delayReasonColumn));
                    var milestones=new ArrayList<Map<String,Object>>();
                    // 阶段名写在表头第 2 行；「按需工序」（氧化/喷特氟龙）填「无」或留空时取到 null，
                    // 落库时会跳过 ⇒ 该单不适用，不计入节点总数、也不挡跟单完成。
                    for(String code:ProductionTrackingPolicy.stageCodes(name)){
                        Integer stageColumn=stageColumns.get(code);
                        if(stageColumn==null)continue;
                        var stage=new LinkedHashMap<String,Object>();
                        stage.put("code",code);stage.put("completedDate",cellDate(row,stageColumn));
                        milestones.add(stage);
                    }
                    payload.put("milestones",milestones);
                    String serialized;
                    try{serialized=json.writeValueAsString(payload);}
                    catch(JsonProcessingException e){throw new IllegalArgumentException("表格行无法序列化",e);}
                    rows.add(new Parsed(name,rowNumber,number,product,(String)payload.get("plannedStartDate"),
                            (String)payload.get("requiredCompletionDate"),(String)payload.get("quantity"),serialized));
                }
            }
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("无法读取生产跟单进度表",e);}
        if(rows.isEmpty())throw new IllegalArgumentException("未找到生产跟单进度表数据");
        return rows;
    }

    /** 表头归一：去掉空白（含全角空格/换行）与括号补充说明，如「预计生产完成日期（要求时间达不到时填写）」。 */
    private static String headerKey(String text){
        return text==null?null:text.replaceAll("[\\s\\u3000]","").replaceAll("[（(][^）)]*[）)]","");
    }
    private static Map<String,Integer> headerColumns(Row row){
        var found=new HashMap<String,Integer>();
        if(row==null)return found;
        for(int c=0;c<row.getLastCellNum();c++){
            String key=headerKey(cellText(row,c));
            if(key!=null)found.putIfAbsent(key,c);
        }
        return found;
    }
    /** 阶段列＝表头第 2 行里能识别出阶段名的那些列，按列序返回「节点码 → 列号」。 */
    private static LinkedHashMap<String,Integer> stageColumns(Row stageRow){
        var found=new LinkedHashMap<String,Integer>();
        if(stageRow==null)return found;
        for(int c=0;c<stageRow.getLastCellNum();c++){
            String code=ProductionTrackingPolicy.stageCodeOfLabel(cellText(stageRow,c));
            if(code!=null)found.putIfAbsent(code,c);
        }
        return found;
    }
    private static int column(Map<String,Integer> columns,String header,String sheetName){
        Integer index=columns.get(headerKey(header));
        if(index==null)throw new IllegalArgumentException(sheetName+"的表头缺少「"+header+"」列，请核对生产跟单进度表模板");
        return index;
    }
    private static Integer optionalColumn(Map<String,Integer> columns,String header){return columns.get(headerKey(header));}
    private static boolean blank(Row row){for(int i=0;i<12;i++)if(cellText(row,i)!=null)return false;return true;}
    private static String cellText(Row row,int index){
        Cell cell=row.getCell(index);if(cell==null)return null;
        CellType type=cell.getCellType()==CellType.FORMULA?cell.getCachedFormulaResultType():cell.getCellType();
        String value=switch(type){
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        return value==null||value.isBlank()?null:value.trim();
    }
    private static String cellDate(Row row,int index){
        Cell cell=row.getCell(index);if(cell==null)return null;
        CellType type=cell.getCellType()==CellType.FORMULA?cell.getCachedFormulaResultType():cell.getCellType();
        if(type==CellType.NUMERIC){double value=cell.getNumericCellValue();
            return value>40000&&value<100000?DateUtil.getLocalDateTime(value).toLocalDate().toString():null;}
        String value=cellText(row,index);
        if(value==null)return null;
        try{return LocalDate.parse(value.replace('/','-')).toString();}catch(Exception ignored){return null;}
    }
    private static boolean same(String a,String b){return a!=null&&a.equals(b);}
    private static String dateString(java.sql.Date date){return date==null?null:date.toLocalDate().toString();}
    private boolean alreadyUploaded(String hash){Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM production_tracking_workbook_rows WHERE source_hash=?",Integer.class,hash);return count!=null&&count>0;}
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("SHA-256 不可用",e);}}
    private static String planHash(List<Planned> planned) {
        StringBuilder fingerprint=new StringBuilder();
        for(var item:planned)fingerprint.append(item.source().sheet()).append('\t')
                .append(item.source().rowNumber()).append('\t').append(item.status()).append('\t')
                .append(item.targetId()).append('\t').append(item.reason()).append('\t')
                .append(item.differenceSignature()).append('\n');
        return sha256(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static byte[] bytes(MultipartFile file) throws IOException {
        if(file==null||file.isEmpty())throw new IllegalArgumentException("请选择 .xlsx 表格");
        String name=file.getOriginalFilename();
        if(name==null||!name.toLowerCase().endsWith(".xlsx"))throw new IllegalArgumentException("仅支持 .xlsx 表格");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("表格不能超过 8 MB");
        return file.getBytes();
    }
}
