package com.meteorite.unsuspiciousblock.mixin.client;

import com.meteorite.unsuspiciousblock.client.state.SpecimenBoxScrollState;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.network.payload.c2s.SpecimenBoxScrollPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.component.DataComponents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端 Mixin：拦截 MouseHandler.onScroll 中对 Screen.mouseScrolled 的调用，
 * 实现标本箱内部槽位滚轮选择。
 * <p>
 * 1.21.1 中 Screen 未直接定义 mouseScrolled（该方法是 GuiEventListener 接口的 default 方法），
 * 故通过 MouseHandler.onScroll 间接注入。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerScrollMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onScroll",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screens/Screen;mouseScrolled(DDDD)Z"),
            cancellable = true)
    private void unsuspiciousblock$handleSpecimenBoxScroll(long window, double horizontal,
                                                            double vertical, CallbackInfo ci) {
        Screen screen = this.minecraft.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;

        Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) return;
        if (!hoveredSlot.getItem().is(ModItems.SPECIMEN_BOX)) return;

        ItemStack box = hoveredSlot.getItem();
        var contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.createWithCapacity(5);
        contents.copyInto(items);

        // 收集所有非空内部槽位
        List<Integer> nonEmptyIndices = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) nonEmptyIndices.add(i);
        }
        if (nonEmptyIndices.isEmpty()) return;

        int current = SpecimenBoxScrollState.getSelectedInnerSlot();
        int currentPos = nonEmptyIndices.indexOf(current);
        if (currentPos < 0) currentPos = nonEmptyIndices.size() - 1;

        // 滚轮方向：vertical > 0 向下 → 下一个槽位
        int delta = vertical > 0 ? 1 : (vertical < 0 ? -1 : 0);
        if (delta == 0) return;

        int newPos = (currentPos + delta + nonEmptyIndices.size()) % nonEmptyIndices.size();
        int newSelected = nonEmptyIndices.get(newPos);

        SpecimenBoxScrollState.setSelectedInnerSlot(newSelected);
        Services.NETWORK.sendToServer(new SpecimenBoxScrollPayload(newSelected));

        // 消费事件，阻止原版 mouseScrolled 继续传播
        ci.cancel();
    }
}