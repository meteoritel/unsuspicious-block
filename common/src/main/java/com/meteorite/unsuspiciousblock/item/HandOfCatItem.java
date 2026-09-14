package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.cat.CatFavorAbility;
import com.meteorite.unsuspiciousblock.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;
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

        int favor = HandOfCatClientState.getCachedFavor();
        int lives = HandOfCatClientState.getCachedNineLivesCount();
        Optional<UUID> ownerUuid = getOwnerUuid(stack);

        if (ownerUuid.isEmpty()) {
            tooltip.status("item.unsuspiciousblock.hand_of_cat.tooltip.unbound");
            return;
        }

        String ownerName = getOwnerName(stack).orElseGet(() -> abbreviateUuid(ownerUuid.get()));
        tooltip.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip.owner", ownerName)
                .withStyle(TooltipBuilder.NAME));

        if (!HandOfCatClientState.isLocalPlayer(ownerUuid.get())) {
            return;
        }

        // 本人信物显示猫族羁绊与当前阶段。
        tooltip.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip.favor",
                favor, CatFavorAbility.NINE_LIVES.threshold()).withStyle(TooltipBuilder.TITLE));
        CatBondStage stage = CatBondStage.fromBond(favor);
        tooltip.status("item.unsuspiciousblock.hand_of_cat.tooltip.stage",
                Component.translatable(stage.translationKey()));

        // 简要信息：残存命数（>0 时显示）
        if (lives > 0) {
            tooltip.add(Component.translatable("item.unsuspiciousblock.hand_of_cat.tooltip.lives",
                    lives).withStyle(TooltipBuilder.ACCENT));
        }

        // 详尽模式：遍历枚举列出全部能力 + 描述；否则显示通用 Shift 展开提示
        tooltip.expandable(t -> {
            t.section("item.unsuspiciousblock.hand_of_cat.tooltip.abilities");
            for (CatFavorAbility ability : CatFavorAbility.values()) {
                appendAbility(t, ability, favor);
            }
        });
    }

    private static String abbreviateUuid(UUID uuid) {
        String value = uuid.toString();
        return value.substring(0, Math.min(8, value.length()));
    }

    // 追加一条能力状态：已解锁显示绿色 + 描述，未解锁显示灰色并标注所需恩惠
    private static void appendAbility(TooltipBuilder tooltip, CatFavorAbility ability, int favor) {
        boolean unlocked = ability.isUnlockedAt(favor);
        MutableComponent name = Component.translatable(ability.nameKey());
        if (unlocked) {
            tooltip.add(Component.literal(" ✔ ").withStyle(TooltipBuilder.POSITIVE)
                    .append(name.withStyle(TooltipBuilder.POSITIVE)));
            tooltip.add(Component.literal("    ").withStyle(TooltipBuilder.HINT)
                    .append(Component.translatable(ability.descKey()).withStyle(TooltipBuilder.HINT)));
        } else {
            tooltip.add(Component.literal(" ✖ ").withStyle(TooltipBuilder.HINT)
                    .append(name.withStyle(TooltipBuilder.HINT))
                    .append(Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.required", ability.threshold())
                            .withStyle(TooltipBuilder.HINT)));
        }
    }
}
