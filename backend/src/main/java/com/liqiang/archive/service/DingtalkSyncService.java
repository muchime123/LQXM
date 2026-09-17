package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.liqiang.archive.config.DingtalkProperties;
import com.liqiang.archive.dto.ExpenseDtos;
import com.liqiang.archive.entity.DingtalkSyncRun;
import com.liqiang.archive.entity.Employee;
import com.liqiang.archive.entity.EmployeeExternalMapping;
import com.liqiang.archive.entity.ExpenseClaim;
import com.liqiang.archive.entity.ExpenseItem;
import com.liqiang.archive.repository.DingtalkSyncRunRepository;
import com.liqiang.archive.repository.EmployeeExternalMappingRepository;
import com.liqiang.archive.repository.EmployeeRepository;
import com.liqiang.archive.repository.ExpenseClaimRepository;
import com.liqiang.archive.repository.ExpenseItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P6.1 钉钉日常报销同步核心服务。
 * <p>
 * 核心链路：
 * <ol>
 *   <li>创建 dingtalk_sync_runs 初始记录（RUNNING）</li>
 *   <li>DingtalkClient 拉 access_token → 审批列表 → 逐条详情</li>
 *   <li>对每条审批单：做 员工身份映射（先 DINGTALK userId → mapping；失败再按姓名精准找 employees；命中 1 条则自动写入 mapping 但不创建新员工）。</li>
 *   <li>计算 SHA-256 source_hash，与本地比较：未存在=插入，hash 不同=更新，hash 相同=unchanged</li>
 *   <li>批量写 expense_claims + expense_items（使用 Repo，不操作 JDBC 批处理以保持简单和审计易读）</li>
 *   <li>异常不冒泡：每条失败记录到 errorDetails（JSON 数组），最后汇总 PARTIAL / FAILED 状态，返回给前端。</li>
 * </ol>
 * 安全边界：
 * <ul>
 *   <li>绝不创建新员工 — 员工名册入口是 EmployeeService（管理员操作）。</li>
 *   <li>无法识别的报销单置 employee_id=NULL，并累计 mappingMissed（前端指标提示管理员去处理）。</li>
 * </ul>
 */
@Service
public class DingtalkSyncService {

    private final DingtalkClient client;
    private final DingtalkProperties props;
    private final DingtalkSyncRunRepository runRepo;
    private final ExpenseClaimRepository claimRepo;
    private final ExpenseItemRepository itemRepo;
    private final EmployeeRepository employeeRepo;
    private final EmployeeExternalMappingRepository mappingRepo;
    private final ObjectMapper objectMapper;
    private final DepartmentService departmentService;

    /** 自注入：doSync → upsertOne/upsertOneV2 是同类内部调用，直接 this 调用会绕过 Spring AOP 代理，
     *  导致 @Transactional(REQUIRES_NEW) 不生效。通过 self 代理调用确保每条审批单都在独立事务中执行。 */
    @Autowired
    @Lazy
    private DingtalkSyncService self;

    public DingtalkSyncService(DingtalkClient client,
                               DingtalkProperties props,
                               DingtalkSyncRunRepository runRepo,
                               ExpenseClaimRepository claimRepo,
                               ExpenseItemRepository itemRepo,
                               EmployeeRepository employeeRepo,
                               EmployeeExternalMappingRepository mappingRepo,
                               ObjectMapper objectMapper,
                               DepartmentService departmentService) {
        this.client = client;
        this.props = props;
        this.runRepo = runRepo;
        this.claimRepo = claimRepo;
        this.itemRepo = itemRepo;
        this.employeeRepo = employeeRepo;
        this.mappingRepo = mappingRepo;
        this.objectMapper = objectMapper;
        this.departmentService = departmentService;
    }

    // ===================== 状态页：前端「钉钉集成」卡片使用 =====================

    public ExpenseDtos.DingtalkStatus getStatus() {
        boolean enabled = props.isEnabled();
        boolean configured = props.isCredentialsConfigured();
        String appKeyMasked = props.getMaskedAppKey();

        // ====== 诊断信息 ======
        String processCodeRaw = props.getReimbursementProcessCode() == null ? "" : props.getReimbursementProcessCode().trim();
        String processCodeMasked = mask(processCodeRaw, 10, 6);
        boolean useV2Api = client.isV2ProcessCode(processCodeRaw);

        // ====== 真实连通性测试：调 /gettoken，不缓存结果（状态页只给人看，调用量低） ======
        boolean connected = false;
        String connectError = null;
        String accessTokenStatus = "N/A";
        String accessTokenMasked = null;
        if (enabled && configured) {
            try {
                String token = client.fetchAccessToken();
                connected = true;
                if (token == null || token.isBlank()) {
                    accessTokenStatus = "EMPTY";
                    accessTokenMasked = null;
                    connected = false;
                    connectError = "/gettoken 返回了空 access_token（钉钉服务端返回 errcode=0 但 access_token 字段缺失或空）。processCodeRaw=\"" + (processCodeRaw.isEmpty() ? "(空)" : processCodeMasked) + "\" useV2=" + useV2Api;
                } else {
                    accessTokenStatus = "OK";
                    accessTokenMasked = mask(token, 3, 2);
                }
            } catch (Exception e) {
                connected = false;
                accessTokenStatus = "FAIL";
                accessTokenMasked = null;
                connectError = truncate(e.getMessage() == null ? "(空错误信息)" : e.getMessage(), 260)
                        + "；processCodeRaw=\"" + (processCodeRaw.isEmpty() ? "(空)" : processCodeMasked) + "\" useV2=" + useV2Api;
            }
        } else if (!enabled) {
            connectError = "钉钉集成未启用：请在后端 .env 配置 DINGTALK_ENABLED=true（或 IDE 启用 EnvFile 插件）";
        } else {
            connectError = "凭证未齐全：请在后端 .env 配置 DINGTALK_APP_KEY 与 DINGTALK_APP_SECRET，并确保 Spring Boot 启动时已加载 .env（需要 IDE 的 EnvFile 插件或启动脚本 export）";
        }

        Optional<DingtalkSyncRun> last = runRepo.findLatestReimbursement();
        String lastStatus = last.map(DingtalkSyncRun::getStatus).orElse(null);
        LocalDateTime lastAt = last.map(DingtalkSyncRun::getStartedAt).orElse(null);

        long totalClaims = claimRepo.count();
        BigDecimal monthAmt = nullToZero(claimRepo.sumThisMonthApprovedAmount());
        long totalRuns = runRepo.count();
        long failedRuns = 0; // 暂不展开；保留扩展位
        return new ExpenseDtos.DingtalkStatus(
                enabled,
                configured,
                connected,
                connectError,
                appKeyMasked,
                lastStatus,
                lastAt,
                new ExpenseDtos.DingtalkStatus.Stats(totalClaims, monthAmt, totalRuns, failedRuns),
                processCodeMasked,
                useV2Api,
                accessTokenStatus,
                accessTokenMasked,
                "dingtalk-v2.2"
        );
    }

