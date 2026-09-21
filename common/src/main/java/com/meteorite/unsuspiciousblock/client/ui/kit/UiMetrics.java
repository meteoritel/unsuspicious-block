package com.meteorite.unsuspiciousblock.client.ui.kit;

/** 可选开发计时出口；生产关闭时不读取时钟，计数与最近耗时不创建逐帧快照。 */
public final class UiMetrics {
    private final boolean enabled;
    private long builds, layouts, renders, hits;
    private long buildNanos, layoutNanos, renderNanos, hitNanos;

    public UiMetrics(boolean enabled) { this.enabled = enabled; }
    long start() { return enabled ? System.nanoTime() : 0; }
    void built(long start) { if (enabled) { builds++; buildNanos = System.nanoTime() - start; } }
    void laidOut(long start) { if (enabled) { layouts++; layoutNanos = System.nanoTime() - start; } }
    void rendered(long start) { if (enabled) { renders++; renderNanos = System.nanoTime() - start; } }
    void hit(long start) { if (enabled) { hits++; hitNanos = System.nanoTime() - start; } }
    public boolean enabled() { return enabled; }
    public long builds() { return builds; }
    public long layouts() { return layouts; }
    public long renders() { return renders; }
    public long hits() { return hits; }
    public long buildNanos() { return buildNanos; }
    public long layoutNanos() { return layoutNanos; }
    public long renderNanos() { return renderNanos; }
    public long hitNanos() { return hitNanos; }
}
