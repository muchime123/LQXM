package com.liqiang.archive.erp;

/**
 * ERP 动作发起人（M0 收口：操作人的唯一构造入口）。
 *
 * <p>为什么需要它：`idem_request.created_by` 与 `row_change_log.operator_id/operator_name`
 * 在 M0 落地时都没有写入路径——列建了，没人填。审计接受可空操作人等于「谁改的」无法回答，
 * 与 DA7「记操作人，修 audit_logs 缺陷」的立项理由直接冲突。本类把「发起人」收敛成一个
 * 不可变对象，任何写动作都必须先构造它，从类型上消除「忘了传操作人」这一分支。
 *
 * <p>为什么不给 {@code system()} 之类的空操作人：定时任务、对账任务同样是业务写入，
 * 必须指定一个有据可查的责任人员工 id（责任人可以是系统管理员），不能用「0 / 系统」蒙混。
 * 若将来确需系统身份，应新增显式的 {@code actorType} 列而不是复用 employeeId=0。
 *
 * <p>{@code name} 是**姓名快照**：人员改名或离职后，历史审计仍显示当时姓名，
 * 与字典 §5「离职撤销访问，但历史单据中的员工 ID 和姓名快照保留」一致。
 *
 * @param employeeId 员工 id（{@code employee} 主键），必须为正数
 * @param name       提交时的姓名快照，非空、去首尾空白、不超过 100 字符
 */
public record ErpActor(long employeeId, String name) {

    private static final int MAX_NAME_LENGTH = 100;

    public ErpActor {
        if (employeeId <= 0) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "操作人员工 id 必须为正数，缺省或占位值不被接受", "actorId");
        }
        if (name == null || name.isBlank()) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "操作人姓名快照不能为空", "actorName");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "操作人姓名不能含控制字符，且长度不超过 " + MAX_NAME_LENGTH, "actorName");
        }
    }
}
