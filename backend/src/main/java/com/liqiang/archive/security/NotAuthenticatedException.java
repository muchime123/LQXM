package com.liqiang.archive.security;

/** 未登录或会话已失效（HTTP 401）。 */
public class NotAuthenticatedException extends RuntimeException {

    public NotAuthenticatedException(String message) {
        super(message);
    }
}