    private static String mask(String s, int head, int tail) {
        if (s == null || s.isEmpty()) return "(空)";
        int n = s.length();
        if (n <= head + tail) return "(长度不足，已省略中间 " + n + " 字符)";
        return s.substring(0, head) + "…" + s.substring(n - tail);
    }

    public Page<ExpenseDtos.SyncRunRow> listRuns(int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page - 1), Math.max(1, Math.min(100, size)));
        return runRepo.findBySyncTypeOrderByStartedAtDesc("DAILY_REIMBURSEMENT", pr)
                .map(this::toRunRow);
    }

    public ExpenseDtos.SyncRunDetail getRunDetail(Long id) {
        DingtalkSyncRun r = runRepo.findById(id).orElse(null);
        if (r == null) return null;
        return new ExpenseDtos.SyncRunDetail(toRunRow(r), r.getErrorDetails());
    }

    // ===================== 同步触发入口 =====================

    /**
     * 手动触发同步。
     *
     * @param triggeredByName 触发者姓名（未登录态可空）。
     * @return 同步 runId（前端可轮询 /integrations/dingtalk/sync-runs/{id} 查看结果）。
     * @throws DingtalkNotEnabledException 未启用或未配置凭证。
     */
    // 注意：此处不加 @Transactional，外层不开启共享事务；所有写 SyncRun / 写 Claim / 写 Item 的操作
    // 都由 saveRunFinalize 和 upsertOne 用 REQUIRES_NEW 各自独立事务提交，避免某条 claim/item 校验失败
    // 把全局事务标成 rollback-only、导致 SyncRun 也被意外回滚的问题（之前 HHH000099 + UnexpectedRollbackException 的根因）。
    public ExpenseDtos.SyncTriggered triggerReimbursementSync(String triggeredByName) {
        return triggerReimbursementSync(triggeredByName, null);
    }

    /**
     * 手动触发同步日常报销单据。
     *
     * @param triggeredByName 触发人（可为 null）
     * @param daysBack 本次同步回溯天数；为空时使用配置默认（app.dingtalk.sync-days-back），上限 90 天防止误传过大范围耗尽 API 配额
     */
    public ExpenseDtos.SyncTriggered triggerReimbursementSync(String triggeredByName, Integer daysBack) {
        client.ensureEnabled();
        LocalDate to = LocalDate.now();
        int effective = daysBack != null && daysBack > 0 ? Math.min(daysBack, 90) : Math.max(1, props.getSyncDaysBack());
        LocalDate from = to.minusDays(effective);
        return doSync(from, to, "MANUAL", triggeredByName);
    }

    // ===================== 内部执行器 =====================

    private ExpenseDtos.SyncTriggered doSync(LocalDate from, LocalDate to, String trigger, String triggerName) {
        DingtalkSyncRun run = new DingtalkSyncRun();
        run.setSyncType("DAILY_REIMBURSEMENT");
        run.setStatus("RUNNING");
        run.setTriggeredBy(trigger);
        run.setTriggeredByName(triggerName);
        run.setStartedAt(LocalDateTime.now());
        run.setRangeDateFrom(from);
        run.setRangeDateTo(to);
        run = saveRunFinalize(run, null); // 独立事务立刻 commit RUNNING，后续即使任何环节抛错，runId 一定已经存在
        final long runId = run.getId();

        ArrayNode errorArr = objectMapper.createArrayNode();
        String processCodeRaw = props.getReimbursementProcessCode() == null ? "" : props.getReimbursementProcessCode().trim();
        String processCodeMasked = mask(processCodeRaw, 10, 6);
        String apiVersionHintOnRun = "dingtalk-v2.2";
        try {
            String token = client.fetchAccessToken();
            String tokenMasked = token == null || token.isBlank() ? "(EMPTY)" : mask(token, 3, 2);
            boolean useV2Raw = client.isV2ProcessCode(processCodeRaw);
            // ==== 诊断快照：永远先写入 errorArr 顶部，无论成功失败都能在同步详情 Drawer 看到 ====
            var metaSnap = objectMapper.createObjectNode();
            metaSnap.put("stage", "meta");
            metaSnap.put("apiVersionHint", apiVersionHintOnRun);
            metaSnap.put("processCodeRaw", processCodeMasked);
            metaSnap.put("props.processCodeLength", processCodeRaw.length());
            metaSnap.put("isV2Raw_BeforeOverride", useV2Raw);
            metaSnap.put("accessTokenMasked", tokenMasked);
            metaSnap.put("timeRange", from.toString() + " ~ " + to.toString());
            metaSnap.put("syncScope", "RECENT_" + (to.toEpochDay() - from.toEpochDay()) + "D");
            // 诊断快照后追加的分支：强制走 v2（只要 ProcessCode 非空且看起来像 PROC- 模板）。
            // 此前出现过「status 接口显示 useV2=true，但同步实际走老版 listids（errcode=88 access_token is blank）」
            // 的不一致，这里把路由分支显式写在此处，不再依赖 isV2ProcessCode 的外部判断。
            final boolean forceV2 = processCodeRaw.toUpperCase().startsWith("PROC-");
            metaSnap.put("forceV2_ActualBranchTaken", forceV2);
            errorArr.add(metaSnap);

            int listMax = props.getSyncMaxItems();
            List<String> ids = forceV2
                    ? client.listProcessInstanceIdsV2(token, from, to, listMax)
                    : client.listProcessInstanceIds(token, from, to, listMax);
            run.setFetchedCount(ids.size());

            int created = 0, updated = 0, unchanged = 0, failed = 0, missed = 0;
            int deptCreated = 0, deptUpgraded = 0;
            String firstErrMsg = null;
            for (String pid : ids) {
                try {
                    UpsertResult r = forceV2
                        ? self.upsertOneV2(token, pid)
                        : self.upsertOne(token, pid);
                    if (r == UpsertResult.CREATED) created++;
                    else if (r == UpsertResult.UPDATED) updated++;
                    else unchanged++;
                    if (r.mappingMissed) missed++;
                    deptCreated += r.deptMappingsCreated;
                    deptUpgraded += r.deptMappingsUpgraded;
                } catch (Exception ex) {
                    failed++;
                    String msg = ex.getMessage() == null ? "未知错误" : ex.getMessage();
                    if (firstErrMsg == null) firstErrMsg = "[" + pid + "] " + truncate(msg, 120);
                    var errObj = objectMapper.createObjectNode();
                    errObj.put("external_id", pid);
                    errObj.put("message", truncate(msg, 300));
                    errorArr.add(errObj);
                    if (errorArr.size() > 200) break; // 防止错误详情爆表
                }
            }

            run.setCreatedCount(created);
            run.setUpdatedCount(updated);
            run.setUnchangedCount(unchanged);
            run.setFailedCount(failed);
            run.setMappingMissed(missed);
            run.setDeptMappingsCreated(deptCreated);
            run.setDeptMappingsUpgraded(deptUpgraded);
            run.setErrorSummary(firstErrMsg);
            if (errorArr.size() > 1) run.setErrorDetails(errorArr.toString()); // >1 表示除 meta 外还有真正错误

            if (failed == 0) run.setStatus("SUCCESS");
            else if (created + updated + unchanged > 0) run.setStatus("PARTIAL");
            else run.setStatus("FAILED");

        } catch (DingtalkNotEnabledException e) {
            // 理论上入口已 ensureEnabled，但这里防御下。
            run.setStatus("SKIPPED");
            run.setErrorSummary(e.getReason());
            var metaSnap = objectMapper.createObjectNode();
            metaSnap.put("stage", "meta");
            metaSnap.put("apiVersionHint", apiVersionHintOnRun);
            metaSnap.put("processCodeRaw", processCodeMasked);
            metaSnap.put("exception", "DingtalkNotEnabledException");
            errorArr.insert(0, metaSnap);
            saveRunFinalize(run, errorArr);
            throw e;
        } catch (Exception e) {
            run.setStatus("FAILED");
            // 若 metaSnap 还在 errorArr 里就是 insert(0, metaSnap) 格式；否则补一个
            if (errorArr.isEmpty() || !"meta".equals(errorArr.get(0).path("stage").asText())) {
                var metaSnap = objectMapper.createObjectNode();
                metaSnap.put("stage", "meta");
                metaSnap.put("apiVersionHint", apiVersionHintOnRun);
                metaSnap.put("processCodeRaw", processCodeMasked);
                metaSnap.put("exceptionAt", "outer");
                errorArr.insert(0, metaSnap);
            }
            run.setErrorSummary("同步任务执行异常：" + truncate(e.getMessage() == null ? "未知" : e.getMessage(), 200));
            var errObj = objectMapper.createObjectNode();
            errObj.put("stage", "run-level");
            errObj.put("message", truncate(e.getMessage() == null ? "" : e.getMessage(), 800));
            if (e.getCause() != null) errObj.put("cause", truncate(e.getCause().getMessage() == null ? "" : e.getCause().getMessage(), 400));
            errorArr.add(errObj);
            saveRunFinalize(run, errorArr); // 独立事务立刻提交，即使有异常也保证 runId+error_details 落库
            // 非启用类异常不向外抛 — 给前端返回 runId 让其去看详情
            return new ExpenseDtos.SyncTriggered(runId, "同步已启动但发生异常，请查看「同步记录」获取错误明细。");
        }

        saveRunFinalize(run, errorArr); // 正常分支（SUCCESS/PARTIAL/FAILED）独立事务提交
        String msg;
        switch (run.getStatus()) {
            case "SUCCESS" -> {
                String deptNote = (run.getDeptMappingsCreated() + run.getDeptMappingsUpgraded()) > 0
                        ? String.format("部门映射：新增 %d、升级 %d。", run.getDeptMappingsCreated(), run.getDeptMappingsUpgraded())
                        : "部门映射无变化。";
                msg = String.format("同步完成：拉取 %d 条，新增 %d，更新 %d，跳过 %d。%s",
                        run.getFetchedCount(), run.getCreatedCount(), run.getUpdatedCount(), run.getUnchangedCount(), deptNote);
            }
            case "PARTIAL" -> msg = String.format("部分成功：成功 %d / 失败 %d / 映射未命中 %d。",
                    run.getCreatedCount() + run.getUpdatedCount() + run.getUnchangedCount(), run.getFailedCount(), run.getMappingMissed());
            default -> msg = "同步失败，请查看「同步记录」错误明细。";
        }
        return new ExpenseDtos.SyncTriggered(runId, msg);
    }

    private void finalizeRun(DingtalkSyncRun run, ArrayNode errorArr) {
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        if (run.getErrorDetails() == null && errorArr.size() > 0) {
            try { run.setErrorDetails(objectMapper.writeValueAsString(errorArr)); }
            catch (JsonProcessingException ignored) { /* 不会 */ }
        }
    }

    /**
     * 独立事务（REQUIRES_NEW）把 dingtalk_sync_runs 落库并立刻 commit。
     * <p>
     * 为什么要单独拆成 REQUIRES_NEW：此前存在一个严重连锁问题 —
     * <pre>
     *   triggerReimbursementSync 外层 @Transactional（一条大事务）
     *     → doSync 里 claim/item 某条 ExpenseItem Bean Validation 失败（Hibernate 内部异常）
     *     → 外层 catch 吞异常但 EntityManager 已被标记为 rollback-only
     *     → 最后 runRepo.save(run) 触发 flush 再炸 HHH000099
     *     → 事务提交 UnexpectedRollbackException → 连 SyncRun 记录本身也回滚掉
     *     → 前端 HTTP 500，用户看不到任何同步记录和 error_details（P6.1 第 2 次验收踩坑）。
     * </pre>
     * 现在改为：SyncRun 的 RUNNING 初始保存、SUCCESS/PARTIAL/FAILED 最终更新 + error_details 都走这个 REQUIRES_NEW，
     * 无论 claim/item 事务如何 rollback，SyncRun 记录永远存在，前端永远能看到 runId + 详细错误（含 stage=meta 诊断快照）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    DingtalkSyncRun saveRunFinalize(DingtalkSyncRun run, ArrayNode errorArrOrNull) {
        if (errorArrOrNull != null) finalizeRun(run, errorArrOrNull);
        return runRepo.save(run);
    }

    private enum UpsertResult {
        CREATED, UPDATED, UNCHANGED;
        boolean mappingMissed;
        int deptMappingsCreated;   // 本条是否新建了真实 deptId 部门映射
        int deptMappingsUpgraded;  // 本条是否用真实 deptId 替换了 NAME# 过渡映射
    }

    /** 处理单条审批单实例：员工映射 → 解析字段 → hash 比较 → upsert。
     *  独立事务（REQUIRES_NEW）：本条 claim/item 校验失败只回滚该条，不污染其他审批单和 SyncRun。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertResult upsertOne(String accessToken, String processInstanceId) {
        JsonNode detail = client.getProcessInstanceDetail(accessToken, processInstanceId);
        return applyDetail(detail, processInstanceId);
    }

    /** 新版 v2：通过 getProcessInstanceDetailV2 拉详情，内部已适配成老版扁平结构；其余 upsert 逻辑同 v1。
     *  独立事务（REQUIRES_NEW）：本条 claim/item 校验失败只回滚该条，不污染其他审批单和 SyncRun。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertResult upsertOneV2(String accessToken, String processInstanceId) {
        JsonNode detail = client.getProcessInstanceDetailV2(accessToken, processInstanceId);
        return applyDetail(detail, processInstanceId);
    }

    /** 共享：给定 detail（已经是老版扁平格式）+ processInstanceId，执行身份解析 / 表单解析 / hash 对比 / upsert。 */
    private UpsertResult applyDetail(JsonNode detail, String processInstanceId) {
        // ---- 1. 提单人身份解析 ----
        String title = text(detail, "title", "business_title");
        String dingtalkUserId = text(detail, "originator_userid", "originator", "createBy", "creator_userid");
        // originator_dept_name 是组织/子公司名，绝不能作为人员姓名。
        // v2 不返回 originatorUserName，优先从标准标题“某某提交的日常报销”提取。
        String submitterName = text(detail, "originator_user_name", "create_by_name", "creator_name");
        if (submitterName == null || submitterName.isBlank()) submitterName = submitterNameFromTitle(title);
        String submitterDept = text(detail, "originator_dept_name", "dept_name", "create_by_dept");
        // 发起人部门真实钉钉 ID（v2 详情返回 originatorDeptId，随审批数据携带、无需通讯录读取权限）。
        // 优先用它绑定统一部门（department_external_mappings 真实键），缺失时回落名称解析。
        String submitterDeptId = text(detail, "originator_dept_id", "dept_id", "dingtalk_dept_id");

        EmployeeMappingResolved emp = resolveEmployee(dingtalkUserId, submitterName);

        // ---- 2. 主单据字段 ----
        String claimNo = firstNonBlank(text(detail, "business_id", "process_code_instance_id"), processInstanceId);
        String status = mapApprovalStatus(text(detail, "status", "result", "approval_status"));
        LocalDateTime created = toDateTime(text(detail, "create_time", "gmt_create", "createDate"));
        LocalDateTime finished = toDateTime(text(detail, "finish_time", "gmt_finished", "modifyDate"));
        LocalDate claimDate = created != null ? created.toLocalDate() : null;
        LocalDate approvalFinishedDate = finished != null ? finished.toLocalDate() : null;
        String category = guessCategory(title, detail.path("form_component_values"));

        // ---- 3. 明细解析（form_component_values） ----
        ParsedItems parsed = parseItems(detail.path("form_component_values"));
        BigDecimal totalAmount = parsed.totalAmount;

        // 兜底：如果 form_component_values 里没解析到任何金额（常见于新版 v2 详情把"金额合计"挂在根节点/processInstance，
        // 或金额字段名不在识别表里），再尝试 detail 根节点及常见嵌套路径拿：
        //   total_money / totalMoney / totalAmount / amount / actualPayAmount /
        //   processInstance.totalMoney / main_process_instance.total_money / amountInCent (÷100) /
        //   result.actualPayAmount / approve_content.total_amount
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal rootAmt = firstMoney(
                money(detail, "total_money", "totalMoney", "totalAmount", "amount",
                             "actualPayAmount", "total_pay_amount", "totalpayamount"),
                money(detail.path("processInstance"), "total_money", "totalMoney", "totalAmount",
                             "actualPayAmount", "amount"),
                money(detail.path("main_process_instance"), "total_money", "totalMoney", "totalAmount"),
                moneyCents(detail, "amountInCent", "amount_in_cent"),
                money(detail.path("result"), "actualPayAmount", "totalMoney"),
                money(detail.path("approve_content"), "total_amount", "totalMoney", "totalAmount")
            );
            if (rootAmt != null && rootAmt.compareTo(BigDecimal.ZERO) > 0) totalAmount = rootAmt;
        }
        BigDecimal approvedAmount = totalAmount; // P6.1 钉钉不区分申请/核定，都按 total 记

        String remark = text(detail, "remark", "comments", "approve_comment");
        String attachments = collectAttachments(detail);

        // ---- 4. 来源 hash（所有内容字段，排除 id/时间戳/同步自维护字段） ----
        String hash = sha256(String.join("||",
                nvl(claimNo), nvl(title), nvl(status), nvl(category),
                nvl(dingtalkUserId), nvl(submitterName), nvl(submitterDept), nvl(submitterDeptId),
                created == null ? "" : created.toString(),
                finished == null ? "" : finished.toString(),
                totalAmount == null ? "" : totalAmount.toPlainString(),
                nvl(remark),
                parsed.itemsFingerprint
        ));

        Optional<ExpenseClaim> existingOpt = claimRepo.findByExternalSourceAndExternalId("DINGTALK", processInstanceId);
        ExpenseClaim claim;
        UpsertResult result;
        DepartmentService.ResolveResult dep = null;
        if (existingOpt.isPresent()) {
            claim = existingOpt.get();
            if (hash.equals(claim.getSourceHash())) {
                // 无变化：touch last_synced_at 即可
                claim.setLastSyncedAt(LocalDateTime.now());
                claimRepo.save(claim);
                result = UpsertResult.UNCHANGED;
            } else {
                dep = applyClaimFields(claim, processInstanceId, claimNo, title, status, category,
                        emp.employeeId(), dingtalkUserId, submitterName, submitterDept, submitterDeptId,
                        claimDate, approvalFinishedDate, totalAmount, approvedAmount,
                        remark, attachments, created, finished, hash);
                claimRepo.save(claim);
                // 先删旧明细再写新
                itemRepo.deleteByExpenseClaimId(claim.getId());
                saveItems(claim.getId(), parsed.items);
                result = UpsertResult.UPDATED;
            }
        } else {
            claim = new ExpenseClaim();
            claim.setExternalSource("DINGTALK");
            dep = applyClaimFields(claim, processInstanceId, claimNo, title, status, category,
                    emp.employeeId(), dingtalkUserId, submitterName, submitterDept, submitterDeptId,
                    claimDate, approvalFinishedDate, totalAmount, approvedAmount,
                    remark, attachments, created, finished, hash);
            claim = claimRepo.save(claim);
            saveItems(claim.getId(), parsed.items);
            result = UpsertResult.CREATED;
        }
        result.mappingMissed = emp.missed();
        if (dep != null) {
            result.deptMappingsCreated = dep.externalMappingCreated() ? 1 : 0;
            result.deptMappingsUpgraded = dep.nameMappingUpgraded() ? 1 : 0;
        }
        return result;
    }

    static String submitterNameFromTitle(String title) {
        if (title == null) return null;
        int marker = title.indexOf("提交的");
        if (marker <= 0) return null;
        String name = title.substring(0, marker).trim();
        return name.isBlank() || name.length() > 100 ? null : name;
    }

    private DepartmentService.ResolveResult applyClaimFields(ExpenseClaim c, String externalId, String claimNo, String title,
                                                             String status, String category, Long employeeId, String dingtalkUserId,
                                                             String submitterName, String submitterDept, String submitterDeptId,
                                                             LocalDate claimDate,
                                                             LocalDate approvalFinishedDate, BigDecimal totalAmount, BigDecimal approvedAmount,
                                                             String remark, String attachments, LocalDateTime created, LocalDateTime finished,
                                                             String hash) {
        c.setExternalId(externalId);
        c.setClaimNo(claimNo);
        c.setTitle(title);
        c.setApprovalStatus(status);
        c.setExpenseCategory(category);
        c.setEmployeeId(employeeId);
        c.setDingtalkUserId(dingtalkUserId);
        c.setSubmitterNameSnapshot(submitterName);
        c.setSubmitterDeptSnapshot(submitterDept);
        c.setDingtalkDeptId(submitterDeptId);
        c.setDepartmentName(submitterDept);
        c.setClaimDate(claimDate);
        c.setApprovalFinishedDate(approvalFinishedDate);
        c.setTotalAmount(totalAmount);
        c.setApprovedAmount(approvedAmount);
        c.setRemark(remark);
        c.setAttachmentInfo(attachments);
        c.setSourceCreatedBy(submitterName);
        c.setSourceCreatedAt(created);
        c.setSourceUpdatedAt(finished);
        c.setSourceHash(hash);
        c.setLastSyncedAt(LocalDateTime.now());
        return departmentService.resolveOrCreateByExternalId(submitterDeptId, submitterDept, "DINGTALK");
    }

    private void saveItems(Long claimId, List<ExpenseItem> items) {
        for (ExpenseItem it : items) {
            it.setExpenseClaimId(claimId);
            itemRepo.save(it);
        }
    }

    // ===================== 员工映射（不创建新员工！） =====================

    record EmployeeMappingResolved(Long employeeId, boolean missed) { }

    EmployeeMappingResolved resolveEmployee(String dingtalkUserId, String submitterName) {
        // 1. 精确映射：DINGTALK userId → employee_external_mappings
        if (dingtalkUserId != null && !dingtalkUserId.isBlank()) {
            Optional<Long> empId = mappingRepo.findEmployeeIdBySourceAndExternalUser("DINGTALK", dingtalkUserId);
            if (empId.isPresent()) return new EmployeeMappingResolved(empId.get(), false);
        }
        // 2. 外部姓名兜底映射（P5.1 员工映射维护时可能已写 external_name）
        if (submitterName != null && !submitterName.isBlank()) {
            List<Long> ids = mappingRepo.findEmployeeIdsBySourceAndExternalName("DINGTALK", submitterName);
            if (ids.size() == 1) return new EmployeeMappingResolved(ids.get(0), false);
        }
        // 3. 花名册姓名兜底（P6.1 边界：只命中 1 条才自动 link，否则保守不猜；命中则写入 mapping 方便下次直接匹配）
        if (submitterName != null && !submitterName.isBlank()) {
            List<Employee> matches = employeeRepo.findByName(submitterName.trim());
            if (matches.size() == 1) {
                Employee e = matches.get(0);
                try {
                    EmployeeExternalMapping m = new EmployeeExternalMapping();
                    m.setEmployeeId(e.getId());
                    m.setSourceSystem("DINGTALK");
                    m.setExternalUserId(dingtalkUserId != null ? dingtalkUserId : ("NAME_LINK#" + e.getName()));
                    m.setExternalName(e.getName());
                    m.setExternalDepartment(null);
                    m.setIsPrimary(Boolean.TRUE);
                    m.setMappedBy("DINGTALK_AUTO_LINK_BY_NAME");
                    mappingRepo.save(m);
                    return new EmployeeMappingResolved(e.getId(), false);
                } catch (Exception ignored) {
                    // 唯一键冲突（并发）→ 忽略本条，mappingMissed 也不计数，直接返回员工ID
                    return new EmployeeMappingResolved(e.getId(), false);
                }
            }
        }
        return new EmployeeMappingResolved(null, true);
    }

    // ===================== 审批字段解析 & 明细项解析 =====================

    /** 把钉钉各种 status 枚举归并到我们支持的 5 种。 */
    private static String mapApprovalStatus(String raw) {
        if (raw == null || raw.isBlank()) return "PENDING";
        switch (raw) {
            case "APPROVED", "agree", "OK", "COMPLETED", "2", "FINISHED", "Y" -> { return "APPROVED"; }
            case "REJECTED", "refuse", "NO", "TERMINATED", "3", "N" -> { return "REJECTED"; }
            case "CANCELED", "cancel", "WITHDRAW", "revoked" -> { return "CANCELED"; }
            case "PENDING", "RUNNING", "WAIT", "doing", "in_progress", "1" -> { return "PENDING"; }
            default -> { return raw.length() > 20 ? "OTHER" : raw; }
        }
    }

    /** 基于标题关键词粗略分类；找不到时看表单内有没有"差旅费/招待费"之类字段。 */
    private static String guessCategory(String title, JsonNode formValues) {
        String combined = (title == null ? "" : title) + " " + collectFormLabels(formValues);
        String s = combined.toLowerCase();
        if (s.contains("差旅") || s.contains("机票") || s.contains("高铁") || s.contains("火车") || s.contains("住宿") || s.contains("出差")) return "TRAVEL";
        if (s.contains("招待") || s.contains("宴请") || s.contains("客户") && s.contains("饭")) return "ENTERTAIN";
        if (s.contains("办公") || s.contains("文具") || s.contains("打印") || s.contains("耗材")) return "OFFICE";
        if (s.contains("出租") || s.contains("滴滴") || s.contains("打车") || s.contains("地铁") || s.contains("交通")) return "TRANSPORT";
        if (s.contains("餐饮") || s.contains("餐费") || s.contains("午餐") || s.contains("聚餐")) return "MEAL";
        if (s.contains("对公") || s.contains("付款") || s.contains("货款")) return "CORP_PAY";
        return "OTHER";
    }

    record ParsedItems(List<ExpenseItem> items, BigDecimal totalAmount, String itemsFingerprint) { }

    /**
     * 解析 form_component_values（钉钉审批表单数组）为消费明细。
     * P6.1 兼容策略：
     * - 识别常见字段名：费用类型 / 费用日期 / 金额 / 消费明细 / 备注 / 发票号 / 票据类型
     * - 如果表单里没有金额字段（可能是金额在标题字段或"金额合计"里），回退为 1 条总明细（总金额 = 各金额字段求和；若仍 0 则总金额 = null）
     */
    private ParsedItems parseItems(JsonNode formValues) {
        List<ExpenseItem> items = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        boolean hasAnyMoney = false;
        StringBuilder fp = new StringBuilder();

        if (formValues.isArray()) {
            // 先检测钉钉"明细表格"组件：value 为 JSON 字符串数组，每行含 rowValue。
            // 钉钉日常报销通常用"报销明细"组件，金额/类型/日期/说明均嵌套在 rowValue 中，
            // 顶层 form_component_values 只有一个 label="报销明细" 的组件，其 value 是整个明细表的 JSON。
            JsonNode detailRows = extractDetailTableRows(formValues);
            if (detailRows != null) {
                // 明细表格模式：每行生成一条 ExpenseItem
                int seq = 0;
                for (JsonNode row : detailRows) {
                    JsonNode rowFields = row.has("rowValue") ? row.get("rowValue") : row;
                    if (!rowFields.isArray() || rowFields.isEmpty()) continue;
                    ExpenseItem it = newItem(++seq);
                    for (JsonNode f : rowFields) {
                        String label = text(f, "name", "label", "component_name");
                        String value = text(f, "value", "component_value", "ext_value");
                        if (label == null) label = "";
                        if (value == null) value = "";
                        fp.append(label).append('=').append(value).append('|');
                        String ll = label.toLowerCase();
                        if (looksLikeAmountField(ll)) {
                            BigDecimal amt = parseMoney(value);
                            if (amt != null && amt.compareTo(BigDecimal.ZERO) != 0) {
                            it.setAmount(amt);
                            it.setItemDesc(truncate(nvl(it.getItemDesc()) + (label + ":" + value + " "), 480));
                            sum = sum.add(amt);
                            hasAnyMoney = true;
                            continue;
                        }
                        }
                        applyOneField(it, ll, label, value);
                    }
                    if (!looksEmpty(it)) items.add(it);
                }
            } else {
                // 扁平表单模式（无明细表格）：按出现顺序聚合，遇金额字段则切分新条目
                int seq = 0;
                ExpenseItem current = newItem(++seq);
                for (JsonNode comp : formValues) {
                    String label = text(comp, "name", "label", "component_name");
                    String value = text(comp, "value", "component_value", "ext_value");
                    if (label == null) label = "";
                    if (value == null) value = "";
                    fp.append(label).append('=').append(value).append('|');
                    String ll = label.toLowerCase();
                    if (looksLikeAmountField(ll)) {
                        BigDecimal amt = parseMoney(value);
                        if (amt != null && amt.compareTo(BigDecimal.ZERO) != 0) {
                            current.setAmount(amt);
                            current.setItemDesc(truncate(nvl(current.getItemDesc()) + (label + ":" + value + " "), 480));
                            sum = sum.add(amt);
                            hasAnyMoney = true;
                            current.setSequenceNo(seq);
                            items.add(current);
                            current = newItem(++seq);
                            continue;
                        }
                    }
                    applyOneField(current, ll, label, value);
                }
                if (!looksEmpty(current)) {
                    current.setSequenceNo(seq);
                    items.add(current);
                }
            }
        }

        if (!hasAnyMoney) {
            // 回退：全部合并成 1 条，金额=null
            items.clear();
            ExpenseItem only = new ExpenseItem();
            only.setSequenceNo(1);
            only.setItemDesc(fp.length() > 0 ? truncate(fp.toString(), 480) : null);
            items.add(only);
        }
        BigDecimal total = hasAnyMoney ? sum : null;
        return new ParsedItems(items, total, sha256(fp.toString()));
    }

    /**
     * 检测钉钉"明细表格"组件并返回其行数组。
     * 明细组件的特征：value 是一个 JSON 字符串，以 [ 开头且包含 "rowValue"。
     * 解析后形如 [{"rowValue":[{label,value,...}, ...]}, ...]
     */
    private JsonNode extractDetailTableRows(JsonNode formValues) {
        for (JsonNode comp : formValues) {
            String value = text(comp, "value", "component_value", "ext_value");
            if (value == null || value.isBlank()) continue;
            String v = value.trim();
            if (v.startsWith("[") && v.contains("rowValue")) {
                try {
                    JsonNode parsed = objectMapper.readTree(v);
                    if (parsed.isArray() && !parsed.isEmpty()) return parsed;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** 将单个表单字段（非金额类）写入 ExpenseItem 的对应属性。 */
    private void applyOneField(ExpenseItem item, String labelLower, String label, String value) {
        if (labelLower.contains("费用类型") || labelLower.contains("类别") || labelLower.contains("消费类型") || labelLower.contains("类型")) {
            item.setExpenseType(truncate(value, 50));
        } else if (labelLower.contains("日期") && !labelLower.contains("截") && !labelLower.contains("截止")) {
            LocalDate d = parseDateLoose(value);
            if (d != null) item.setItemDate(d);
        } else if (labelLower.contains("发票") && (labelLower.contains("号") || labelLower.contains("号码"))) {
            item.setInvoiceNo(truncate(value, 100));
        } else if (labelLower.contains("票据") && labelLower.contains("类型")) {
            item.setReceiptType(truncate(value, 50));
        } else if (labelLower.contains("备注") || labelLower.contains("说明") || labelLower.contains("事由") || labelLower.contains("摘要")) {
            item.setItemDesc(truncate(nvl(item.getItemDesc()) + value + " ", 480));
        } else {
            // 其余字段作为 itemDesc 追加（比如"起终点/行程/出差人"等文字）
            item.setItemDesc(truncate(nvl(item.getItemDesc()) + label + ":" + value + " ", 480));
        }
    }

    private static ExpenseItem newItem(int seq) {
        ExpenseItem it = new ExpenseItem();
        it.setSequenceNo(seq);
        return it;
    }

    private static boolean looksEmpty(ExpenseItem it) {
        return it.getAmount() == null
                && it.getItemDate() == null
                && it.getExpenseType() == null
                && (it.getItemDesc() == null || it.getItemDesc().isBlank())
                && it.getInvoiceNo() == null
                && it.getReceiptType() == null;
    }

    private static String collectFormLabels(JsonNode formValues) {
        if (!formValues.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : formValues) {
            sb.append(text(n, "name", "label", "component_name")).append(' ');
        }
        return sb.toString();
    }

    private static String collectAttachments(JsonNode detail) {
        JsonNode atts = detail.path("attaches");
        if (!atts.isArray() || atts.isEmpty()) return null;
        try { return new ObjectMapper().writeValueAsString(atts); }
        catch (JsonProcessingException e) { return truncate(atts.toString(), 4000); }
    }

    // ===================== 控制器辅助（同步记录转换） =====================

    private ExpenseDtos.SyncRunRow toRunRow(DingtalkSyncRun r) {
        return new ExpenseDtos.SyncRunRow(
                r.getId(), r.getSyncType(), r.getStatus(), r.getTriggeredBy(), r.getTriggeredByName(),
                r.getStartedAt(), r.getFinishedAt(), r.getDurationMs(),
                r.getFetchedCount(), r.getCreatedCount(), r.getUpdatedCount(), r.getUnchangedCount(),
                r.getFailedCount(), r.getMappingMissed(),
                r.getDeptMappingsCreated(), r.getDeptMappingsUpgraded(), r.getErrorSummary(),
                r.getRangeDateFrom(), r.getRangeDateTo()
        );
    }

    // ===================== 通用工具 =====================

    private static String text(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull()) {
                if (v.isTextual() && !v.asText().isBlank()) return v.asText();
                if (v.isNumber()) return v.asText();
                if (v.isBoolean()) return String.valueOf(v.asBoolean());
            }
        }
        return null;
    }

    private static boolean looksLikeAmountField(String labelLower) {
        return (labelLower.contains("金额") || labelLower.contains("费用") && !labelLower.contains("类型")
                || labelLower.contains("合计") || labelLower.contains("总计")
                || labelLower.contains("单价") || labelLower.contains("价格")
                || labelLower.contains("款"))
                && !labelLower.contains("人") && !labelLower.contains("类型") && !labelLower.contains("说明");
    }

    private static BigDecimal parseMoney(String s) {
        if (s == null) return null;
        String clean = s.replaceAll("[¥￥,，\\s]", "").replace("元", "");
        if (clean.isBlank()) return null;
        try { return new BigDecimal(clean); }
        catch (NumberFormatException e) { return null; }
    }

    /** 在给定 JsonNode 下按顺序尝试多个字段名，返回第一个解析成功的金额；都没命中返回 null。 */
    private static BigDecimal money(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull()) {
                if (v.isNumber()) {
                    BigDecimal bd = v.decimalValue();
                    if (bd != null && bd.compareTo(BigDecimal.ZERO) > 0) return bd;
                } else if (v.isTextual()) {
                    BigDecimal bd = parseMoney(v.asText());
                    if (bd != null && bd.compareTo(BigDecimal.ZERO) > 0) return bd;
                }
            }
        }
        return null;
    }

    /** 按分计价的金额字段（amountInCent / amount_in_cent）：命中后 ÷ 100 转元。 */
    private static BigDecimal moneyCents(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull() && v.canConvertToLong()) {
                long cents = v.asLong();
                if (cents > 0) return BigDecimal.valueOf(cents, 2);
            }
        }
        return null;
    }

    /** 返回数组中第一个非空且 >0 的金额；全为空则返回 null。 */
    private static BigDecimal firstMoney(BigDecimal... candidates) {
        if (candidates == null) return null;
        for (BigDecimal c : candidates) {
            if (c != null && c.compareTo(BigDecimal.ZERO) > 0) return c;
        }
        return null;
    }

    private static LocalDate parseDateLoose(String s) {
        if (s == null || s.isBlank()) return null;
        String[] patterns = { "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyy年MM月dd日" };
        for (String p : patterns) {
            try { return LocalDate.parse(s.trim(), DateTimeFormatter.ofPattern(p)); }
            catch (Exception ignored) { }
        }
        return null;
    }

    private static LocalDateTime toDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        // 钉钉返回毫秒时间戳
        if (s.matches("\\d{13}")) {
            try {
                long ms = Long.parseLong(s);
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.of("Asia/Shanghai"));
            } catch (Exception ignored) { }
        }
        // 秒级时间戳
        if (s.matches("\\d{10}")) {
            try {
                long sec = Long.parseLong(s);
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(sec), ZoneId.of("Asia/Shanghai"));
            } catch (Exception ignored) { }
        }
        // 钉钉新版 API 返回 ISO8601 带Z后缀（如 "2024-07-10T09:40Z" 或 "2026-07-25T08:26Z" 分钟精度无秒）
        // 注意：Instant.parse 使用的 ISO_INSTANT 要求秒必须存在，钉钉 v2 常省略秒（"08:26Z"），会解析失败；
        // 改用 OffsetDateTime.parse（ISO_OFFSET_DATE_TIME，秒可选），再转 Instant。
        if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            try {
                return LocalDateTime.ofInstant(
                        java.time.OffsetDateTime.parse(s).toInstant(),
                        ZoneId.of("Asia/Shanghai"));
            } catch (Exception ignored) { }
        }
        // ISO
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (Exception ignored) { }
        // 日期 → 当天 00:00
        LocalDate d = parseDateLoose(s);
        return d != null ? d.atStartOfDay() : null;
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
    private static BigDecimal nullToZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 标准 JDK 必有 SHA-256
            throw new RuntimeException("SHA-256 不可用（JVM 环境异常）", e);
        }
    }
}
