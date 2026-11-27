package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.bootstrap.Registrar;

import com.meteorite.unsuspiciousblock.core.block.ModBlocks;
import com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.Set;
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
    public static Item SPARK_DUST;
    public static RewindDustItem REWIND_DUST;
    public static EyeOfCatItem EYE_OF_CAT;
    public static HandOfCatItem HAND_OF_CAT;
    public static PanItem COPPER_PAN;
    public static PanItem GOLD_PAN;
    public static PanItem OBSIDIAN_PAN;
    public static BlockItem UNSUSPICIOUS_SAND;
    public static BlockItem UNSUSPICIOUS_GRAVEL;
    public static BlockItem POTTERY_WHEEL;
    public static UnfiredDecoratedPotItem UNFIRED_DECORATED_POT;
    public static UnfiredDecoratedSherdItem UNFIRED_DECORATED_SHERD;
    public static BlockItem PURRMINT;
    public static ItemNameBlockItem PURRMINT_SEEDS;
    public static PurrmintPotionItem PURRMINT_POTION;
    public static SplashPurrmintPotionItem SPLASH_PURRMINT_POTION;
    public static LingeringPurrmintPotionItem LINGERING_PURRMINT_POTION;
    public static HalfCarrotItem HALF_CARROT_TOP;
    public static HalfCarrotItem HALF_CARROT_BOTTOM;
    public static BarelyGluedCarrotItem BARELY_GLUED_CARROT;

    // 通用标签的命名空间——由 Fabric API 与 NeoForge 各自的数据包提供并填充原版物品
    private static final String COMMON_TAG_NAMESPACE = "c";

    /** 物品分类用于派生客户端模型属性注册视图。 */
    public enum Category { STANDARD, PAN }

    /** getter 只在平台注册完成后求值；创造栏顺序为空的条目不加入创造栏。 */
    public record ItemEntry(String name, Supplier<Item> factory, Consumer<Item> setter,
                            Supplier<Item> item, @Nullable Integer creativeOrder, Category category) {}


    // 物品注册清单——新增物品只需在此添加一行
    public static final List<ItemEntry> REGISTRY_MANIFEST = List.of(
            new ItemEntry("unsuspicious_sand",
                    ModItems::createUnsuspiciousSand,
                    item -> UNSUSPICIOUS_SAND = (BlockItem) item,
                    () -> UNSUSPICIOUS_SAND, 1, Category.STANDARD),
            new ItemEntry("unsuspicious_gravel",
                    ModItems::createUnsuspiciousGravel,
                    item -> UNSUSPICIOUS_GRAVEL = (BlockItem) item,
                    () -> UNSUSPICIOUS_GRAVEL, 2, Category.STANDARD),
            new ItemEntry("pottery_wheel",
                    ModItems::createPotteryWheel,
                    item -> POTTERY_WHEEL = (BlockItem) item,
                    () -> POTTERY_WHEEL, 3, Category.STANDARD),
            new ItemEntry("unfired_decorated_pot",
                    ModItems::createUnfiredDecoratedPot,
                    item -> UNFIRED_DECORATED_POT = (UnfiredDecoratedPotItem) item,
                    () -> UNFIRED_DECORATED_POT, 4, Category.STANDARD),
            new ItemEntry("unfired_decorated_sherd",
                    ModItems::createUnfiredDecoratedSherd,
                    item -> UNFIRED_DECORATED_SHERD = (UnfiredDecoratedSherdItem) item,
                    () -> UNFIRED_DECORATED_SHERD, 5, Category.STANDARD),
            new ItemEntry("suspicious_reader",
                    ModItems::createSuspiciousReader,
                    item -> SUSPICIOUS_READER = (SuspiciousReaderItem) item,
                    () -> SUSPICIOUS_READER, 6, Category.STANDARD),
            new ItemEntry("archaeological_shovel",
                    ModItems::createArchaeologicalShovel,
                    item -> ARCHAEOLOGICAL_SHOVEL = (ArchaeologicalShovelItem) item,
                    () -> ARCHAEOLOGICAL_SHOVEL, 7, Category.STANDARD),
            new ItemEntry("archaeology_journal",
                    ModItems::createArchaeologyJournal,
                    item -> ARCHAEOLOGY_JOURNAL = (ArchaeologyJournalItem) item,
                    () -> ARCHAEOLOGY_JOURNAL, 0, Category.STANDARD),
            new ItemEntry("specimen_box",
                    ModItems::createSpecimenBox,
                    item -> SPECIMEN_BOX = (SpecimenBoxItem) item,
                    () -> SPECIMEN_BOX, 17, Category.STANDARD),
            new ItemEntry("ancient_coin",
                    ModItems::createAncientCoin,
                    item -> ANCIENT_COIN = item,
                    () -> ANCIENT_COIN, 11, Category.STANDARD),
            new ItemEntry("lost_page",
                    ModItems::createLostPage,
                    item -> LOST_PAGE = item,
                    () -> LOST_PAGE, 12, Category.STANDARD),
            new ItemEntry("base_page",
                    ModItems::createBasePage,
                    item -> BASE_PAGE = item,
                    () -> BASE_PAGE, 13, Category.STANDARD),
            new ItemEntry("spark_dust",
                    ModItems::createSparkDust,
                    item -> SPARK_DUST = item,
                    () -> SPARK_DUST, 14, Category.STANDARD),
            new ItemEntry("rewind_dust",
                    ModItems::createRewindDust,
                    item -> REWIND_DUST = (RewindDustItem) item,
                    () -> REWIND_DUST, 15, Category.STANDARD),
            new ItemEntry("eye_of_cat",
                    ModItems::createEyeOfCat,
                    item -> EYE_OF_CAT = (EyeOfCatItem) item,
                    () -> EYE_OF_CAT, 16, Category.STANDARD),
            new ItemEntry("hand_of_cat",
                    ModItems::createHandOfCat,
                    item -> HAND_OF_CAT = (HandOfCatItem) item,
                    () -> HAND_OF_CAT, 18, Category.STANDARD),
            new ItemEntry("purrmint",
                    ModItems::createPurrmint,
                    item -> PURRMINT = (BlockItem) item,
                    () -> PURRMINT, 19, Category.STANDARD),
            new ItemEntry("purrmint_seeds",
                    ModItems::createPurrmintSeeds,
                    item -> PURRMINT_SEEDS = (ItemNameBlockItem) item,
                    () -> PURRMINT_SEEDS, 20, Category.STANDARD),
            new ItemEntry("purrmint_potion",
                    ModItems::createPurrmintPotion,
                    item -> PURRMINT_POTION = (PurrmintPotionItem) item,
                    () -> PURRMINT_POTION, null, Category.STANDARD),
            new ItemEntry("splash_purrmint_potion",
                    ModItems::createSplashPurrmintPotion,
                    item -> SPLASH_PURRMINT_POTION = (SplashPurrmintPotionItem) item,
                    () -> SPLASH_PURRMINT_POTION, null, Category.STANDARD),
            new ItemEntry("lingering_purrmint_potion",
                    ModItems::createLingeringPurrmintPotion,
                    item -> LINGERING_PURRMINT_POTION = (LingeringPurrmintPotionItem) item,
                    () -> LINGERING_PURRMINT_POTION, null, Category.STANDARD),
            new ItemEntry("copper_pan",
                    ModItems::createCopperPan,
                    item -> COPPER_PAN = (PanItem) item,
                    () -> COPPER_PAN, 8, Category.PAN),
            new ItemEntry("gold_pan",
                    ModItems::createGoldPan,
                    item -> GOLD_PAN = (PanItem) item,
                    () -> GOLD_PAN, 9, Category.PAN),
            new ItemEntry("obsidian_pan",
                    ModItems::createObsidianPan,
                    item -> OBSIDIAN_PAN = (PanItem) item,
                    () -> OBSIDIAN_PAN, 10, Category.PAN),
            new ItemEntry("half_carrot_top",
                    ModItems::createHalfCarrotTop,
                    item -> HALF_CARROT_TOP = (HalfCarrotItem) item,
                    () -> HALF_CARROT_TOP, null, Category.STANDARD),
            new ItemEntry("half_carrot_bottom",
                    ModItems::createHalfCarrotBottom,
                    item -> HALF_CARROT_BOTTOM = (HalfCarrotItem) item,
                    () -> HALF_CARROT_BOTTOM, null, Category.STANDARD),
            new ItemEntry("barely_glued_carrot",
                    ModItems::createBarelyGluedCarrot,
                    item -> BARELY_GLUED_CARROT = (BarelyGluedCarrotItem) item,
                    () -> BARELY_GLUED_CARROT, null, Category.STANDARD)
    );

    // 创造模式物品栏图标 —— 考古笔记
    public static final Supplier<ItemStack> CREATIVE_TAB_ICON =
            () -> new ItemStack(ARCHAEOLOGY_JOURNAL);

    /** 创造栏视图按声明排序，延迟读取已注册物品。 */
    public static final List<Supplier<Item>> CREATIVE_TAB_ITEMS = creativeTabItems();

    /** 全部淘盘的模型属性注册视图。 */
    public static final List<Supplier<Item>> PAN_ITEMS = REGISTRY_MANIFEST.stream()
            .filter(entry -> entry.category() == Category.PAN).map(ItemEntry::item).toList();

    private static List<Supplier<Item>> creativeTabItems() {
        Set<Integer> orders = new HashSet<>();
        for (ItemEntry entry : REGISTRY_MANIFEST) {
            if (entry.creativeOrder() != null && !orders.add(entry.creativeOrder())) {
                throw new IllegalStateException("重复的创造栏顺序: " + entry.creativeOrder());
            }
        }
        return REGISTRY_MANIFEST.stream().filter(entry -> entry.creativeOrder() != null)
                .sorted(Comparator.comparing(ItemEntry::creativeOrder)).map(ItemEntry::item).toList();
    }

    // ========== 供平台模块通过 Supplier/Registry.register 调用 ============ //
    // 创建不可疑的沙子方块物品
    public static BlockItem createUnsuspiciousSand() {
        return new UnsuspiciousBlockItem(ModBlocks.UNSUSPICIOUS_SAND.get(), new Item.Properties()
                .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
    }

    // 创建不可疑的沙砾方块物品
    public static BlockItem createUnsuspiciousGravel() {
        return new UnsuspiciousBlockItem(ModBlocks.UNSUSPICIOUS_GRAVEL.get(), new Item.Properties()
                .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
    }

    // 创建纹饰陶轮台物品
    public static BlockItem createPotteryWheel() {
        return new BlockItem(ModBlocks.POTTERY_WHEEL.get(), new Item.Properties());
    }

    // 创建可携带四面纹饰组件的未烧制陶罐方块物品
    public static UnfiredDecoratedPotItem createUnfiredDecoratedPot() {
        return new UnfiredDecoratedPotItem(new Item.Properties().stacksTo(16));
    }

    // 创建可保存纹饰数据的未烧制陶片
    public static UnfiredDecoratedSherdItem createUnfiredDecoratedSherd() {
        return new UnfiredDecoratedSherdItem(new Item.Properties().stacksTo(16));
    }

    // 创建可疑解析仪实例
    public static SuspiciousReaderItem createSuspiciousReader() {
        return new SuspiciousReaderItem(new Item.Properties().stacksTo(1));
    }

    // 创建考古铲实例
    public static ArchaeologicalShovelItem createArchaeologicalShovel() {
        return new ArchaeologicalShovelItem(new Item.Properties().stacksTo(1));
    }

    // 创建铜淘盘实例——水域专属，32 点耐久，可在铁砧上用铜锭修复，无再生效果与幸运加成
    public static PanItem createCopperPan() {
        return new PanItem(new Item.Properties().stacksTo(1).durability(32), new PanProfile(
                Set.of(ShimmerVariants.WATER_ID),
                commonItemTag("ingots/copper"),
                Map.of(),
                () -> 0.0D,
                List.of()));
    }

    // 创建金淘盘实例——水域专属，耐久 24，可在铁砧上用金锭修复；
    // 幸运与每次自然点淘洗的奖励概率由服务端配置提供。
    public static PanItem createGoldPan() {
        return new PanItem(new Item.Properties().stacksTo(1).durability(24), new PanProfile(
                Set.of(ShimmerVariants.WATER_ID),
                commonItemTag("ingots/gold"),
                Map.of(ShimmerVariants.WATER_ID, Services.PANNING_CONFIG::getGoldPanLuckBonus),
                Services.PANNING_CONFIG::getGoldPanRegenerationChance,
                List.of(new PanProfile.QualityNote(
                        "item.unsuspiciousblock.gold_pan.tooltip.quality",
                        PanProfile.NoteTone.POSITIVE))));
    }

    // 创建黑曜石淘盘实例——耐久 64，可在铁砧上用黑曜石修复；
    // 既能采水点也能采幽微的光，淘水时按配置扣减幸运，因此水中产出明显低于铜盘
    public static PanItem createObsidianPan() {
        return new PanItem(new Item.Properties().stacksTo(1).durability(64), new PanProfile(
                Set.of(ShimmerVariants.WATER_ID, ShimmerVariants.GLIMMER_ID),
                commonItemTag("obsidians/normal"),
                Map.of(ShimmerVariants.WATER_ID,
                        () -> -Services.PANNING_CONFIG.getObsidianPanLuckPenalty()),
                () -> 0.0D,
                List.of(
                        new PanProfile.QualityNote(
                                "item.unsuspiciousblock.obsidian_pan.tooltip.nether",
                                PanProfile.NoteTone.ACCENT),
                        new PanProfile.QualityNote(
                                "item.unsuspiciousblock.obsidian_pan.tooltip.quality",
                                PanProfile.NoteTone.NEGATIVE))));
    }

    // 铁砧修复材料统一引用 c: 通用标签，两端数据包均会填充原版物品
    private static TagKey<Item> commonItemTag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(COMMON_TAG_NAMESPACE, path));
    }

    // 创建考古笔记实例
    public static ArchaeologyJournalItem createArchaeologyJournal() {
        return new ArchaeologyJournalItem(new Item.Properties().stacksTo(1));
    }

    // 创建标本箱实例--默认 5 格容器组件
    public static SpecimenBoxItem createSpecimenBox() {
        return new SpecimenBoxItem(new Item.Properties()
                .stacksTo(1)
                .component(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
    }

    // 创建古代金币实例
    public static Item createAncientCoin() {
        return new DescribedItem(new Item.Properties(),
                "item.unsuspiciousblock.ancient_coin.tooltip.desc");
    }

    // 创建失落书页实例
    public static Item createLostPage() {
        return new DescribedItem(new Item.Properties(),
                "item.unsuspiciousblock.lost_page.tooltip.desc");
    }

    // 创建书页基底实例
    public static Item createBasePage() {
        return new DescribedItem(new Item.Properties(),
                "item.unsuspiciousblock.base_page.tooltip.desc");
    }

    // 创建花火粉实例——火把花研磨的燃料粉末，也可与木棍合成火把
    public static Item createSparkDust() {
        return new DescribedItem(new Item.Properties(),
                "item.unsuspiciousblock.spark_dust.tooltip.desc");
    }

    // 创建回溯粉实例——对世界生成结构右键原位重生成，逻辑见 world/StructureRewindService
    public static RewindDustItem createRewindDust() {
        return new RewindDustItem(new Item.Properties(),
                "item.unsuspiciousblock.rewind_dust.tooltip.desc");
    }

    // 创建猫之瞳实例——持有者开启附魔台时可窥见完整附魔候选
    public static EyeOfCatItem createEyeOfCat() {
        return new EyeOfCatItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
    }

    // 创建猫之手实例
    public static HandOfCatItem createHandOfCat() {
        return new HandOfCatItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    /** 创建成熟植株的可放置物品。 */
    public static BlockItem createPurrmint() {
        return new BlockItem(ModBlocks.PURRMINT.get(), new Item.Properties());
    }

    /** 创建使用独立物品名称的幼苗种子。 */
    public static ItemNameBlockItem createPurrmintSeeds() {
        return new ItemNameBlockItem(ModBlocks.PURRMINT_CROP.get(), new Item.Properties());
    }

    /** 创建饮用型灵猫药水实例。 */
    public static PurrmintPotionItem createPurrmintPotion() {
        return new PurrmintPotionItem(new Item.Properties().stacksTo(1));
    }

    /** 创建喷溅型灵猫药水实例。 */
    public static SplashPurrmintPotionItem createSplashPurrmintPotion() {
        return new SplashPurrmintPotionItem(new Item.Properties().stacksTo(1));
    }

    /** 创建滞留型灵猫药水实例。 */
    public static LingeringPurrmintPotionItem createLingeringPurrmintPotion() {
        return new LingeringPurrmintPotionItem(new Item.Properties().stacksTo(1));
    }

    /** 创建半个胡萝卜（上半）实例。 */
    public static HalfCarrotItem createHalfCarrotTop() {
        return new HalfCarrotItem(
                new Item.Properties().food(HalfCarrotItem.FOOD_PROPERTIES),
                "item.unsuspiciousblock.half_carrot_top.tooltip.desc");
    }

    /** 创建半个胡萝卜（下半）实例。 */
    public static HalfCarrotItem createHalfCarrotBottom() {
        return new HalfCarrotItem(
                new Item.Properties().food(HalfCarrotItem.FOOD_PROPERTIES),
                "item.unsuspiciousblock.half_carrot_bottom.tooltip.desc");
    }

    /** 创建勉强粘好的胡萝卜实例。 */
    public static BarelyGluedCarrotItem createBarelyGluedCarrot() {
        return new BarelyGluedCarrotItem(
                new Item.Properties().rarity(Rarity.UNCOMMON).food(BarelyGluedCarrotItem.FOOD_PROPERTIES));
    }

    // 遍历清单，调用平台回调完成注册
    public static void forEach(Registrar<Item, Item> registrar) {
        for (ItemEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.factory(), entry.setter());
        }
    }
}
