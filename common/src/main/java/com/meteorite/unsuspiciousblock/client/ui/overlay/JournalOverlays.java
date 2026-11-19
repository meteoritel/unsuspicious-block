package com.meteorite.unsuspiciousblock.client.ui.overlay;

import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.input.UiInteractionSession;
import com.meteorite.meteosuikit.api.render.UiLayer;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** 笔记场景覆盖面的所有权；输入、遮罩与焦点由实际 kit 层负责。 */
public final class JournalOverlays {
    /** 场景覆盖面的准备与关闭业务。 */
    public interface Surface {
        UiLayer layer();
        void prepare(Font font);
        default void invalidateResources() { }
        default void closed() { }
    }

    private int width = 1;
    private int height = 1;
    private @Nullable UiLayer active;
    private Consumer<Font> prepare = font -> { };
    private Runnable closed = () -> { };
    private Runnable invalidateResources = () -> { };

    public int width() { return width; }
    public int height() { return height; }
    public void setBounds(int width, int height) { this.width = width; this.height = height; }
    public boolean isOpen() { return active != null; }
    public @Nullable UiLayer layer() { return active; }

    public void open(Surface surface) {
        open(surface.layer(), surface::prepare, surface::closed);
        invalidateResources = surface::invalidateResources;
    }

    public void open(UiLightbox lightbox) {
        open(lightbox.layer(), font -> lightbox.prepare(font, width, height), lightbox::onClosed);
        invalidateResources = lightbox::invalidateResources;
    }

    private void open(UiLayer layer, Consumer<Font> prepare, Runnable closed) {
        close();
        active = layer;
        this.prepare = prepare;
        this.closed = closed;
        layer.scope().setEscape(this::close);
    }

    public void prepare(Font font) { prepare.accept(font); }
    public void invalidateResources() { invalidateResources.run(); }

    public void close() {
        UiLayer previous = active;
        active = null;
        Runnable cleanup = closed;
        prepare = font -> { };
        closed = () -> { };
        invalidateResources = () -> { };
        if (previous != null) {
            previous.scope().interaction().end(UiInteractionSession.EndReason.CANCEL);
            cleanup.run();
        }
    }

}
