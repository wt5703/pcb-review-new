package com.leapmotor.pcbreview.notification.domain;

/**
 * @author 王涛
 * @date 2026-09-18
 * @description 表示一次邮件 Mock 投递尝试的结果，用于区分发送成功、可重试失败和不应影响业务主事务的通知异常。
 */
public enum NotificationDeliveryStatus {
    SUCCESS,
    FAILED
}
