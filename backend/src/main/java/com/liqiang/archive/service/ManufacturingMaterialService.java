package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ManufacturingMaterialService {
    private final JdbcTemplate jdbc;private final CurrentUserService users;private final AuditLogService audit;private final MaterialInventoryService inventory;
    public record RequirementRequest(Long materialId,String materialName,String specification,BigDecimal plannedQuantity,String unit,String remark){}
    public record MovementRequest(Long requirementId,Long warehouseId,BigDecimal quantity,String remark){}

    public Map<String,Object> detail(long orderId){
        ensureOrder(orderId);var requirements=jdbc.queryForList("""
          SELECT r.id,r.manufacturing_order_id manufacturingOrderId,r.material_id materialId,r.material_name materialName,r.specification,
            r.planned_quantity plannedQuantity,r.unit,r.remark,
            COALESCE(SUM(CASE WHEN m.movement_type='ISSUE' THEN m.quantity ELSE 0 END),0) issuedQuantity,
            COALESCE(SUM(CASE WHEN m.movement_type='RETURN' THEN m.quantity ELSE 0 END),0) returnedQuantity,
            r.planned_quantity-COALESCE(SUM(CASE WHEN m.movement_type='ISSUE' THEN m.quantity WHEN m.movement_type='RETURN' THEN -m.quantity ELSE 0 END),0) pendingQuantity
          FROM manufacturing_material_requirements r LEFT JOIN manufacturing_material_movements m ON m.requirement_id=r.id
          WHERE r.manufacturing_order_id=? GROUP BY r.id ORDER BY r.id
          """,orderId);
        var movements=jdbc.queryForList("""
          SELECT m.id,m.movement_no movementNo,m.requirement_id requirementId,m.warehouse_id warehouseId,
            m.movement_type movementType,m.quantity,m.quantity_change quantityChange,m.operator_name operatorName,
            m.occurred_at occurredAt,m.remark,r.material_name materialName,r.specification,r.unit,w.warehouse_name warehouseName FROM manufacturing_material_movements m
          JOIN manufacturing_material_requirements r ON r.id=m.requirement_id JOIN warehouses w ON w.id=m.warehouse_id
          WHERE m.manufacturing_order_id=? ORDER BY m.occurred_at DESC,m.id DESC
          """,orderId);
        return Map.of("requirements",requirements,"movements",movements);
    }
    @Transactional public long saveRequirement(long orderId,Long id,RequirementRequest request){
        ensureEditableOrder(orderId);String name=clean(request.materialName()),unit=clean(request.unit()),spec=clean(request.specification());if(request.materialId()!=null){var material=jdbc.queryForList("SELECT material_name,specification,default_unit FROM material_items WHERE id=? AND active=1",request.materialId()).stream().findFirst().orElseThrow(()->new IllegalArgumentException("材料档案不存在或已停用"));name=String.valueOf(material.get("material_name"));unit=String.valueOf(material.get("default_unit"));spec=clean((String)material.get("specification"));}name=required(name,"材料名称");unit=required(unit,"单位");BigDecimal qty=request.plannedQuantity();if(qty==null||qty.signum()<=0)throw new IllegalArgumentException("计划用量必须大于0");
        if(id==null){jdbc.update("INSERT INTO manufacturing_material_requirements(manufacturing_order_id,material_id,material_name,specification,planned_quantity,unit,remark) VALUES(?,?,?,?,?,?,?)",orderId,request.materialId(),name,spec,qty,unit,clean(request.remark()));id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
        else{BigDecimal net=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN movement_type='ISSUE' THEN quantity ELSE -quantity END),0) FROM manufacturing_material_movements WHERE requirement_id=?",BigDecimal.class,id);if(net!=null&&qty.compareTo(net)<0)throw new IllegalArgumentException("计划用量不能小于当前净领用量");if(jdbc.update("UPDATE manufacturing_material_requirements SET material_id=?,material_name=?,specification=?,planned_quantity=?,unit=?,remark=? WHERE id=? AND manufacturing_order_id=?",request.materialId(),name,spec,qty,unit,clean(request.remark()),id,orderId)!=1)throw new IllegalArgumentException("计划用料不存在");}
        audit.log("ManufacturingMaterialRequirement",id,String.valueOf(orderId),"SAVE","计划用料："+name+" "+qty+unit);return id;
    }
    @Transactional public long move(long orderId,String type,MovementRequest request){
        ensureEditableOrder(orderId);if(!Set.of("ISSUE","RETURN").contains(type))throw new IllegalArgumentException("领退料类型无效");if(request.requirementId()==null||request.warehouseId()==null)throw new IllegalArgumentException("请选择用料和仓库");BigDecimal qty=request.quantity();if(qty==null||qty.signum()<=0)throw new IllegalArgumentException("数量必须大于0");
        var req=jdbc.queryForList("SELECT * FROM manufacturing_material_requirements WHERE id=? AND manufacturing_order_id=? FOR UPDATE",request.requirementId(),orderId).stream().findFirst().orElseThrow(()->new IllegalArgumentException("计划用料不存在"));if(jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE id=? AND active=1",Integer.class,request.warehouseId())!=1)throw new IllegalArgumentException("仓库无效");
        BigDecimal issued=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN movement_type='ISSUE' THEN quantity ELSE 0 END),0) FROM manufacturing_material_movements WHERE requirement_id=?",BigDecimal.class,request.requirementId());BigDecimal returned=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN movement_type='RETURN' THEN quantity ELSE 0 END),0) FROM manufacturing_material_movements WHERE requirement_id=?",BigDecimal.class,request.requirementId());BigDecimal net=issued.subtract(returned),planned=(BigDecimal)req.get("planned_quantity");
        if("ISSUE".equals(type)&&net.add(qty).compareTo(planned)>0)throw new IllegalArgumentException("领料数量超过计划待领数量，请先调整计划用量");if("RETURN".equals(type)&&qty.compareTo(net)>0)throw new IllegalArgumentException("退料数量超过当前净领用量");
        String tmp="PENDING-"+UUID.randomUUID();var user=users.current();BigDecimal change="ISSUE".equals(type)?qty.negate():qty;jdbc.update("INSERT INTO manufacturing_material_movements(movement_no,manufacturing_order_id,requirement_id,warehouse_id,movement_type,quantity,quantity_change,operator_employee_id,operator_name,occurred_at,remark) VALUES(?,?,?,?,?,?,?,?,?,NOW(),?)",tmp,orderId,request.requirementId(),request.warehouseId(),type,qty,change,user.employeeId(),user.name(),clean(request.remark()));long id=jdbc.queryForObject("SELECT id FROM manufacturing_material_movements WHERE movement_no=?",Long.class,tmp);String no=("ISSUE".equals(type)?"MI":"MR")+String.format("%08d",id);jdbc.update("UPDATE manufacturing_material_movements SET movement_no=? WHERE id=?",no,id);
        String spec=clean((String)req.get("specification"));Long materialId=req.get("material_id") instanceof Number n?n.longValue():null;jdbc.update("INSERT INTO material_inventory_balances(warehouse_id,material_id,material_name,specification_key,unit,quantity) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE material_id=COALESCE(material_id,VALUES(material_id)),quantity=quantity+VALUES(quantity)",request.warehouseId(),materialId,req.get("material_name"),spec==null?"":spec,req.get("unit"),change);BigDecimal after=jdbc.queryForObject("SELECT quantity FROM material_inventory_balances WHERE warehouse_id=? AND material_name=? AND specification_key=? AND unit=?",BigDecimal.class,request.warehouseId(),req.get("material_name"),spec==null?"":spec,req.get("unit"));inventory.writeTransaction(request.warehouseId(),materialId,String.valueOf(req.get("material_name")),spec,String.valueOf(req.get("unit")),"ISSUE".equals(type)?"PRODUCTION_ISSUE":"PRODUCTION_RETURN",change,after,"MANUFACTURING_MATERIAL_MOVEMENT",id,clean(request.remark()));audit.log("ManufacturingMaterialMovement",id,no,type,"ISSUE".equals(type)?"生产领料":"生产退料");return id;
    }
    private void ensureOrder(long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders WHERE id=?",Integer.class,id)!=1)throw new IllegalArgumentException("生产任务不存在");}
    private void ensureEditableOrder(long id){String status=jdbc.queryForList("SELECT status FROM manufacturing_orders WHERE id=?",String.class,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("生产任务不存在"));if(Set.of("COMPLETED","CLOSED","CANCELLED").contains(status))throw new IllegalStateException("已结束的生产任务不能办理领退料");}
    private static String clean(String s){return s==null||s.isBlank()?null:s.trim();}private static String required(String s,String label){String v=clean(s);if(v==null)throw new IllegalArgumentException("请填写"+label);return v;}
}
