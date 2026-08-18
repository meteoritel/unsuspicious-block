package com.meteorite.unsuspiciousblock.plugin.jei;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.screen.PotteryWheelScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.recipe.UnsuspiciousCreationRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** JEI 联动入口，将模组配方接入原版工作台与熔炉分类。 */
@mezz.jei.api.JeiPlugin
public class JeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "jei_plugin");

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(
                new PotteryWheelJeiCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        registration.getCraftingCategory().addExtension(
                UnsuspiciousCreationRecipe.class,
                new UnsuspiciousCreationCategoryExtension());
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(RecipeTypes.SMELTING, createSherdSmeltingRecipes());
        registration.addRecipes(PotteryWheelJeiCategory.TYPE, List.of(
                PotteryWheelJeiRecipe.createPotRecipe(),
                PotteryWheelJeiRecipe.createSherdRecipe()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(ModItems.POTTERY_WHEEL, PotteryWheelJeiCategory.TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addRecipeClickArea(
                PotteryWheelScreen.class,
                110, 36, 20, 16,
                PotteryWheelJeiCategory.TYPE);
        registration.addGlobalGuiHandler(new JournalJeiGuiHandler(
                registration.getJeiHelpers().getIngredientManager()));
        registration.addGuiScreenHandler(ArchaeologyJournalScreen.class, screen -> {
            // JEI 可能在 Screen.init 完成前查询属性，此时 Screen 的宽高仍为 0。
            int windowWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int windowHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int screenWidth = Math.max(2, screen.width > 1 ? screen.width : windowWidth);
            int screenHeight = Math.max(2, screen.height > 1 ? screen.height : windowHeight);
            return new mezz.jei.api.gui.handlers.IGuiProperties() {
                @Override
                public @NotNull Class<? extends Screen> screenClass() {
                    return ArchaeologyJournalScreen.class;
                }

                @Override
                public int guiLeft() {
                    return 0;
                }

                @Override
                public int guiTop() {
                    return 0;
                }

                @Override
                public int guiXSize() {
                    return screenWidth;
                }

                @Override
                public int guiYSize() {
                    return screenHeight;
                }

                @Override
                public int screenWidth() {
                    return screenWidth;
                }

                @Override
                public int screenHeight() {
                    return screenHeight;
                }
            };
        });
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime jeiRuntime) {
        ArchaeologyJournalKeyHandler.registerHoveredItemProvider((screen, mouseX, mouseY) -> {
            if (jeiRuntime.getIngredientListOverlay().hasKeyboardFocus()) {
                return Optional.of(ItemStack.EMPTY);
            }
            ItemStack ingredient = jeiRuntime.getIngredientListOverlay()
                    .getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (ingredient == null) {
                ingredient = jeiRuntime.getBookmarkOverlay().getItemStackUnderMouse();
            }
            if (ingredient != null && !ingredient.isEmpty()) {
                return Optional.of(ingredient);
            }
            return jeiRuntime.getScreenHelper()
                    .getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                    .map(IClickableIngredient::getIngredient)
                    .filter(ItemStack.class::isInstance)
                    .map(ItemStack.class::cast)
                    .findFirst();
        });
    }

    @Override
    public void onRuntimeUnavailable() {
        ArchaeologyJournalKeyHandler.clearHoveredItemProvider();
    }

    // 为 tag 中的每种纹饰陶片创建独立的原版熔炉展示配方
    private static List<RecipeHolder<SmeltingRecipe>> createSherdSmeltingRecipes() {
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> BuiltInRegistries.ITEM.wrapAsHolder(item).is(ItemTags.DECORATED_POT_SHERDS))
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .map(JeiPlugin::createSherdSmeltingRecipe)
                .toList();
    }

    // 使用无纹饰组件的未烧制陶片作为统一展示输入
    private static RecipeHolder<SmeltingRecipe> createSherdSmeltingRecipe(Item result) {
        ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result);
        ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath(
                Constants.MOD_ID,
                "jei/unfired_decorated_sherd_smelting/"
                        + resultId.getNamespace() + "/" + resultId.getPath());
        SmeltingRecipe recipe = new SmeltingRecipe(
                "",
                CookingBookCategory.MISC,
                Ingredient.of(ModItems.UNFIRED_DECORATED_SHERD),
                result.getDefaultInstance(),
                0.1F,
                200);
        return new RecipeHolder<>(recipeId, recipe);
    }

    /** 为不可疑方块特殊配方提供原版工作台布局。 */
    private static final class UnsuspiciousCreationCategoryExtension
            implements ICraftingCategoryExtension<UnsuspiciousCreationRecipe> {
        @Override
        public void setRecipe(RecipeHolder<UnsuspiciousCreationRecipe> recipeHolder,
                              @NotNull IRecipeLayoutBuilder builder,
                              ICraftingGridHelper craftingGridHelper,
                              @NotNull IFocusGroup focuses) {
            UnsuspiciousCreationRecipe recipe = recipeHolder.value();
            List<List<ItemStack>> inputs = List.of(
                    List.of(recipe.getBaseItem().getDefaultInstance()),
                    List.of(ModItems.ARCHAEOLOGICAL_SHOVEL.getDefaultInstance()));
            craftingGridHelper.createAndSetInputs(builder, inputs, 0, 0);
            craftingGridHelper.createAndSetOutputs(
                    builder,
                    List.of(recipe.getOutputItem().getDefaultInstance()));
            builder.setShapeless();
        }
    }
}
