package com.liqiang.archive.erp;

import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把现有身份上下文（{@link CurrentUserService}）解析成 ERP 契约要求的 {@link ErpActor}。
 *
 * <p>存在的理由：M0 把「操作人」收敛成不可变对象后，每个写路径都要自己去构造它，
 * 于是「未登录时怎么填」就会被各自发挥——典型退化是传 {@code 0} 或 {@code "系统"}。
 * 本类把这一步收敛成一个入口：**解析不出真实身份就直接拒绝**，不产生占位操作人。
 *
 * <p>与 {@code RequirePermission} 拦截器的分工：拦截器管「有没有这个功能码」，
 * 本类管「这条审计行算在谁头上」。两者都要过——拦截器放行不等于操作人可解析
 * （例如开发环境的 {@code X-Employee-Id} 降级路径与生产 Bearer 路径的差别就落在这里）。
 */
@Component
@RequiredArgsConstructor
public class ErpActorResolver {

    private final CurrentUserService users;

    /**
     * 当前请求的操作人。未登录、员工 id 缺失或姓名缺失都会抛错——
     * 宁可让写动作失败，也不要留下一条查不到责任人的审计行。
     */
    public ErpActor current() {
        var user = users.current();
        if (user.employeeId() == null || user.employeeId() <= 0) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "缺少操作人身份：写动作不接受匿名提交，请先登录", "actorId");
        }
        if (user.name() == null || user.name().isBlank()) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "缺少操作人姓名：审计与幂等都需要可追溯的责任人，不能用占位身份", "actorName");
        }
        return new ErpActor(user.employeeId(), user.name());
    }
}
