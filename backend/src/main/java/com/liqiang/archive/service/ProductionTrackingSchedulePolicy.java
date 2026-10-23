package com.liqiang.archive.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生产跟单的只读工期推算；所有日期均按自然日，缺少间隔时不猜测。 */
final class ProductionTrackingSchedulePolicy {
    private ProductionTrackingSchedulePolicy() {}

    record Result(LocalDate latestStartDate, LocalDate predictedFinishDate,
                  Long slackDays, String risk, Map<String,LocalDate> targetDates) {}

    static Result calculate(List<String> codes, Map<String,BigDecimal> intervals,
                            Map<String,Boolean> applicable, Map<String,String> statuses,
                            Map<String,LocalDate> completed, Map<String,LocalDate> manual,
                            LocalDate scheduled, LocalDate deadline, LocalDate today) {
        var active=codes.stream().filter(code->!ProductionTrackingPolicy.isOptionalStage(code)
                ||Boolean.TRUE.equals(applicable.get(code))).toList();
        var targets=new LinkedHashMap<String,LocalDate>();
        LocalDate target=deadline;
        for(int i=active.size()-1;i>=0;i--){
            String code=active.get(i);
            if(!"DONE".equals(statuses.get(code)))targets.put(code,target);
            BigDecimal days=intervals.get(code);
            if(target!=null)target=days==null?null:target.minusDays(Math.round(days.doubleValue()));
        }
        LocalDate latestStart=target;
        if(scheduled==null){
            String risk=latestStart==null?"NO_BASELINE":today.isAfter(latestStart)?"SCHEDULE_LATE"
                    :!today.isBefore(latestStart.minusDays(2))?"SCHEDULE_DUE":"SCHEDULE_NORMAL";
            return new Result(latestStart,null,null,risk,targets);
        }
        LocalDate cursor=scheduled;
        for(String code:active){
            LocalDate done=completed.get(code);
            if("DONE".equals(statuses.get(code))&&done!=null){cursor=done;continue;}
            LocalDate override=manual.get(code);
            BigDecimal days=intervals.get(code);
            cursor=override!=null?override:cursor!=null&&days!=null
                    ?cursor.plusDays(Math.round(days.doubleValue())):null;
        }
        Long slack=deadline!=null&&cursor!=null?ChronoUnit.DAYS.between(cursor,deadline):null;
        String risk=slack==null?"UNKNOWN":slack<0?"PRESSURE":slack==0?"NO_BUFFER":"ON_TRACK";
        return new Result(latestStart,cursor,slack,risk,targets);
    }
}
