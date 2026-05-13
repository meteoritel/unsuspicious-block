package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.util.Mth;

import java.util.function.IntSupplier;

/**
 * 通用分页状态。
 */
public final class PaginationState {
    private final IntSupplier pageCountSupplier;
    private int page;

    public PaginationState(IntSupplier pageCountSupplier) {
        this.pageCountSupplier = pageCountSupplier;
    }

    public int pageCount() {
        return Math.max(1, this.pageCountSupplier.getAsInt());
    }

    public int getPage() {
        return Mth.clamp(this.page, 0, Math.max(0, this.pageCount() - 1));
    }

    public void changePage(int delta) {
        this.setPage(this.getPage() + delta);
    }

    public void setPage(int page) {
        this.page = Mth.clamp(page, 0, Math.max(0, this.pageCount() - 1));
    }

    public void reset() {
        this.page = 0;
    }
}