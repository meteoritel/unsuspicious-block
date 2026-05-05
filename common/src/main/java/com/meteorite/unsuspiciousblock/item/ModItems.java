package com.meteorite.unsuspiciousblock.item;

import net.minecraft.world.item.Item;

/** 模组物品——物品实例由各平台分别创建注册 */
public class ModItems {
    // 由平台模块在注册阶段赋值
    public static SuspiciousReaderItem SUSPICIOUS_READER;
    public static LuoyangSpadeItem LUOYANG_SPADE;
    public static ArchaeologyJournalItem ARCHAEOLOGY_JOURNAL;
    //供平台模块通过 Supplier/Registry.register 调用
    // 创建可疑解析仪实例
    public static SuspiciousReaderItem createSuspiciousReader() {
        return new SuspiciousReaderItem(new Item.Properties().stacksTo(1));
    }

    // 创建洛阳铲实例
    public static LuoyangSpadeItem createLuoyangSpade() {
        return new LuoyangSpadeItem(new Item.Properties().stacksTo(1));
    }

    // 创建考古笔记实例
    public static ArchaeologyJournalItem createArchaeologyJournal() {
        return new ArchaeologyJournalItem(new Item.Properties().stacksTo(1));
    }
}
