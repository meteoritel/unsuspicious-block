package com.meteorite.tools.recipecards.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 在渲染线程创建独立 JEI 布局，固定材料候选并读取紧凑 PNG。 */
final class CardRenderer {
    private static final long MAX_PIXELS = 16_777_216;
    private static final int OFF_CARD_MOUSE = -10_000;
    private static final float NEAR_PLANE = 1_000;
    private static final float FAR_PLANE = 21_000;
    private static final float GUI_DEPTH = -11_000;

    private CardRenderer() {}

    static <R> Card render(IRecipeCategory<R> category, R recipe, RecipeCardExports.Options options) {
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        IJeiRuntime runtime = RecipeCardJeiPlugin.requireRuntime();
        IFocusGroup focuses = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        IRecipeLayoutDrawable<R> layout = runtime.getRecipeManager()
                .createRecipeLayoutDrawable(category, recipe, focuses)
                .orElseThrow(() -> new IllegalArgumentException("JEI 无法创建该配方的布局"));
        layout.setPosition(0, 0);
        // extras 可能惰性创建；先初始化，固定候选后不再 tick，避免清除 overrides。
        layout.tick();
        List<IRecipeSlotDrawable> slots = freezeSlots(layout, options.variant());
        category.onDisplayedIngredientsUpdate(recipe, slots, focuses);
        freezeOutputs(slots, options.variant());
        Rect2i bounds = layout.getRectWithBorder();
        int width = Math.multiplyExact(bounds.getWidth(), options.scale());
        int height = Math.multiplyExact(bounds.getHeight(), options.scale());
        int textureLimit = RenderSystem.maxSupportedTextureSize();
        if (width <= 0 || height <= 0 || width > textureLimit || height > textureLimit
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("配方卡片超出 GPU 纹理或像素预算：" + width + "x" + height);
        }

        minecraft.renderBuffers().bufferSource().endBatch();
        RenderState state = new RenderState();
        TextureTarget target = null;
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        NativeImage image = null;
        try {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            RenderSystem.disableScissor();
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, bounds.getWidth(),
                    bounds.getHeight(), 0, NEAR_PLANE, FAR_PLANE), VertexSorting.ORTHOGRAPHIC_Z);
            modelView.identity().translate(0, 0, GUI_DEPTH);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            graphics.pose().translate(-bounds.getX(), -bounds.getY(), 0);
            layout.drawRecipe(graphics, OFF_CARD_MOUSE, OFF_CARD_MOUSE);
            graphics.flush();
            image = Screenshot.takeScreenshot(target);
            JsonObject metadata = metadata(minecraft, runtime, layout, width, height, options);
            Card card = new Card(image, metadata);
            image = null;
            return card;
        } finally {
            try {
                graphics.flush();
            } finally {
                if (image != null) {
                    image.close();
                }
                if (target != null) {
                    target.destroyBuffers();
                }
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
                state.restore();
            }
        }
    }

    private static List<IRecipeSlotDrawable> freezeSlots(IRecipeLayoutDrawable<?> layout, int variant) {
        List<IRecipeSlotDrawable> slots = new ArrayList<>();
        for (IRecipeSlotView view : layout.getRecipeSlotsView().getSlotViews()) {
            if (!(view instanceof IRecipeSlotDrawable slot)) {
                throw new IllegalStateException("JEI 槽位未提供 public drawable 接口");
            }
            List<ITypedIngredient<?>> ingredients = slot.getAllIngredientsList();
            slot.clearDisplayOverrides();
            if (slot.getRole() == RecipeIngredientRole.OUTPUT) {
                // 输出留给分类回调生成，避免把旧产物追加进新的动态结果。
                slot.createDisplayOverrides();
            } else if (!ingredients.isEmpty()) {
                // 保留原顺序和空候选，让 focus-linked 输入与产物使用同一候选下标。
                Optional<ITypedIngredient<?>> chosen = Optional.ofNullable(ingredients.get(variant % ingredients.size()));
                slot.createDisplayOverrides().addOptionalTypedIngredients(List.of(chosen));
            }
            slots.add(slot);
        }
        return slots;
    }

    private static void freezeOutputs(List<IRecipeSlotDrawable> slots, int variant) {
        for (IRecipeSlotDrawable slot : slots) {
            if (slot.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            Optional<ITypedIngredient<?>> chosen = slot.getDisplayedIngredient();
            List<ITypedIngredient<?>> ingredients = slot.getAllIngredientsList();
            if (chosen.isEmpty() && !ingredients.isEmpty()) {
                chosen = Optional.ofNullable(ingredients.get(variant % ingredients.size()));
            }
            slot.clearDisplayOverrides();
            slot.createDisplayOverrides().addOptionalTypedIngredients(List.of(chosen));
        }
    }

    private static JsonObject metadata(Minecraft minecraft, IJeiRuntime runtime, IRecipeLayoutDrawable<?> layout,
                                       int width, int height, RecipeCardExports.Options options) {
        JsonObject result = new JsonObject();
        result.addProperty("width", width);
        result.addProperty("height", height);
        result.addProperty("scale", options.scale());
        result.addProperty("variant", options.variant());
        result.addProperty("language", minecraft.getLanguageManager().getSelected());
        result.addProperty("categoryTitle", layout.getRecipeCategory().getTitle().getString());
        JsonArray slots = new JsonArray();
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", slot.getRole().name());
            slot.getSlotName().ifPresent(name -> entry.addProperty("name", name));
            entry.addProperty("alternatives", slot.getAllIngredientsList().size());
            slot.getDisplayedIngredient().ifPresent(ingredient ->
                    entry.add("displayed", ingredientMetadata(runtime.getIngredientManager(), ingredient)));
            slots.add(entry);
        }
        result.add("slots", slots);
        return result;
    }

    private static <T> JsonObject ingredientMetadata(IIngredientManager manager, ITypedIngredient<T> typed) {
        T ingredient = typed.getIngredient();
        IIngredientHelper<T> helper = manager.getIngredientHelper(typed.getType());
        JsonObject result = new JsonObject();
        result.addProperty("type", typed.getType().getUid().toString());
        result.addProperty("id", helper.getResourceLocation(ingredient).toString());
        result.addProperty("name", helper.getDisplayName(ingredient));
        result.addProperty("uid", helper.getUid(typed, UidContext.Ingredient).toString());
        typed.getItemStack().ifPresent(stack -> result.addProperty("count", stack.getCount()));
        return result;
    }

    record Card(NativeImage image, JsonObject metadata) {}

    /** 帧末可能绑定默认 framebuffer，因此恢复真实绑定，而不是猜测主 RenderTarget。 */
    private static final class RenderState {
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int[] viewport = new int[4];
        private final int[] scissorBox = new int[4];
        private final float[] clearColor = new float[4];
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final VertexSorting sorting = RenderSystem.getVertexSorting();
        private final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        private final float[] shaderColor = RenderSystem.getShaderColor().clone();
        private final ShaderInstance shader = RenderSystem.getShader();
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int texture;

        RenderState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            RenderSystem.activeTexture(activeTexture);
        }

        void restore() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            else RenderSystem.disableScissor();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunc);
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShader(() -> shader);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(texture);
            RenderSystem.activeTexture(activeTexture);
        }
    }
}
