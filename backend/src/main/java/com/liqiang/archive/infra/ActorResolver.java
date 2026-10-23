package com.liqiang.archive.infra;

import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把现有身份上下文（{@link CurrentUserService}）解析成系统通用的 {@link Actor}。
 *
 * <p>每个需要操作人的写路径都从这里取当前操作人，收敛成一个入口：
 * **解析不出真实身份就直接拒绝**，不产生占位操作人（{@code 0} / {@code "系统"}）。
 *
 * <p>与 {@code RequirePermission} 拦截器的分工：拦截器管「有没有这个功能码」，
 * 本类管「这条写路径算在谁头上」。两者都要过——拦截器放行不等于操作人可解析
 * （例如开发环境的 {@code X-Employee-Id} 降级路径与生产 Bearer 路径的差别就落在这里）。
 *
 * <p>2026-10-23：随 ERP 独立 schema 下线，由 {@code ErpActorResolver} 收敛为系统通用操作人解析。
 */
@Component
@RequiredArgsConstructor
public class ActorResolver {

    private final CurrentUserService users;

    /**
     * 当前请求的操作人。未登录、员工 id 缺失或姓名缺失都会抛错——
     * 宁可让写动作失败，也不要留下一条查不到责任人的记录。
     */
    public Actor current() {
        var user = users.current();
        if (user.employeeId() == null || user.employeeId() <= 0) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT,
                    "缺少操作人身份：写动作不接受匿名提交，请先登录", "actorId");
        }
        if (user.name() == null || user.name().isBlank()) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT,
                    "缺少操作人姓名：幂等与审计都需要可追溯的责任人，不能用占位身份", "actorName");
        }
        return new Actor(user.employeeId(), user.name());
    }
}
