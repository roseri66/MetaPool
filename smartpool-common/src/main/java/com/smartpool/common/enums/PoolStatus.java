package com.smartpool.common.enums;

/**
 * 资源池运行状态枚举。
 *
 * @since 0.1.0
 */
public enum PoolStatus {

    /** 新建，尚未初始化 */
    NEW,

    /** 正在初始化中 */
    INITIALIZING,

    /** 正常运行 */
    RUNNING,

    /** 已暂停，拒绝新的 acquire 但接受 release */
    PAUSED,

    /** 正在关闭，等待已借出资源归还 */
    SHUTTING_DOWN,

    /** 已销毁，不再接受任何操作 */
    DESTROYED
}
