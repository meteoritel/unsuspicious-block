package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.input.UiInteractionSession;
import com.meteorite.meteosuikit.api.render.UiLayer;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** 管理笔记当前打开的场景浮层或灯箱的生命周期。 */
public final class JournalOverlays {
    /** 场景覆盖面的准备与关闭业务。 */
    public interface Surface {
        UiLayer layer();
        void prepare(Font font);
        default void invalidateResources() { }
        default void closed() { }
    }

    private record ActiveSurface(UiLayer layer, Consumer<Font> prepare, Runnable invalidateResources, Runnable closed) {
    }

    private int width = 1;
    private int height = 1;
    private @Nullable ActiveSurface active;

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void setBounds(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public boolean isOpen() {
        return active != null;
    }

    public @Nullable UiLayer layer() {
        return active == null ? null : active.layer();
    }

    /** 关闭当前覆盖面并打开场景浮层，将 Esc 接到同一关闭流程。 */
    public void open(Surface surface) {
        open(new ActiveSurface(surface.layer(), surface::prepare, surface::invalidateResources, surface::closed));
    }

    /** 关闭当前覆盖面并打开灯箱，每次准备时使用最新视口尺寸。 */
    public void open(UiLightbox lightbox) {
        open(new ActiveSurface(lightbox.layer(), font -> lightbox.prepare(font, width, height),
                lightbox::invalidateResources, lightbox::onClosed));
    }

    private void open(ActiveSurface next) {
        close();
        active = next;
        next.layer().scope().setEscape(this::close);
    }

    public void prepare(Font font) {
        if (active != null) {
            active.prepare().accept(font);
        }
    }

    public void invalidateResources() {
        if (active != null) {
            active.invalidateResources().run();
        }
    }

    /** 先解除活动层的所有权，避免交互或清理回调再次关闭同一覆盖面。 */
    public void close() {
        ActiveSurface previous = active;
        if (previous == null) {
            return;
        }
        active = null;
        previous.layer().scope().interaction().end(UiInteractionSession.EndReason.CANCEL);
        previous.closed().run();
    }
}
