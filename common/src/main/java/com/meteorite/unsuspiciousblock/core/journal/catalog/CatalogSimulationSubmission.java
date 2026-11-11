package com.meteorite.unsuspiciousblock.core.journal.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker.SimulationRequest;

import java.util.Set;
import java.util.UUID;

/** 一项去重工作完成后的提交凭据；目录独占一次消费权，等待者以完成时快照交付。 */
public final class CatalogSimulationSubmission {
    private final SimulationRequest request;
    private final Set<UUID> requesters;
    private final Publication publication;
    private boolean consumed;

    public CatalogSimulationSubmission(SimulationRequest request, Set<UUID> requesters) {
        this(request, request.requester() == null ? Publication.BASELINE : Publication.REQUEST_ONLY, requesters);
    }

    public CatalogSimulationSubmission(SimulationRequest request, Publication publication, Set<UUID> requesters) {
        this.request = request;
        this.publication = publication;
        this.requesters = Set.copyOf(requesters);
    }

    /** 共享基准与玩家选择具有不同的发布归属。 */
    public enum Publication { BASELINE, REQUEST_ONLY }

    public boolean publishesBaseline() { return publication == Publication.BASELINE; }
    public SimulationRequest request() { return request; }
    public Set<UUID> requesters() { return requesters; }

    boolean claim() {
        if (this.consumed) return false;
        this.consumed = true;
        return true;
    }
}
