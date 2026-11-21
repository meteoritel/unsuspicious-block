package com.meteorite.unsuspiciousblock.client.plugin.jei;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.screen.PotteryWheelScreen;
import com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen;
import com.meteorite.unsuspiciousblock.client.state.ArchaeologyJournalKeyHandler;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.recipe.PotterySmeltingRecipe;
import com.meteorite.unsuspiciousblock.core.recipe.UnfiredDecoratedSherdSmeltingRecipe;
import com.meteorite.unsuspiciousblock.core.recipe.UnsuspiciousCreationRecipe;
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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
    public void registerRecipes(@NotNull IRecipeRegistration registration) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            registration.addRecipes(RecipeTypes.SMELTING, connection.getRecipeManager()
                    .getAllRecipesFor(RecipeType.SMELTING).stream()
                    .filter(holder -> holder.value() instanceof PotterySmeltingRecipe && !holder.value().isIncomplete())
                    .flatMap(holder -> createSmeltingPreviews(holder, connection.registryAccess()))
                    .toList());
        }
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
        PotteryWheelJeiGuiHandler potteryWheel = new PotteryWheelJeiGuiHandler();
        registration.addGuiScreenHandler(PotteryWheelScreen.class, potteryWheel);
        registration.addGuiContainerHandler(PotteryWheelScreen.class, potteryWheel);
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

    private static Stream<RecipeHolder<SmeltingRecipe>> createSmeltingPreviews(
            RecipeHolder<SmeltingRecipe> holder, HolderLookup.Provider registries) {
        SmeltingRecipe recipe = holder.value();
        ItemStack input = recipe.getIngredients().getFirst().getItems()[0];
        ResourceLocation previewId = holder.id().withPrefix("jei/");
        if (!(recipe instanceof UnfiredDecoratedSherdSmeltingRecipe)) {
            return Stream.of(createSmeltingPreview(previewId, recipe, input, registries));
        }

        // 每种纹样有独立的展示条目，使 JEI 能按成品陶片查询烧制方式。
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> BuiltInRegistries.ITEM.wrapAsHolder(item).is(ItemTags.DECORATED_POT_SHERDS))
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .map(pattern -> {
                    ResourceLocation patternId = BuiltInRegistries.ITEM.getKey(pattern);
                    ItemStack patternedInput = input.copy();
                    patternedInput.set(DataComponents.POT_DECORATIONS,
                            new PotDecorations(pattern, pattern, pattern, pattern));
                    return createSmeltingPreview(
                            previewId.withSuffix("/" + patternId.getNamespace() + "/" + patternId.getPath()),
                            recipe, patternedInput, registries);
                });
    }

    private static RecipeHolder<SmeltingRecipe> createSmeltingPreview(
            ResourceLocation previewId, SmeltingRecipe recipe, ItemStack input, HolderLookup.Provider registries) {
        Ingredient ingredient = recipe.getIngredients().getFirst();
        ItemStack output = recipe.assemble(new SingleRecipeInput(input), registries);
        return new RecipeHolder<>(previewId, new SmeltingRecipe(
                recipe.getGroup(), recipe.category(), ingredient, output, recipe.getExperience(), recipe.getCookingTime()));
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
