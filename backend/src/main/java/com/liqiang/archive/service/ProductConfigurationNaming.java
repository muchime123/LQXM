package com.liqiang.archive.service;

import java.math.BigDecimal;
import java.util.*;

/** Pure, bounded naming engine. Labels are frozen when a rule is published. */
public final class ProductConfigurationNaming {
    private ProductConfigurationNaming() {}
    public static final Set<String> FIELDS = Set.of("boxShape", "boxHeight", "moldArrangement", "boxMaterial",
            "voltage", "plug", "brand", "specificName", "compatibleModel", "moldType", "drawingVersion", "partSpecification");
    public static final List<String> MACHINE_REQUIRED = List.of("boxShape", "boxHeight", "moldArrangement", "boxMaterial", "voltage", "plug", "brand");
    public record Option(long optionId, String groupCode, boolean technical, String exclusiveGroup) {}
    public record Definition(String productType, List<String> requiredFields, List<Option> options, boolean includeElectrical) {}
    public record Labels(String productName, String productCode, Map<String,String> optionNames, Map<String,String> optionCodes) {}
    public record Result(String namePreview, String specificationPreview, String completionState,
                         Map<String,String> fieldErrors, List<String> confirmationItems, Map<String,Object> normalizedConfiguration) {}

    public static String str(Map<String,Object> c, String key) { return Objects.toString(c.get(key), "").trim(); }
    private static String mm(Map<String,Object> c, String key, Map<String,String> errors) {
        String value=str(c,key);
        try {
            if (!value.matches("\\d+(?:\\.\\d{1,2})?")) throw new IllegalArgumentException();
            BigDecimal n=new BigDecimal(value);
            if(n.signum()<=0) throw new IllegalArgumentException();
            return n.stripTrailingZeros().toPlainString();
        } catch(Exception e) { errors.put(key,"请填写正数毫米值，最多2位小数"); return ""; }
    }
    public static Result generate(Definition rule, Labels labels, Map<String,Object> input) {
        Map<String,Object> c=new LinkedHashMap<>();
        for(String key: Set.of("boxShape","boxLengthMm","boxWidthMm","boxDiameterMm","boxShapeNote","boxSizeNote",
                "boxHeight","moldArrangement","boxMaterial","voltage","plug","brand","specificName","compatibleModel",
                "moldType","drawingVersion","partSpecification","configNote")) c.put(key,str(input,key));
        Map<String,String> errors=new LinkedHashMap<>();
        if(!"MACHINE".equals(rule.productType())) for(String key:List.of("voltage","plug","brand","boxMaterial")) c.put(key,"");
        if("PART".equals(rule.productType())) for(String key:List.of("boxShape","boxHeight","moldArrangement","drawingVersion","boxLengthMm","boxWidthMm","boxDiameterMm","boxShapeNote","boxSizeNote")) c.put(key,"");
        for(String f:rule.requiredFields()) if(str(c,f).isEmpty()) errors.put(f,"请填写此项");
        for(var entry:c.entrySet()) if(entry.getValue().toString().length()>500) errors.put(entry.getKey(),"字段不能超过500字");
        List<String> confirmations=new ArrayList<>();
        String size="",shape=str(c,"boxShape");
        if(!"PART".equals(rule.productType())) {
            if("RECT".equals(shape)) {
                String l=mm(c,"boxLengthMm",errors),w=mm(c,"boxWidthMm",errors);
                c.put("boxLengthMm",l);c.put("boxWidthMm",w);
                if(!l.isEmpty()&&!w.isEmpty()) {
                    if(new BigDecimal(l).compareTo(new BigDecimal(w))<0) errors.put("boxLengthMm","盒子长应为较大的一边");
                    size="方盒"+l+"×"+w+"mm";c.put("boxSize",l+"x"+w);
                }
                c.remove("boxDiameterMm");c.remove("boxShapeNote");c.remove("boxSizeNote");
            } else if("ROUND".equals(shape)) {
                String d=mm(c,"boxDiameterMm",errors);c.put("boxDiameterMm",d);
                if(!d.isEmpty()) {size="圆盒Φ"+d+"mm";c.put("boxSize",d);}
                c.remove("boxLengthMm");c.remove("boxWidthMm");c.remove("boxShapeNote");c.remove("boxSizeNote");
            } else if("OTHER".equals(shape)) {
                if(str(c,"boxShapeNote").isEmpty()) errors.put("boxShapeNote","请填写异形说明");
                if(str(c,"boxSizeNote").isEmpty()) errors.put("boxSizeNote","请填写尺寸描述");
                size=String.join(" ",str(c,"boxShapeNote"),str(c,"boxSizeNote")).trim();
                c.remove("boxLengthMm");c.remove("boxWidthMm");c.remove("boxDiameterMm");
                confirmations.add("异形结构需核对图纸与实际产出");
            } else errors.put("boxShape","请选择方形、圆形或异形");
            if(!str(c,"boxHeight").isEmpty()) c.put("boxHeight",mm(c,"boxHeight",errors));
        }
        if(!str(c,"voltage").isEmpty()&&!Set.of("单相110V","单相220V","三相220V","三相380V").contains(str(c,"voltage"))) errors.put("voltage","请选择有效电压");
        if(!str(c,"plug").isEmpty()&&!Set.of("国标","欧标","英标","美标","其他").contains(str(c,"plug"))) errors.put("plug","请选择有效插头");
        if(!str(c,"brand").isEmpty()&&!Set.of("利强品牌","中性","其他").contains(str(c,"brand"))) errors.put("brand","请选择有效品牌");
        var allowed=new HashMap<Long,Option>();rule.options().forEach(o->allowed.put(o.optionId(),o));
        List<Long> ids=new ArrayList<>();
        Object selected=input.get("optionIds");
        if(selected instanceof List<?> list) {
            if(list.size()>100) errors.put("optionIds","最多选择100项");
            else for(Object id:list) {
                if(!(id instanceof Number n)||!allowed.containsKey(n.longValue())) errors.put("optionIds","存在不适用于此产品的配置项");
                else if(!ids.contains(n.longValue()))ids.add(n.longValue());
            }
        } else if(selected!=null) errors.put("optionIds","配置项参数格式错误");
        ids.sort(Comparator.comparing(id->labels.optionCodes().getOrDefault(id.toString(),id.toString())));
        var exclusive=new HashSet<String>();
        for(long id:ids) {
            String group=allowed.get(id).exclusiveGroup();
            if(group!=null&&!group.isBlank()&&!exclusive.add(group)) errors.put("optionIds","同一互斥组不能重复选择："+group);
            if("UNCLASSIFIED".equals(allowed.get(id).groupCode())) confirmations.add("配置项尚未分类："+labels.optionNames().get(id+""));
        }
        var optionNames=ids.stream().map(id->labels.optionNames().get(id+"")).toList();
        c.put("optionIds",ids);c.put("optionCodes",ids.stream().map(id->labels.optionCodes().get(id+"")).toList());
        c.put("optionalConfigs",optionNames);c.put("productName",labels.productName());
        if(input.get("extraMold") instanceof Boolean b)c.put("extraMold",b);
        if(Boolean.TRUE.equals(input.get("extraMold"))) confirmations.add("额外模具需单独确认产出与规格");
        if(!str(c,"configNote").isEmpty()) confirmations.add("其他配置说明需核对是否影响实物");
        List<String> name=new ArrayList<>(),spec=new ArrayList<>();
        name.add("PART".equals(rule.productType())?fallback(str(c,"specificName"),"配件名称未填写"):labels.productName());
        if("MOLD".equals(rule.productType())) {name.clear();name.add(fallback(str(c,"compatibleModel"),"适配机型未填写"));name.add(fallback(str(c,"moldType"),"模具类型未填写"));}
        if("PART".equals(rule.productType())) {
            name.add(fallback(str(c,"partSpecification"),"规格未填写"));
            if(!str(c,"compatibleModel").isEmpty())name.add(str(c,"compatibleModel"));
        } else {
            name.add(fallback(size,"尺寸未填写"));name.add(fallback(str(c,"moldArrangement"),"排列未填写"));
            if(!str(c,"boxHeight").isEmpty())spec.add("盒高"+str(c,"boxHeight")+"mm");
            if(!str(c,"boxMaterial").isEmpty())spec.add("盒材质"+str(c,"boxMaterial"));
        }
        var technical=ids.stream().filter(id->allowed.get(id).technical()).map(id->labels.optionNames().get(id+"")).toList();
        if(!technical.isEmpty())name.add(String.join("+",technical));
        String electrical=String.join("/",List.of(str(c,"voltage"),str(c,"plug"),str(c,"brand")).stream().filter(v->!v.isEmpty()).toList());
        if(!electrical.isEmpty()){if(rule.includeElectrical()&&"MACHINE".equals(rule.productType()))name.add(electrical);else spec.add(electrical);}
        if(!str(c,"drawingVersion").isEmpty()) {if("MOLD".equals(rule.productType()))name.add("图纸"+str(c,"drawingVersion"));else spec.add("图纸"+str(c,"drawingVersion"));}
        if(!optionNames.isEmpty())spec.add("全部选配："+String.join("、",optionNames));
        if(!str(c,"configNote").isEmpty())spec.add("说明："+str(c,"configNote"));
        String display=String.join("｜",name),full=display+(spec.isEmpty()?"":"；"+String.join("；",spec));
        if(display.length()>500)errors.put("namePreview","名称过长，请简化配置项名称");
        if(full.length()>1000)errors.put("specificationPreview","规格超过1000字，请精简说明");
        return new Result(display,full,errors.isEmpty()?(confirmations.isEmpty()?"COMPLETE":"NEEDS_CONFIRMATION"):"INCOMPLETE",errors,confirmations,c);
    }
    private static String fallback(String value,String empty){return value.isEmpty()?empty:value;}
}
