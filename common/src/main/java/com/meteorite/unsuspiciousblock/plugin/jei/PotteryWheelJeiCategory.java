package com.meteorite.unsuspiciousblock.plugin.jei;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import com.meteorite.unsuspiciousblock.client.ui.PotteryPreviewRenderer;
import com.meteorite.unsuspiciousblock.item.ModItems;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.PotDecorations;

import java.util.List;
import java.util.Optional;

/** 复用陶轮台 GUI 十字槽位、进度箭头与放大预览的 JEI 配方分类。 */
public final class PotteryWheelJeiCategory extends AbstractRecipeCategory<PotteryWheelJeiRecipe> {
    public static final RecipeType<PotteryWheelJeiRecipe> TYPE = RecipeType.create(
            Constants.MOD_ID, "pottery_wheel", PotteryWheelJeiRecipe.class);

    private static final ResourceLocation GUI_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/pottery_wheel_gui.png");
    private static final ResourceLocation POT_RECIPE_ID = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "jei/pottery_wheel/pot");
    private static final ResourceLocation SHERD_RECIPE_ID = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "jei/pottery_wheel/sherd");

    private static final String TOP_SLOT = "pattern_top";
    private static final String LEFT_SLOT = "pattern_left";
    private static final String RIGHT_SLOT = "pattern_right";
    private static final String BOTTOM_SLOT = "pattern_bottom";
    private static final String CLAY_SLOT = "clay";
    private static final String OUTPUT_SLOT = "output";

    private static final int CROP_X = 18;
    private static final int CROP_Y = 16;
    private static final int WIDTH = 158;
    private static final int HEIGHT = 64;
    private static final int PREVIEW_ITEM_X = 119;
    private static final int PREVIEW_ITEM_Y = 4;

    private final IDrawableStatic background;
    private final IDrawableAnimated progress;
    private final PotteryPreviewRenderer previewRenderer = new PotteryPreviewRenderer();

    public PotteryWheelJeiCategory(IGuiHelper guiHelper) {
        super(TYPE,
                Component.translatable("jei.unsuspiciousblock.category.pottery_wheel"),
                guiHelper.createDrawableItemStack(ModItems.POTTERY_WHEEL.getDefaultInstance()),
                WIDTH, HEIGHT);
        this.background = guiHelper.createDrawable(GUI_TEXTURE, CROP_X, CROP_Y, WIDTH, HEIGHT);
        IDrawableStatic progressTexture = guiHelper.createDrawable(GUI_TEXTURE, 176, 16, 20, 16);
        this.progress = guiHelper.createAnimatedDrawable(
                progressTexture,
                PotteryWheelBlockEntity.PROCESS_TIME,
                IDrawableAnimated.StartDirection.LEFT,
                false);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, PotteryWheelJeiRecipe recipe, IFocusGroup focuses) {
        if (recipe.mode() == PotteryWheelJeiRecipe.Mode.POT) {
            addPatternSlot(builder, TOP_SLOT, 26, 2, recipe.patternInputs());
            addPatternSlot(builder, LEFT_SLOT, 8, 20, recipe.patternInputs());
            addPatternSlot(builder, RIGHT_SLOT, 44, 20, recipe.patternInputs());
            addPatternSlot(builder, BOTTOM_SLOT, 26, 38, recipe.patternInputs());
        } else {
            IRecipeSlotBuilder patternSlot = addPatternSlot(
                    builder, TOP_SLOT, 26, 2, recipe.patternInputs());
            IRecipeSlotBuilder outputSlot = addOutputSlot(builder, recipe.outputs());
            builder.createFocusLink(patternSlot, outputSlot);
        }

        builder.addInputSlot(26, 20)
                .setSlotName(CLAY_SLOT)
                .addItemStacks(recipe.clayInputs());
        builder.addInputSlot(71, 20)
                .addItemStack(recipe.waterInput());

        if (recipe.mode() == PotteryWheelJeiRecipe.Mode.POT) {
            addOutputSlot(builder, recipe.outputs());
        }
    }

    @Override
    public void onDisplayedIngredientsUpdate(PotteryWheelJeiRecipe recipe,
                                             List<IRecipeSlotDrawable> recipeSlots,
                                             IFocusGroup focuses) {
        findSlot(recipeSlots, OUTPUT_SLOT).ifPresent(outputSlot -> {
            ItemStack result = recipe.mode() == PotteryWheelJeiRecipe.Mode.POT
                    ? createDisplayedPot(recipeSlots)
                    : createDisplayedSherd(recipeSlots);
            if (result.isEmpty()) {
                outputSlot.clearDisplayOverrides();
            } else {
                outputSlot.createDisplayOverrides().addItemStack(result);
            }
        });
    }

    @Override
    public void draw(PotteryWheelJeiRecipe recipe, IRecipeSlotsView recipeSlotsView,
                     GuiGraphics guiGraphics, double mouseX, double mouseY) {
        background.draw(guiGraphics, 0, 0);
        progress.draw(guiGraphics, 92, 20);
        recipeSlotsView.findSlotByName(OUTPUT_SLOT)
                .flatMap(IRecipeSlotView::getDisplayedItemStack)
                .ifPresent(output -> renderPreview(guiGraphics, output));
    }

    @Override
    public ResourceLocation getRegistryName(PotteryWheelJeiRecipe recipe) {
        return recipe.mode() == PotteryWheelJeiRecipe.Mode.POT ? POT_RECIPE_ID : SHERD_RECIPE_ID;
    }

    // 添加一个复用 GUI 原始坐标的纹饰输入槽
    private static IRecipeSlotBuilder addPatternSlot(
            IRecipeLayoutBuilder builder, String name, int x, int y, List<ItemStack> patterns) {
        return builder.addInputSlot(x, y)
                .setSlotName(name)
                .addItemStacks(patterns);
    }

    // 添加实际产物槽并保留所有候选结果用于 JEI 查询
    private static IRecipeSlotBuilder addOutputSlot(
            IRecipeLayoutBuilder builder, List<ItemStack> outputs) {
        return builder.addOutputSlot(127, 45)
                .setSlotName(OUTPUT_SLOT)
                .addItemStacks(outputs);
    }

    // 根据四个正在显示的纹饰输入构造对应的未烧制陶罐
    private static ItemStack createDisplayedPot(List<IRecipeSlotDrawable> slots) {
        Optional<ItemStack> top = getDisplayedStack(slots, TOP_SLOT);
        Optional<ItemStack> left = getDisplayedStack(slots, LEFT_SLOT);
        Optional<ItemStack> right = getDisplayedStack(slots, RIGHT_SLOT);
        Optional<ItemStack> bottom = getDisplayedStack(slots, BOTTOM_SLOT);
        if (top.isEmpty() || left.isEmpty() || right.isEmpty() || bottom.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack result = ModItems.UNFIRED_DECORATED_POT.getDefaultInstance();
        result.set(DataComponents.POT_DECORATIONS, new PotDecorations(
                top.get().getItem(), left.get().getItem(),
                right.get().getItem(), bottom.get().getItem()));
        return result;
    }

    // 根据当前纹饰与黏土类型构造 1 个或 4 个未烧制陶片
    private static ItemStack createDisplayedSherd(List<IRecipeSlotDrawable> slots) {
        Optional<ItemStack> pattern = getDisplayedStack(slots, TOP_SLOT);
        Optional<ItemStack> clay = getDisplayedStack(slots, CLAY_SLOT);
        if (pattern.isEmpty() || clay.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int count = clay.get().is(Blocks.CLAY.asItem()) ? 4 : 1;
        Item source = pattern.get().getItem();
        ItemStack result = new ItemStack(ModItems.UNFIRED_DECORATED_SHERD, count);
        result.set(DataComponents.POT_DECORATIONS,
                new PotDecorations(source, source, source, source));
        return result;
    }

    // 使用输出组件还原陶轮台 GUI 中的放大纹饰预览
    private void renderPreview(GuiGraphics graphics, ItemStack output) {
        if (output.is(ModItems.UNFIRED_DECORATED_POT)) {
            ItemStack decoratedPot = Items.DECORATED_POT.getDefaultInstance();
            decoratedPot.set(DataComponents.POT_DECORATIONS,
                    output.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY));
            previewRenderer.render(graphics, decoratedPot, 135.0F, 34.0F, 24.0F, 0.0F);
            return;
        }

        ItemStack preview = output;
        if (output.is(ModItems.UNFIRED_DECORATED_SHERD)) {
            PotDecorations decorations = output.get(DataComponents.POT_DECORATIONS);
            if (decorations != null && !decorations.ordered().isEmpty()) {
                preview = new ItemStack(decorations.ordered().getFirst());
            }
        }
        graphics.pose().pushPose();
        graphics.pose().translate(PREVIEW_ITEM_X, PREVIEW_ITEM_Y, 0.0F);
        graphics.pose().scale(2.0F, 2.0F, 1.0F);
        graphics.renderItem(preview, 0, 0);
        graphics.pose().popPose();
        graphics.flush();
    }

    // 按槽位名称查找 JEI 的可绘制槽位
    private static Optional<IRecipeSlotDrawable> findSlot(List<IRecipeSlotDrawable> slots, String name) {
        return slots.stream()
                .filter(slot -> slot.getSlotName().filter(name::equals).isPresent())
                .findFirst();
    }

    // 返回指定槽位当前轮换显示的物品
    private static Optional<ItemStack> getDisplayedStack(List<IRecipeSlotDrawable> slots, String name) {
        return findSlot(slots, name).flatMap(IRecipeSlotDrawable::getDisplayedItemStack);
    }
}
