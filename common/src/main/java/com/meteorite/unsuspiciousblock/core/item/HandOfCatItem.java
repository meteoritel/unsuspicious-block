package com.meteorite.unsuspiciousblock.core.item;

import com.meteorite.unsuspiciousblock.core.cat.CatFavorAbility;
import com.meteorite.unsuspiciousblock.core.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.core.text.ClientTooltipBridge;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 猫之手——猫国授予玩家的身份信物。空白信物进入个人携带范围后绑定玩家 UUID，
 * 绑定后的信物只为原主人显化猫族羁绊与阶段信息。
 */
public class HandOfCatItem extends Item {
    private static final String OWNER_UUID_TAG = "owner_uuid";
    private static final String OWNER_NAME_TAG = "owner_name";

    public HandOfCatItem(Properties properties) {
        super(properties);
    }

    public static boolean isBound(ItemStack stack) {
        return getOwnerUuid(stack).isPresent();
    }

    public static boolean isBoundTo(ItemStack stack, UUID playerUuid) {
        return getOwnerUuid(stack).filter(playerUuid::equals).isPresent();
    }

    public static Optional<UUID> getOwnerUuid(ItemStack stack) {
        if (!(stack.getItem() instanceof HandOfCatItem)) {
            return Optional.empty();
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(OWNER_UUID_TAG)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(tag.getString(OWNER_UUID_TAG)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<String> getOwnerName(ItemStack stack) {
        if (!(stack.getItem() instanceof HandOfCatItem)) {
            return Optional.empty();
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        String ownerName = tag.getString(OWNER_NAME_TAG);
        return ownerName.isBlank() ? Optional.empty() : Optional.of(ownerName);
    }

    // 将空白猫之手绑定给玩家；已绑定信物不会被覆盖。
    public static boolean bindTo(ItemStack stack, ServerPlayer player) {
        if (!(stack.getItem() instanceof HandOfCatItem) || isBound(stack)) {
            return false;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(OWNER_UUID_TAG, player.getUUID().toString());
            tag.putString(OWNER_NAME_TAG, player.getGameProfile().getName());
        });
        return true;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipLines, flag);
        TooltipBuilder tooltip = new TooltipBuilder(tooltipLines);

        // WIP 提示：物品仍在开发中，效果可能变更，置于首行醒目提示
        tooltip.wip();

        // 客户端缓存经桥接读取；专用服务端未安装实现时取缺省值
        int favor = ClientTooltipBridge.handOfCatFavor();
        int lives = ClientTooltipBridge.handOfCatLives();
        Optional<UUID> ownerUuid = getOwnerUuid(stack);

        if (ownerUuid.isEmpty()) {
            tooltip.labelValue("item.unsuspiciousblock.hand_of_cat.tooltip.status",
                    Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip.unbound")
                            .withStyle(TooltipBuilder.HINT));
            tooltip.hint("item.unsuspiciousblock.hand_of_cat.tooltip.bind_hint");
            return;
        }

        String ownerName = getOwnerName(stack).orElseGet(() -> abbreviateUuid(ownerUuid.get()));
        tooltip.labelValue("item.unsuspiciousblock.hand_of_cat.tooltip.owner",
                Component.literal(ownerName).withStyle(TooltipBuilder.NAME));

        if (!ClientTooltipBridge.isLocalPlayer(ownerUuid.get())) {
            return;
        }

        // 本人信物显示猫族羁绊、当前阶段与残存命数，标签灰 + 数值按语义上色
        tooltip.labelValue("item.unsuspiciousblock.hand_of_cat.tooltip.favor",
                Component.literal(favor + "/" + CatFavorAbility.NINE_LIVES.threshold())
                        .withStyle(TooltipBuilder.TITLE));
        CatBondStage stage = CatBondStage.fromBond(favor);
        tooltip.labelValue("item.unsuspiciousblock.hand_of_cat.tooltip.stage",
                Component.translatable(stage.translationKey()).withStyle(stageColor(stage)));

        // 简要信息：残存命数（>0 时显示）
        if (lives > 0) {
            tooltip.labelValue("item.unsuspiciousblock.hand_of_cat.tooltip.lives",
                    Component.literal(lives + "/9").withStyle(TooltipBuilder.ACCENT));
        }

        // 详尽模式：遍历枚举列出全部能力 + 自动折行描述；否则显示通用 Shift 展开提示
        tooltip.expandable(t -> {
            t.section("item.unsuspiciousblock.hand_of_cat.tooltip.abilities");
            for (CatFavorAbility ability : CatFavorAbility.values()) {
                appendAbility(t, ability, favor);
            }
        });
    }

    private static ChatFormatting stageColor(CatBondStage stage) {
        return switch (stage) {
            case ACQUAINTED -> TooltipBuilder.LABEL;
            case CLOSE -> TooltipBuilder.POSITIVE;
            case TRUSTED -> TooltipBuilder.ACCENT;
            case FAVORED -> TooltipBuilder.NAME;
            case HONORED_GUEST -> TooltipBuilder.TITLE;
            case BEST_FRIEND -> TooltipBuilder.SPECIAL;
        };
    }

    private static String abbreviateUuid(UUID uuid) {
        String value = uuid.toString();
        return value.substring(0, Math.min(8, value.length()));
    }

    // 追加一条能力状态：已解锁显示绿色 + 折行描述，未解锁显示灰色并标注所需羁绊
    private static void appendAbility(TooltipBuilder tooltip, CatFavorAbility ability, int favor) {
        boolean unlocked = ability.isUnlockedAt(favor);
        MutableComponent name = Component.translatable(ability.nameKey());
        if (unlocked) {
            tooltip.wrapped(" ✔ ", "    ", name.withStyle(TooltipBuilder.POSITIVE));
            tooltip.wrapped("    ", "    ",
                    Component.translatable(ability.descKey()).withStyle(TooltipBuilder.LABEL));
        } else {
            MutableComponent locked = name
                    .append(Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.required",
                            ability.threshold()))
                    .withStyle(TooltipBuilder.HINT);
            tooltip.wrapped(" ✖ ", "    ", locked);
        }
    }
}
