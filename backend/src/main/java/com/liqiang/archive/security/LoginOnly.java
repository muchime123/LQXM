package com.liqiang.archive.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注「仅要求登录、不校验具体权限」的接口。
 *
 * <p>适用场景：任何登录用户均可执行的写操作——退出登录、文件上传、资产借用/归还/流转、
 * 视频收藏/浏览/自建合集等个人工作台操作。与 {@link RequirePermission} 的区别是不校验权限码；
 * 与 {@link PublicApi} 的区别是不允许匿名访问。可标注在类上（整个 controller）或方法上。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoginOnly {
}
