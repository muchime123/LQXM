package com.liqiang.archive.security;
import java.lang.annotation.*;
@Target({ElementType.TYPE,ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission { String[] value(); boolean any() default true; }
