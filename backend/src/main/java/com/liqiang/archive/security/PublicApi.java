package com.liqiang.archive.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注公开接口：匿名访问者与登录用户均可访问，不参与认证与权限校验。
 *
 * <p>适用场景：官网公开 API、登录/引导接口、公开文件访问等。
 * 可标注在类上（整个 controller 公开）或方法上（仅该方法公开）。
 * 未标注 {@code @PublicApi} 的接口一律要求登录（匿名访问返回 401）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicApi {
}
