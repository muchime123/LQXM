package com.liqiang.archive.service;

import com.liqiang.archive.service.ProductionTrackingStageCatalog.StageDef;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Loads code-migrated tracking node definitions for existing follow-up calculations. */
@Service
public class ProductionTrackingStageService {
    private static final Logger log=LoggerFactory.getLogger(ProductionTrackingStageService.class);
    private static final String SELECT="SELECT workshop_kind,stage_code,stage_label,sort_no,optional,"
            +"match_keywords,exclude_keywords,header_aliases,enabled,default_included "
            +"FROM production_tracking_stage_defs ORDER BY workshop_kind,sort_no,id";
    private final JdbcTemplate jdbc;

    public ProductionTrackingStageService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @PostConstruct
    public void reload() {
        try {
            var defs=jdbc.query(SELECT,(r,n)->new StageDef(r.getString("workshop_kind"),r.getString("stage_code"),
                    r.getString("stage_label"),r.getInt("sort_no"),r.getInt("optional")==1,
                    r.getString("match_keywords"),r.getString("exclude_keywords"),
                    splitAliases(r.getString("header_aliases")),r.getInt("enabled")==1,
                    r.getInt("default_included")==1));
            if(defs.isEmpty()) {
                log.warn("跟单节点定义为空，沿用代码内置默认流程");
                return;
            }
            ProductionTrackingStageCatalog.replace(defs);
        } catch(RuntimeException error) {
            log.error("跟单节点定义加载失败，沿用代码内置默认流程",error);
        }
    }

    private static List<String> splitAliases(String raw) {
        if(raw==null||raw.isBlank())return List.of();
        var result=new ArrayList<String>();
        for(String part:raw.split("[,，]"))if(!part.isBlank())result.add(part.trim());
        return result;
    }
}
