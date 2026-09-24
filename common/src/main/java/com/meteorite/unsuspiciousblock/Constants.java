package com.meteorite.unsuspiciousblock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 模组常量 */
public class Constants {
    public static final String MOD_ID = "unsuspiciousblock";
    // 初始化日志统一前缀——日志格式在整合包/崩溃报告中会被裁掉 logger 名，消息自带模组 ID 才可溯源
    public static final String LOG_TAG = "[" + MOD_ID + "] ";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
}
