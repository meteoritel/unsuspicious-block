package com.meteorite.unsuspiciousblock.loottable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * 表示考古手册中一个可匹配的战利品结果签名。
 */
public record LootResultSignature(ResourceLocation itemId, SignatureType type, @Nullable String data) {
    private static final String STORED_KEY_PREFIX = "usb_sig";

    public LootResultSignature {
        data = normalizeData(data);
    }

    // 构造普通物品签名
    public static LootResultSignature plain(ResourceLocation itemId) {
        return new LootResultSignature(itemId, SignatureType.PLAIN, null);
    }

    // 构造附魔近似签名
    public static LootResultSignature enchantedApprox(ResourceLocation itemId) {
        return new LootResultSignature(itemId, SignatureType.ENCHANTED_APPROX, null);
    }

    // 构造严格组件签名
    public static LootResultSignature componentExact(ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return plain(itemId);
        }

        String patchData = encodeComponentPatch(patch);
        return patchData != null
                ? new LootResultSignature(itemId, SignatureType.COMPONENT_EXACT, patchData)
                : approximateItemOnly(itemId, "component_encode_failed");
    }

    // 构造近似回退签名
    public static LootResultSignature approximateItemOnly(ResourceLocation itemId, @Nullable String reason) {
        return new LootResultSignature(itemId, SignatureType.APPROX_ITEM_ONLY, reason);
    }

    // 返回用于 NBT / 网络 / Map 键的稳定字符串
    public String toStoredKey() {
        return STORED_KEY_PREFIX
                + "|" + this.type.name()
                + "|" + this.itemId
                + "|" + encodeData(this.data);
    }

    // 从持久化字符串恢复签名；兼容旧版仅以 item id 存储的 key
    @Nullable
    public static LootResultSignature fromStoredKey(String storedKey) {
        if (storedKey == null || storedKey.isBlank()) {
            return null;
        }
        if (!storedKey.startsWith(STORED_KEY_PREFIX + "|")) {
            ResourceLocation legacyItemId = ResourceLocation.tryParse(storedKey);
            return legacyItemId != null ? plain(legacyItemId) : null;
        }

        String[] parts = storedKey.split("\\|", 4);
        if (parts.length < 4) {
            return null;
        }

        SignatureType type;
        try {
            type = SignatureType.valueOf(parts[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }

        ResourceLocation itemId = ResourceLocation.tryParse(parts[2]);
        if (itemId == null) {
            return null;
        }

        if (type == SignatureType.ENCHANTED_RANDOM || type == SignatureType.ENCHANTED_LEVEL) {
            return enchantedApprox(itemId);
        }
        return new LootResultSignature(itemId, type, decodeData(parts[3]));
    }

    public boolean isEnchantedVariant() {
        return this.type == SignatureType.ENCHANTED_RANDOM
                || this.type == SignatureType.ENCHANTED_LEVEL
                || this.type == SignatureType.ENCHANTED_APPROX;
    }

    // 根据签名重建用于 UI 展示或精确匹配的预览物品
    public ItemStack createPreviewStack() {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(this.itemId));
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (this.type == SignatureType.COMPONENT_EXACT) {
            DataComponentPatch patch = decodeComponentPatch(this.data);
            if (patch != null) {
                try {
                    stack.applyComponentsAndValidate(patch);
                } catch (RuntimeException exception) {
                    return ItemStack.EMPTY;
                }
            }
        } else if (this.isEnchantedVariant()) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    @Nullable
    private static String encodeComponentPatch(DataComponentPatch patch) {
        return DataComponentPatch.CODEC.encodeStart(JsonOps.INSTANCE, patch)
                .result()
                .map(JsonElement::toString)
                .orElse(null);
    }

    @Nullable
    public static DataComponentPatch decodeComponentPatch(@Nullable String patchData) {
        if (patchData == null || patchData.isBlank()) {
            return null;
        }
        try {
            return DataComponentPatch.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(patchData))
                    .result()
                    .orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String encodeData(@Nullable String data) {
        if (data == null) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static String decodeData(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            return normalizeData(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Nullable
    private static String normalizeData(@Nullable String data) {
        if (data == null) {
            return null;
        }
        String trimmed = data.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum SignatureType {
        PLAIN,
        COMPONENT_EXACT,
        ENCHANTED_RANDOM,
        ENCHANTED_LEVEL,
        ENCHANTED_APPROX,
        APPROX_ITEM_ONLY
    }
}
