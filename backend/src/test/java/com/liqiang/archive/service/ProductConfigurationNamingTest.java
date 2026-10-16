package com.liqiang.archive.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.liqiang.archive.service.ProductConfigurationNaming.*;
class ProductConfigurationNamingTest {
    private final Labels labels=new Labels("全自动气调机","BP-1",Map.of("1","灌装","2","纸箱"),Map.of("1","OPT-1","2","OPT-2"));
    private Map<String,Object> machine(){return new HashMap<>(Map.of("boxShape","RECT","boxLengthMm","200.00","boxWidthMm","150","boxHeight","40","moldArrangement","1出2","boxMaterial","PP","voltage","单相220V","plug","国标","brand","利强品牌"));}
    @Test void normalizesSizesAndSortsOptionsWithoutUsingPackagingInName(){
        var d=new Definition("MACHINE",MACHINE_REQUIRED,List.of(new Option(1,"FILLING",true,""),new Option(2,"PACKAGING",false,"")),true);
        var c=machine();c.put("optionIds",List.of(2,1,1));c.put("boxDiameterMm","999");
        var r=generate(d,labels,c);assertEquals("COMPLETE",r.completionState());
        assertTrue(r.namePreview().contains("方盒200×150mm"));assertTrue(r.namePreview().contains("灌装"));assertFalse(r.namePreview().contains("纸箱"));
        assertTrue(r.specificationPreview().contains("纸箱"));assertEquals(List.of(1L,2L),r.normalizedConfiguration().get("optionIds"));assertFalse(r.normalizedConfiguration().containsKey("boxDiameterMm"));
    }
    @Test void rejectsReversedSizeUnknownOptionsAndExclusiveConflicts(){
        var d=new Definition("MACHINE",MACHINE_REQUIRED,List.of(new Option(1,"FILLING",true,"灌装"),new Option(2,"FILLING",true,"灌装")),true);
        var c=machine();c.put("boxLengthMm","100");c.put("optionIds",List.of(1,2,999));
        var r=generate(d,labels,c);assertEquals("INCOMPLETE",r.completionState());assertTrue(r.fieldErrors().containsKey("boxLengthMm"));assertTrue(r.fieldErrors().containsKey("optionIds"));
    }
    @Test void distinguishesConfirmationFromMissingFields(){
        var c=machine();c.put("extraMold",true);
        var d=new Definition("MACHINE",MACHINE_REQUIRED,List.of(),true);
        assertEquals("NEEDS_CONFIRMATION",generate(d,labels,c).completionState());
        c.remove("brand");assertEquals("INCOMPLETE",generate(d,labels,c).completionState());
    }
    @Test void partIgnoresStaleMachineFields(){
        var c=machine();c.put("specificName","送料皮带");c.put("partSpecification","20×500");
        var r=generate(new Definition("PART",List.of("specificName","partSpecification"),List.of(),false),labels,c);
        assertEquals("COMPLETE",r.completionState());assertEquals("送料皮带｜20×500",r.namePreview());assertFalse(r.specificationPreview().contains("220V"));
    }
    @Test void frozenLabelsDetermineNameRatherThanClientText(){
        var c=machine();c.put("productName","客户伪造名称");
        var r=generate(new Definition("MACHINE",MACHINE_REQUIRED,List.of(),true),labels,c);
        assertTrue(r.namePreview().startsWith("全自动气调机"));assertEquals("全自动气调机",r.normalizedConfiguration().get("productName"));
    }
}
