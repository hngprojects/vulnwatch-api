package com.vulnwatch.worker.interfaces;

import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.SurfaceState;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the per-surface state of an in-progress scan job.
 *
 * <p>Implementations must guarantee that status transitions are safe under
 * concurrent access (multiple worker instances processing the same stream).
 *
 * <p>Key design rules:
 * <ul>
 *   <li>A surface that has reached SUCCESS cannot be downgraded.</li>
 *   <li>A surface that is PERMANENTLY_FAILED cannot be retried.</li>
 *   <li>Both SUCCESS and PERMANENTLY_FAILED are terminal — the scan can
 *       complete even when some surfaces permanently fail.</li>
 * </ul>
 */
public interface SurfaceStateManager {

    /**
     * Initializes all surfaces with PENDING status and retryCount = 0.
     * Idempotent — safe to call multiple times; subsequent calls are no-ops.
     */
    void initSurfaces(UUID scanId, List<SurfaceType> surfaces);

    /**
     * Marks a surface as SUCCESS. No-op if the surface is already terminal.
     */
    void updateSuccess(UUID scanId, SurfaceType surface);

    /**
     * Marks a surface as FAILED and records the error message.
     * No-op if the surface is already SUCCESS.
     */
    void updateFailure(UUID scanId, SurfaceType surface, String errorMessage);

    /**
     * Marks a surface as RETRYING, recording the current retry count and error.
     */
    void updateRetrying(UUID scanId, SurfaceType surface, int retryCount, String errorMessage);

    /**
     * Marks a surface as PERMANENTLY_FAILED (retries exhausted).
     * No-op if the surface is already SUCCESS.
     */
    void updatePermanentlyFailed(UUID scanId, SurfaceType surface, String errorMessage);

    /**
     * Atomically increments and returns the new retry count for the surface.
     */
    int incrementRetryCount(UUID scanId, SurfaceType surface);

    /**
     * Returns the current state of a single surface.
     * If the surface key is missing, returns a synthetic PENDING state.
     */
    SurfaceState getSurfaceState(UUID scanId, SurfaceType surface);

    /**
     * Returns the state of all surfaces for a scan, keyed by surface name.
     */
    Map<String, SurfaceState> getAllStates(UUID scanId);

    /**
     * Returns true when every surface has reached a terminal state
     * (SUCCESS or PERMANENTLY_FAILED). Returns false if no surfaces are tracked.
     */
    boolean isAllTerminal(UUID scanId);

    /**
     * Returns the names of surfaces that have succeeded.
     */
    List<String> getSuccessfulSurfaces(UUID scanId);

    /**
     * Returns the names of surfaces that have failed (FAILED or PERMANENTLY_FAILED).
     */
    List<String> getFailedSurfaces(UUID scanId);

    boolean hasSurfaceSucceeded(UUID scanId, SurfaceType surface);

    boolean hasSurfaceFailed(UUID scanId, SurfaceType surface);
}