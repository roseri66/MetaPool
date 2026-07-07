package com.metapool.spi.alert;

import com.metapool.common.spi.SPI;

/**
 * 告警渠道 SPI 接口。
 *
 * <p>接收告警消息并分发到对应的通知渠道（如钉钉、企业微信、飞书、邮件等）。
 * 实现方负责具体渠道的消息格式化和推送逻辑。
 *
 * <h3>线程安全</h3>
 * <p>实现类必须保证 {@link #send(AlertMessage)} 方法的线程安全，
 * 允许多个线程并发调用，不因单个渠道发送失败影响其他消息的投递。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AlertChannel channel = ExtensionLoader.getExtension(AlertChannel.class);
 * if (channel != null) {
 *     AlertMessage msg = AlertMessage.builder()
 *         .severity(AlertSeverity.CRITICAL)
 *         .title("连接池耗尽")
 *         .content("数据库连接池活跃连接数已达到上限")
 *         .timestamp(System.currentTimeMillis())
 *         .sourcePool("metapool-db")
 *         .build();
 *     channel.send(msg);
 * }
 * }</pre>
 *
 * @see AlertMessage
 * @see AlertSeverity
 * @since 0.1.0
 */
@SPI
public interface AlertChannel {

    /**
     * 发送告警消息。
     *
     * <p>实现方应根据 {@link AlertMessage#getSeverity()} 决定通知的紧急程度，
     * 根据 {@link AlertMessage#getSourcePool()} 定位告警来源，将消息格式化后
     * 推送到对应渠道（钉钉 Webhook / 企业微信机器人 / 邮件 SMTP 等）。
     *
     * <p>调用方应对 {@code this} 做判空兜底——
     * 当 {@link com.metapool.common.spi.ExtensionLoader} 未找到实现且无默认实现时返回 {@code null}。
     *
     * @param message 告警消息，不能为 {@code null}
     */
    void send(AlertMessage message);
}
