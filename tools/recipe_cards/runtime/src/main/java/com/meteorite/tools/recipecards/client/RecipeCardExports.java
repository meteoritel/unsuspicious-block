package com.meteorite.tools.recipecards.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.Util;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Supplier;

/** JS 使用的单请求导出接口；渲染与磁盘写入分别在对应线程完成。 */
public final class RecipeCardExports {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SCALE = 6;
    private static volatile Job current;

    private RecipeCardExports() {}

    /** 是否还有排队、渲染或写入中的请求。 */
    public static boolean isBusy() {
        Job job = current;
        return job != null && !job.finished;
    }

    /**
     * 登记独立布局的导出；optionsJson 包含 id、path、scale、variant 和 overwrite。
     * 调用方提供唯一 id，传输超时后可查询原请求而无需再次提交。
     */
    public static <R> String submit(IRecipeCategory<R> category, R recipe, String optionsJson) {
        if (isBusy()) {
            throw new IllegalStateException("已有配方卡片正在导出");
        }
        Options options = GSON.fromJson(optionsJson, Options.class);
        options.validate();
        IJeiRuntime runtime = RecipeCardJeiPlugin.requireRuntime();
        current = new Job(options, runtime, () -> CardRenderer.render(category, recipe, options));
        return options.id();
    }

    /** 返回单个请求的 JSON 状态；写入成功后才报告 done。 */
    public static String status(String id) {
        Job job = current;
        if (job == null || !job.options.id().equals(id)) {
            JsonObject unknown = new JsonObject();
            unknown.addProperty("state", "unknown");
            unknown.addProperty("id", id);
            return GSON.toJson(unknown);
        }
        return job.statusJson;
    }

    static void afterFrame(RenderFrameEvent.Post event) {
        Job job = current;
        if (job == null || job.finished || !job.queued) {
            return;
        }
        job.queued = false;
        try {
            if (job.runtime != RecipeCardJeiPlugin.requireRuntime()) {
                throw new IllegalStateException("JEI 已重新加载，请重新获取配方清单");
            }
            job.update("rendering", new JsonObject());
            CardRenderer.Card card = job.renderer.get();
            job.update("writing", card.metadata());
            try {
                Util.ioPool().execute(() -> write(job, card));
            } catch (RuntimeException exception) {
                card.image().close();
                throw exception;
            }
        } catch (Exception | LinkageError exception) {
            job.fail(exception);
        }
    }

    private static void write(Job job, CardRenderer.Card card) {
        Path temporary = null;
        try (NativeImage image = card.image()) {
            Path destination = Path.of(job.options.path());
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), ".recipe-card-", ".png");
            image.writeToFile(temporary);
            if (job.options.overwrite()) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, destination);
            }
            job.finished = true;
            job.update("done", card.metadata());
        } catch (Exception exception) {
            job.fail(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOGGER.warn("[usb_recipe_cards] 清理临时 PNG 失败：{}", temporary, exception);
                }
            }
        }
    }

    record Options(String id, String path, int scale, int variant, boolean overwrite) {
        void validate() {
            Objects.requireNonNull(id, "导出请求缺少 id");
            Objects.requireNonNull(path, "导出请求缺少 path");
            Path destination = Path.of(path);
            if (id.isBlank() || !destination.isAbsolute() || !path.endsWith(".png")) {
                throw new IllegalArgumentException("导出需要唯一 id 与绝对 PNG 路径");
            }
            if (scale < 1 || scale > MAX_SCALE || variant < 0) {
                throw new IllegalArgumentException("scale 需在 1–6 之间，variant 需为非负整数");
            }
            if (!overwrite && Files.exists(destination)) {
                throw new IllegalArgumentException("输出已存在：" + destination);
            }
        }
    }

    private static final class Job {
        final Options options;
        final IJeiRuntime runtime;
        final Supplier<CardRenderer.Card> renderer;
        boolean queued = true;
        volatile boolean finished;
        volatile String statusJson;

        Job(Options options, IJeiRuntime runtime, Supplier<CardRenderer.Card> renderer) {
            this.options = options;
            this.runtime = runtime;
            this.renderer = renderer;
            update("queued", new JsonObject());
        }

        void update(String state, JsonObject metadata) {
            JsonObject result = metadata.deepCopy();
            result.addProperty("id", options.id());
            result.addProperty("state", state);
            result.addProperty("path", options.path());
            statusJson = GSON.toJson(result);
        }

        void fail(Throwable exception) {
            LOGGER.error("[usb_recipe_cards] 导出失败：{}", options.path(), exception);
            JsonObject result = new JsonObject();
            result.addProperty("error", exception.toString());
            finished = true;
            update("failed", result);
        }
    }
}
