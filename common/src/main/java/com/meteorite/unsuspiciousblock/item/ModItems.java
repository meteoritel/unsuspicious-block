package com.meteorite.unsuspiciousblock.item;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 模组物品——物品实例由各平台分别创建注册 */
public class ModItems {
    // 由平台模块在注册阶段赋值
    public static SuspiciousReaderItem SUSPICIOUS_READER;
    public static LuoyangSpadeItem LUOYANG_SPADE;
    public static ArchaeologyJournalItem ARCHAEOLOGY_JOURNAL;
    public static Item ANCIENT_COIN;
    public static Item LOST_PAGE;
    public static Item PAGE_BASE;

    // 物品注册清单条目，供各平台遍历注册
    public record ItemEntry(String name, Supplier<Item> factory, Consumer<Item> setter) {}

    // 物品注册清单——新增物品只需在此添加一行
    public static final List<ItemEntry> REGISTRY_MANIFEST = List.of(
            new ItemEntry("suspicious_reader",
                    ModItems::createSuspiciousReader,
                    item -> SUSPICIOUS_READER = (SuspiciousReaderItem) item),
            new ItemEntry("luoyang_spade",
                    ModItems::createLuoyangSpade,
                    item -> LUOYANG_SPADE = (LuoyangSpadeItem) item),
            new ItemEntry("archaeology_journal",
                    ModItems::createArchaeologyJournal,
                    item -> ARCHAEOLOGY_JOURNAL = (ArchaeologyJournalItem) item),
            new ItemEntry("ancient_coin",
                    ModItems::createAncientCoin,
                    item -> ANCIENT_COIN = item),
            new ItemEntry("lost_page",
                    ModItems::createLostPage,
                    item -> LOST_PAGE = item),
            new ItemEntry("page_base",
                    ModItems::createPageBase,
                    item -> PAGE_BASE = item)
    );

    // ========== 供平台模块通过 Supplier/Registry.register 调用 ============ //
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

    // 创建古代金币实例
    public static Item createAncientCoin() {
        return new Item(new Item.Properties());
    }

    // 创建失落书页实例
    public static Item createLostPage() {
        return new Item(new Item.Properties());
    }

    // 创建书页基底实例
    public static Item createPageBase() {
        return new Item(new Item.Properties());
    }
}
