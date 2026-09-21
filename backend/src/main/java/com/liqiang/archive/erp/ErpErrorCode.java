package com.liqiang.archive.erp;

import org.springframework.http.HttpStatus;

/**
 * ERP 业务错误码（技术方案映射 §5.2 / DA7）。
 *
 * <p>现状 {@code GlobalExceptionHandler} 直接把 HTTP 状态码当业务码用，前端无法区分
 * 「同一个 422」到底是库存不足还是额度超限。本枚举给出可判定的业务语义，
 * 由 {@code ErpBusinessException} 携带，经 GlobalExceptionHandler 输出为
 * {@code data.errorCode}（HTTP 状态码保留在 {@code code}，前端现有判断零改造）。
 *
 * <p>清单来自契约 §8 与字典 §7，实施期按需增补；新增须同步
 * {@code docs/ERP技术方案映射-20260917.md §5.2} 与本枚举，避免两处口径漂移。
 */
public enum ErpErrorCode {

    // ---------- 409 冲突：状态/并发/唯一性 ----------

    /** 状态机不允许该迁移（M12）。 */
    INVALID_STATE(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    /** 乐观锁冲突：行已被他人修改。 */
    STALE_VERSION(HttpStatus.CONFLICT, "数据已被他人修改，请刷新后重试"),
    /** 编码冲突（M02：非空编码终身占用，包含停用对象）。 */
    CODE_CONFLICT(HttpStatus.CONFLICT, "编码已被占用"),
    /** 生效区间重叠（M18：如 sku_conversion 同一产品的换算区间不得交叠）。 */
    EFFECTIVE_RANGE_OVERLAP(HttpStatus.CONFLICT, "生效区间与已有记录重叠"),
    /** 同一请求键、不同请求内容（B14）。 */
    IDEMPOTENCY_PAYLOAD_CONFLICT(HttpStatus.CONFLICT, "同一请求键的内容与首次提交不一致"),

    // ---------- 422 语义错误：业务规则不满足 ----------

    /** 启用校验：必填项未完成（M03）。 */
    REQUIRED_FOR_ACTIVATION(HttpStatus.UNPROCESSABLE_ENTITY, "启用前必填项未完成"),
    /** 引用的主数据已停用（M03）。 */
    REFERENCE_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "引用的主数据已停用"),
    /** 归属/货权冲突（M14：不得以共享主数据推导跨公司授权）。 */
    OWNERSHIP_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "归属或货权校验未通过"),
    /** 无换算关系，阻断过账（蓝图 §7）。 */
    UNIT_CONVERSION_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "缺少单位换算关系，无法过账"),
    /** 换算关系缺失（字典 §4 的口径错误码）。 */
    CONVERSION_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "缺少换算关系"),
    /** 执行量超过来源单据可执行数量（B06）。 */
    SOURCE_QUANTITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "超过来源单据可执行数量"),
    /** 核销超过未结额度（B11）。 */
    ALLOCATION_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "核销金额超过未结额度"),
    /** 退款超过可退额度（B13）。 */
    REFUND_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "退款金额超过可退额度"),
    /** 可用库存不足（T06：禁负库存）。 */
    INSUFFICIENT_AVAILABLE_STOCK(HttpStatus.UNPROCESSABLE_ENTITY, "可用库存不足"),
    /** 存在下游依赖，不可改/不可撤（B08）。 */
    DOWNSTREAM_DEPENDENCY(HttpStatus.UNPROCESSABLE_ENTITY, "存在下游单据，不可修改或撤销"),
    /** 期间已关闭（D08 期间控制）。 */
    PERIOD_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "该期间已关闭，请走冲销后在下一期间重做"),

    // ---------- 404 / 400 ----------

    /** 对象不存在。 */
    NOT_FOUND(HttpStatus.NOT_FOUND, "对象不存在"),
    /** 参数不合法（结构正确但取值非法）。 */
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "参数不合法");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErpErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int httpStatusCode() {
        return httpStatus.value();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
