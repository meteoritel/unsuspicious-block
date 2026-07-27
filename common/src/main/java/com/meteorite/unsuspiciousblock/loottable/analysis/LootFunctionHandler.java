package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonObject;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.jetbrains.annotations.Nullable;

/**
 * 战利品函数处理器——将 loot function 静态应用到预览栈并派生签名。
 * <p>
 * 外部模组可通过 {@link LootFunctionHandlers#register} 注册自定义处理逻辑，
 * 无需修改本模组的 JSON 解析代码。
 */
public interface LootFunctionHandler {

    /**
     * 尝试将 function 静态应用到预览栈。
     *
     * @param previewStack 预览物品栈（handler 可直接修改其组件，或通过 transmuteCopy 替换）
     * @param function     通过 {@link LootItemFunction} Codec 解析后的类型化 function 对象
     * @return 处理后的预览栈（可能与传入的栈为同一实例，也可能是 transmuteCopy 产生的新栈）；
     *         返回 null 表示无法静态求值（需标记条件）
     */
    @Nullable
    ItemStack apply(ItemStack previewStack, LootItemFunction function);

    /**
     * 应用后派生签名类型。
     *
     * @param previewStack 经过 apply 修改后的预览栈
     * @param itemId       当前物品 id
     * @return 新的签名；返回 null 表示不改变签名（由调用方根据最终栈状态决定）
     */
    @Nullable
    default LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
        return null;
    }

    /**
     * 该 function 是否引入随机性/条件（无法静态确定最终结果）。
     *
     * @return true 表示结果不可静态确定
     */
    boolean addsRandomness();

    /**
     * 当 {@link #apply} 返回 null 时，提供人类可读的提示来描述该 function 的效果范围。
     * 例如 set_count 的范围 "1-3"，set_damage 的范围 "0-90%"。
     * 返回 null 表示无额外提示（使用默认的"近似物品"）。
     */
    @Nullable
    default Component describeHint(LootItemFunction function) {
        return null;
    }

    // 需要读取 Codec 未公开字段时，优先从原始 JSON 提取稳定的结构化信息。
    @Nullable
    default Component describeHint(LootItemFunction function, JsonObject source) {
        return describeHint(function);
    }
}
