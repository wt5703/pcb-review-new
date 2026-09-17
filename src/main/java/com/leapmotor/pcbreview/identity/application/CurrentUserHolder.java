package com.leapmotor.pcbreview.identity.application;

/**
 * @author 王涛
 * @date 2026-09-10
 * @description 基于线程本地变量保存当前请求用户上下文，并在请求结束后清理，避免不同请求之间发生身份信息串扰。
 */


public final class CurrentUserHolder {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(CurrentUser currentUser) { HOLDER.set(currentUser); }
    public static CurrentUser require() {
        CurrentUser currentUser = HOLDER.get();
        if (currentUser == null) throw new IllegalStateException("缺少当前用户");
        return currentUser;
    }
    public static void clear() { HOLDER.remove(); }
}
