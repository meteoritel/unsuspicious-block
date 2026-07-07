package com.meteorite.unsuspiciousblock.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 模组物品——物品实例由各平台分别创建注册 */
public class ModItems {
    // 由平台模块在注册阶段赋值
    public static SuspiciousReaderItem SUSPICIOUS_READER;
    public static ArchaeologicalShovelItem ARCHAEOLOGICAL_SHOVEL;
    public static ArchaeologyJournalItem ARCHAEOLOGY_JOURNAL;
    public static SpecimenBoxItem SPECIMEN_BOX;
    public static Item ANCIENT_COIN;
    public static Item LOST_PAGE;
    public static Item BASE_PAGE;
    public static EyeOfCatItem EYE_OF_CAT;
    public static HandOfCatItem HAND_OF_CAT;

    // 物品注册清单条目，供各平台遍历注册
    public record ItemEntry(String name, Supplier<Item> factory, Consumer<Item> setter) {}

    // 物品注册清单——新增物品只需在此添加一行
    public static final List<ItemEntry> REGISTRY_MANIFEST = List.of(
            new ItemEntry("suspicious_reader",
                    ModItems::createSuspiciousReader,
                    item -> SUSPICIOUS_READER = (SuspiciousReaderItem) item),
            new ItemEntry("archaeological_shovel",
                    ModItems::createArchaeologicalShovel,
                    item -> ARCHAEOLOGICAL_SHOVEL = (ArchaeologicalShovelItem) item),
            new ItemEntry("archaeology_journal",
                    ModItems::createArchaeologyJournal,
                    item -> ARCHAEOLOGY_JOURNAL = (ArchaeologyJournalItem) item),
            new ItemEntry("specimen_box",
                    ModItems::createSpecimenBox,
                    item -> SPECIMEN_BOX = (SpecimenBoxItem) item),
            new ItemEntry("ancient_coin",
                    ModItems::createAncientCoin,
                    item -> ANCIENT_COIN = item),
            new ItemEntry("lost_page",
                    ModItems::createLostPage,
                    item -> LOST_PAGE = item),
            new ItemEntry("base_page",
                    ModItems::createBasePage,
                    item -> BASE_PAGE = item),
            new ItemEntry("eye_of_cat",
                    ModItems::createEyeOfCat,
                    item -> EYE_OF_CAT = (EyeOfCatItem) item),
            new ItemEntry("hand_of_cat",
                    ModItems::createHandOfCat,
                    item -> HAND_OF_CAT = (HandOfCatItem) item)
    );

    // 创造模式物品栏图标 —— 考古笔记
    public static final Supplier<ItemStack> CREATIVE_TAB_ICON =
            () -> new ItemStack(ARCHAEOLOGY_JOURNAL);

    // 创造模式物品栏中展示的物品（使用 Supplier 延迟求值，因为静态字段在注册后才被赋值）
    public static final List<Supplier<Item>> CREATIVE_TAB_ITEMS = List.of(
            () -> ARCHAEOLOGY_JOURNAL,
            () -> SUSPICIOUS_READER,
            () -> ARCHAEOLOGICAL_SHOVEL,
            () -> ANCIENT_COIN,
            () -> LOST_PAGE,
            () -> BASE_PAGE,
            () -> EYE_OF_CAT,
            // () -> SPECIMEN_BOX,
            () -> HAND_OF_CAT
    );

    // ========== 供平台模块通过 Supplier/Registry.register 调用 ============ //
    // 创建可疑解析仪实例
    public static SuspiciousReaderItem createSuspiciousReader() {
        return new SuspiciousReaderItem(new Item.Properties().stacksTo(1));
    }

    // 创建考古铲实例
    public static ArchaeologicalShovelItem createArchaeologicalShovel() {
        return new ArchaeologicalShovelItem(new Item.Properties().stacksTo(1));
    }

    // 创建考古笔记实例
    public static ArchaeologyJournalItem createArchaeologyJournal() {
        return new ArchaeologyJournalItem(new Item.Properties().stacksTo(1));
    }

    // 创建标本箱实例
    public static SpecimenBoxItem createSpecimenBox() {
        return new SpecimenBoxItem(new Item.Properties().stacksTo(1));
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
    public static Item createBasePage() {
        return new Item(new Item.Properties());
    }

    // 创建猫之瞳实例——持有者开启附魔台时可窥见完整附魔候选
    public static EyeOfCatItem createEyeOfCat() {
        return new EyeOfCatItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
    }

    // 创建猫之手实例
    public static HandOfCatItem createHandOfCat() {
        return new HandOfCatItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    // 遍历清单，调用平台回调完成注册
    public static void forEach(ItemRegistrar registrar) {
        for (ItemEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }
}
