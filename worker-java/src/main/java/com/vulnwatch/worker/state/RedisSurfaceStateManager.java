package com.vulnwatch.worker.state;

import com.vulnwatch.worker.enums.SurfaceStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.interfaces.SurfaceStateManager;
import com.vulnwatch.worker.models.SurfaceState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis Hash implementation of {@link SurfaceStateManager}.
 *
 * <p><b>Key scheme:</b> {@code scan:{scanId}:state}
 * (matches the scheme used by other scan keys, enabling pattern-based TTL and SCAN).
 *
 * <p><b>Hash fields per surface:</b>
 * <pre>
 *   {SURFACE}:status        – SurfaceStatus name
 *   {SURFACE}:retryCount    – integer (managed via HINCRBY)
 *   {SURFACE}:lastError     – string, nullable
 *   {SURFACE}:completedAt   – ISO-8601, nullable
 *   {SURFACE}:lastAttemptAt – ISO-8601, nullable
 * </pre>
 *
 * <p><b>Concurrency:</b>
 * <ul>
 *   <li>{@code initSurfaces} uses {@code putIfAbsent} (HSETNX) on a sentinel
 *       field so only one worker initializes a given scan.</li>
 *   <li>{@code incrementRetryCount} uses {@code increment} (HINCRBY) — atomic.</li>
 *   <li>Status guards (e.g. "don't downgrade SUCCESS") read-then-write and are
 *       therefore <em>not</em> strictly atomic. For the current single-stream,
 *       consumer-group architecture this is acceptable; if stronger guarantees
 *       are needed, wrap the guard + write in a Lua script.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSurfaceStateManager implements SurfaceStateManager {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Produces {@code scan:{scanId}:state}.
     * Consistent with {@code scan:{scanId}:*} used elsewhere in the system.
     */
    private String stateKey(UUID scanId) {
        return "scan:" + scanId + ":state";
    }

    private String field(SurfaceType surface, String attribute) {
        return surface.name() + ":" + attribute;
    }


    /**
     * Atomically initializes all surfaces. Uses HSETNX on a sentinel field
     * so that two workers racing at startup only initialize once.
     */
    @Override
    public void initSurfaces(UUID scanId, List<SurfaceType> surfaces) {
        String key = stateKey(scanId);

        if (!isNewScanState(key)) {
            log.debug("State already initialised for scan {}, skipping", scanId);
            return;
        }

        Map<String, Object> initialState = buildInitialStateMap(surfaces);

        redisTemplate.opsForHash()
                .putAll(key, initialState);
        log.info("Initialised state for scan {} with surfaces: {}", scanId, surfaces);
    }

    private boolean isNewScanState(String key) {
        Boolean isAbsent = redisTemplate
                .opsForHash()
                .putIfAbsent(key, "initialized", "true");
        return Objects.equals(Boolean.TRUE, isAbsent);
    }

    private Map<String, Object> buildInitialStateMap(List<SurfaceType> surfaces) {
        Map<String, Object> state = new HashMap<>();
        for (SurfaceType surface : surfaces) {
            state.put(field(surface, "status"),     SurfaceStatus.PENDING.name());
            state.put(field(surface, "retryCount"), "0");
            state.put(field(surface, "lastError"),  "");
        }
        return state;
    }

    // ── Status updates ────────────────────────────────────────────────────────

    @Override
    public void updateSuccess(UUID scanId, SurfaceType surface) {
        String key = stateKey(scanId);
        SurfaceStatus current = readStatus(key, surface);

        if (current != null && current.isTerminal()) {
            log.warn("Surface {} already terminal ({}) for scan {}, ignoring SUCCESS update",
                    surface, current, scanId);
            return;
        }

        redisTemplate
                .opsForHash()
                .putAll(key, Map.of(
                field(surface, "status"), SurfaceStatus.SUCCESS.name(),
                field(surface, "completedAt"), Instant.now().toString()
        ));
        log.info("Surface {} → SUCCESS for scan {}", surface, scanId);
    }

    @Override
    public void updateFailure(UUID scanId, SurfaceType surface, String errorMessage) {
        String key = stateKey(scanId);
        SurfaceStatus current = readStatus(key, surface);

        if (current == SurfaceStatus.SUCCESS) {
            log.warn("Surface {} already SUCCESS for scan {}, cannot mark as FAILED", surface, scanId);
            return;
        }

        redisTemplate
                .opsForHash()
                .putAll(key, Map.of(
                field(surface, "status"), SurfaceStatus.FAILED.name(),
                field(surface, "lastError"), sanitise(errorMessage),
                field(surface, "lastAttemptAt"), Instant.now().toString()
        ));
        log.info("Surface {} → FAILED for scan {} ({})", surface, scanId, errorMessage);
    }

    @Override
    public void updateRetrying(UUID scanId, SurfaceType surface, int retryCount, String errorMessage) {
        String key = stateKey(scanId);

        redisTemplate
                .opsForHash().putAll(key, Map.of(
                field(surface, "status"), SurfaceStatus.RETRYING.name(),
                field(surface, "retryCount"), String.valueOf(retryCount),
                field(surface, "lastError"), sanitise(errorMessage),
                field(surface, "lastAttemptAt"), Instant.now().toString()
        ));
        log.debug("Surface {} → RETRYING (attempt {}) for scan {}", surface, retryCount, scanId);
    }

    @Override
    public void updatePermanentlyFailed(UUID scanId, SurfaceType surface, String errorMessage) {
        String key = stateKey(scanId);
        SurfaceStatus current = readStatus(key, surface);

        if (current == SurfaceStatus.SUCCESS) {
            log.warn("Surface {} already SUCCESS for scan {}, cannot mark as PERMANENTLY_FAILED",
                    surface, scanId);
            return;
        }

        redisTemplate
                .opsForHash()
                .putAll(key, Map.of(
                field(surface, "status"), SurfaceStatus.PERMANENTLY_FAILED.name(),
                field(surface, "lastError"), sanitise(errorMessage),
                field(surface, "completedAt"), Instant.now().toString()
        ));
        log.error("Surface {} → PERMANENTLY_FAILED for scan {} ({})", surface, scanId, errorMessage);
    }


    @Override
    public int incrementRetryCount(UUID scanId, SurfaceType surface) {
        Long newCount = redisTemplate.opsForHash()
                .increment(stateKey(scanId), field(surface, "retryCount"), 1);
        return newCount.intValue();
    }



    @Override
    public SurfaceState getSurfaceState(UUID scanId, SurfaceType surface) {
        String key = stateKey(scanId);
        List<String> fieldNames = List.of(
                field(surface, "status"),
                field(surface, "retryCount"),
                field(surface, "lastError"),
                field(surface, "completedAt"),
                field(surface, "lastAttemptAt")
        );

        // HMGET — single round trip, only the fields we need
        List<Object> values = redisTemplate.opsForHash().multiGet(key, new ArrayList<>(fieldNames));

        return SurfaceState.builder()
                .surfaceType(surface)
                .status(parseStatus(str(values, 0)))
                .retryCount(parseInt(str(values, 1)))
                .lastError(emptyToNull(str(values, 2)))
                .completedAt(parseInstant(str(values, 3)))
                .lastAttemptAt(parseInstant(str(values, 4)))
                .build();
    }

    @Override
    public Map<String, SurfaceState> getAllStates(UUID scanId) {
        String key = stateKey(scanId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        // Derive surface names from hash keys, excluding the flag field that shows if its initialized or not
        Set<String> surfaceNames = entries.keySet().stream()
                .map(Object::toString)
                .filter(k -> k.contains(":") && !k.equals("initialized"))
                .map(k -> k.split(":")[0])
                .collect(Collectors.toSet());

        Map<String, SurfaceState> result = new LinkedHashMap<>();
        for (String surfaceName : surfaceNames) {
            result.put(surfaceName, SurfaceState.builder()
                    .surfaceType(SurfaceType.fromString(surfaceName))
                    .status(parseStatus(strFromMap(entries, surfaceName + ":status")))
                    .retryCount(parseInt(strFromMap(entries, surfaceName + ":retryCount")))
                    .lastError(emptyToNull(strFromMap(entries, surfaceName + ":lastError")))
                    .completedAt(parseInstant(strFromMap(entries, surfaceName + ":completedAt")))
                    .lastAttemptAt(parseInstant(strFromMap(entries, surfaceName + ":lastAttemptAt")))
                    .build());
        }
        return result;
    }

    @Override
    public boolean isAllTerminal(UUID scanId) {
        Map<String, SurfaceState> states = getAllStates(scanId);
        if (states.isEmpty()) {
            return false;
        }
        return states.values().stream().allMatch(SurfaceState::isTerminal);
    }

    @Override
    public List<String> getSuccessfulSurfaces(UUID scanId) {
        return getAllStates(scanId).entrySet().stream()
                .filter(e -> e.getValue().isSuccess())
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<String> getFailedSurfaces(UUID scanId) {
        return getAllStates(scanId).entrySet().stream()
                .filter(e -> e.getValue().isFailed())
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public boolean hasSurfaceSucceeded(UUID scanId, SurfaceType surface) {
        return getSurfaceState(scanId, surface).isSuccess();
    }

    @Override
    public boolean hasSurfaceFailed(UUID scanId, SurfaceType surface) {
        return getSurfaceState(scanId, surface).isFailed();
    }



    /** Reads just the status field for the given surface — used in guard checks. */
    private SurfaceStatus readStatus(String key, SurfaceType surface) {
        Object raw = redisTemplate.opsForHash().get(key, field(surface, "status"));
        return raw != null ? parseStatus(raw.toString()) : null;
    }

    private SurfaceStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return SurfaceStatus.PENDING;
        try {
            return SurfaceStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown SurfaceStatus value '{}', defaulting to PENDING", value);
            return SurfaceStatus.PENDING;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn("Could not parse Instant from '{}', returning null", value);
            return null;
        }
    }

    private int parseInt(String value) {
        if (value == null)
            return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String sanitise(String message) {
        return message != null ? message : "Unknown error";
    }

    private String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Safe indexed get from a List<Object> returned by HMGET. */
    private String str(List<Object> list, int index) {
        if (list == null || index >= list.size())
            return null;
        Object v = list.get(index);
        return v != null ? v.toString() : null;
    }

    /** Safe get from HGETALL map. */
    private String strFromMap(Map<Object, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}