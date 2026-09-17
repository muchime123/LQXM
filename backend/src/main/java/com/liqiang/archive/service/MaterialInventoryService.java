package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
public class MaterialInventoryService {
    private final JdbcTemplate jdbc;private final CurrentUserService users;private final AuditLogService audit;
    public record MaterialRequest(String materialCode,String materialName,String specification,String defaultUnit,String materialCategory,String description,boolean active){}
    public record BalanceRequest(Long materialId,Long warehouseId,BigDecimal newQuantity,String reason){}
    public List<Map<String,Object>> materials(){return jdbc.queryForList("SELECT id,material_code materialCode,material_name materialName,specification,default_unit defaultUnit,material_category materialCategory,description,active,created_at createdAt,updated_at updatedAt FROM material_items ORDER BY active DESC,material_category,material_name,id");}
    public List<Map<String,Object>> balances(){return jdbc.queryForList("""
      SELECT b.id,b.warehouse_id warehouseId,w.warehouse_name warehouseName,b.material_id materialId,
        COALESCE(m.material_code,'未建档') materialCode,b.material_name materialName,NULLIF(b.specification_key,'') specification,
        b.unit,b.quantity,b.updated_at updatedAt FROM material_inventory_balances b JOIN warehouses w ON w.id=b.warehouse_id
        LEFT JOIN material_items m ON m.id=b.material_id ORDER BY w.id,b.material_name,b.specification_key
      """);}
    public List<Map<String,Object>> transactions(){return jdbc.queryForList("""
      SELECT t.id,t.transaction_no transactionNo,t.warehouse_id warehouseId,w.warehouse_name warehouseName,t.material_id materialId,
        t.material_name_snapshot materialName,t.specification_snapshot specification,t.unit,t.transaction_type transactionType,
        t.quantity_change quantityChange,t.balance_after balanceAfter,t.source_type sourceType,t.operator_name operatorName,
        t.occurred_at occurredAt,t.reason FROM material_inventory_transactions t JOIN warehouses w ON w.id=t.warehouse_id
        ORDER BY t.occurred_at DESC,t.id DESC LIMIT 500
      """);}
    @Transactional public long saveMaterial(Long id,MaterialRequest request){String code=required(request.materialCode(),"材料编码").toUpperCase(Locale.ROOT);if(!code.matches("[A-Z0-9_-]{2,50}"))throw new IllegalArgumentException("材料编码只能包含字母、数字、下划线和横线");String name=required(request.materialName(),"材料名称"),unit=required(request.defaultUnit(),"默认单位");if(id==null){jdbc.update("INSERT INTO material_items(material_code,material_name,specification,default_unit,material_category,description,active) VALUES(?,?,?,?,?,?,?)",code,name,clean(request.specification()),unit,clean(request.materialCategory()),clean(request.description()),request.active());id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}else{String old=jdbc.queryForList("SELECT material_code FROM material_items WHERE id=?",String.class,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("材料不存在"));if(!old.equals(code))throw new IllegalArgumentException("材料编码创建后不能修改");jdbc.update("UPDATE material_items SET material_name=?,specification=?,default_unit=?,material_category=?,description=?,active=? WHERE id=?",name,clean(request.specification()),unit,clean(request.materialCategory()),clean(request.description()),request.active(),id);}audit.log("MaterialItem",id,code,"SAVE","维护材料“"+name+"”");return id;}
    @Transactional public long adjust(BalanceRequest request){if(request.materialId()==null||request.warehouseId()==null||request.newQuantity()==null)throw new IllegalArgumentException("请选择材料、仓库并填写调整后数量");var material=jdbc.queryForList("SELECT * FROM material_items WHERE id=? AND active=1",request.materialId()).stream().findFirst().orElseThrow(()->new IllegalArgumentException("材料不存在或已停用"));if(jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE id=? AND active=1",Integer.class,request.warehouseId())!=1)throw new IllegalArgumentException("仓库无效");String spec=clean((String)material.get("specification"));jdbc.update("INSERT INTO material_inventory_balances(warehouse_id,material_id,material_name,specification_key,unit,quantity) VALUES(?,?,?,?,?,0) ON DUPLICATE KEY UPDATE material_id=VALUES(material_id)",request.warehouseId(),request.materialId(),material.get("material_name"),spec==null?"":spec,material.get("default_unit"));var balance=jdbc.queryForMap("SELECT id,quantity FROM material_inventory_balances WHERE warehouse_id=? AND material_id=? FOR UPDATE",request.warehouseId(),request.materialId());BigDecimal old=(BigDecimal)balance.get("quantity"),change=request.newQuantity().subtract(old);jdbc.update("UPDATE material_inventory_balances SET material_name=?,specification_key=?,unit=?,quantity=? WHERE id=?",material.get("material_name"),spec==null?"":spec,material.get("default_unit"),request.newQuantity(),balance.get("id"));long id=writeTransaction(request.warehouseId(),request.materialId(),String.valueOf(material.get("material_name")),spec,String.valueOf(material.get("default_unit")),"STOCK_ADJUSTMENT",change,request.newQuantity(),"MANUAL_ADJUSTMENT",null,required(request.reason(),"调整原因"));audit.log("MaterialInventoryTransaction",id,String.valueOf(balance.get("id")),"ADJUST","库存由"+old+"调整为"+request.newQuantity());return id;}
    public long writeTransaction(long warehouseId,Long materialId,String name,String specification,String unit,String type,BigDecimal change,BigDecimal after,String sourceType,Long sourceId,String reason){String temp="PENDING-"+UUID.randomUUID();var user=users.current();jdbc.update("INSERT INTO material_inventory_transactions(transaction_no,warehouse_id,material_id,material_name_snapshot,specification_snapshot,unit,transaction_type,quantity_change,balance_after,source_type,source_id,operator_employee_id,operator_name,occurred_at,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),?)",temp,warehouseId,materialId,name,specification,unit,type,change,after,sourceType,sourceId,user.employeeId(),user.name(),clean(reason));long id=jdbc.queryForObject("SELECT id FROM material_inventory_transactions WHERE transaction_no=?",Long.class,temp);jdbc.update("UPDATE material_inventory_transactions SET transaction_no=? WHERE id=?","MT"+String.format("%08d",id),id);return id;}
    private static String clean(String s){return s==null||s.isBlank()?null:s.trim();}private static String required(String s,String label){String v=clean(s);if(v==null)throw new IllegalArgumentException("请填写"+label);return v;}
}
