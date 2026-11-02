package com.liqiang.archive.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only compatibility for task snapshots created before product process management was withdrawn. */
@Service
public class ProductionTrackingProcessService {
    private final JdbcTemplate jdbc;

    public ProductionTrackingProcessService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> snapshot(long orderId) {
        var rows=jdbc.queryForList("SELECT production_order_id AS orderId,template_id AS templateId,"
                +"template_version AS templateVersion,source_kind AS sourceKind,revision_no AS revisionNo "
                +"FROM production_tracking_process_snapshots WHERE production_order_id=?",orderId);
        if(rows.isEmpty())return Map.of("orderId",orderId,"sourceKind","NOT_CAPTURED",
                "nodes",List.of(),"edges",List.of());
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        result.put("nodes",jdbc.queryForList("SELECT stage_code AS code,stage_label AS label,sort_no AS sortNo,"
                +"conditional,applicability,applicability_reason AS applicabilityReason "
                +"FROM production_tracking_process_snapshot_nodes WHERE production_order_id=? "
                +"ORDER BY sort_no,stage_code",orderId));
        result.put("edges",jdbc.queryForList("SELECT predecessor_code AS predecessor,successor_code AS successor "
                +"FROM production_tracking_process_snapshot_edges WHERE production_order_id=?",orderId));
        return result;
    }

    static boolean flag(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0;
    }
}
