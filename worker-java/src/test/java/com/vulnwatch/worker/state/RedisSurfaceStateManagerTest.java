package com.vulnwatch.worker.state;

import com.vulnwatch.worker.enums.SurfaceStatus;
import com.vulnwatch.worker.enums.SurfaceType;
import com.vulnwatch.worker.models.SurfaceState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("ALL")
@ExtendWith(MockitoExtension.class)
class RedisSurfaceStateManagerTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;

    private RedisSurfaceStateManager stateManager;
    private UUID scanId;
    private String key;

    @BeforeEach
    void setUp() {
        stateManager = new RedisSurfaceStateManager(redisTemplate);
        scanId = UUID.randomUUID();
        key = "scan:" + scanId + ":state";
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    // ── initSurfaces ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("initSurfaces")
    class InitSurfaces {

        @Test
        @DisplayName("creates initial state when scan is new")
        void initSurfaces_newScan_createsState() {
            when(hashOps.putIfAbsent(eq(key), eq("initialized"), eq("true"))).thenReturn(true);

            stateManager.initSurfaces(scanId, List.of(SurfaceType.DNS, SurfaceType.SSL));

            verify(hashOps).putAll(eq(key), anyMap());
        }

        @Test
        @DisplayName("skips when already initialised")
        void initSurfaces_alreadyInit_skips() {
            when(hashOps.putIfAbsent(eq(key), eq("initialized"), eq("true"))).thenReturn(false);

            stateManager.initSurfaces(scanId, List.of(SurfaceType.DNS));

            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("initial state map contains PENDING status and 0 retryCount per surface")
        void initSurfaces_correctFields() {
            List<SurfaceType> surfaces = List.of(SurfaceType.DNS, SurfaceType.SSL, SurfaceType.HTTP_HEADERS);
            when(hashOps.putIfAbsent(eq(key), eq("initialized"), eq("true"))).thenReturn(true);

            stateManager.initSurfaces(scanId, surfaces);

            @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(key), captor.capture());

            Map<String, Object> state = captor.getValue();
            assertThat(state).containsEntry("DNS:status", "PENDING");
            assertThat(state).containsEntry("DNS:retryCount", "0");
            assertThat(state).containsEntry("DNS:lastError", "");
            assertThat(state).containsEntry("SSL:status", "PENDING");
            assertThat(state).containsEntry("HTTP_HEADERS:status", "PENDING");
        }
    }


    @SuppressWarnings("unchecked")
    @Nested
    @DisplayName("updateSuccess")
    class UpdateSuccess {

        @Test
        @DisplayName("marks surface SUCCESS when PENDING")
        void updateSuccess_fromPending_marksSuccess() {
            when(hashOps.get(key, "DNS:status")).thenReturn("PENDING");

            stateManager.updateSuccess(scanId, SurfaceType.DNS);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(key), captor.capture());
            assertThat(captor.getValue()).containsEntry("DNS:status", "SUCCESS");
            assertThat(captor.getValue()).containsKey("DNS:completedAt");
        }

        @Test
        @DisplayName("no-op when already SUCCESS")
        void updateSuccess_alreadySuccess_noOp() {
            when(hashOps.get(key, "DNS:status")).thenReturn("SUCCESS");

            stateManager.updateSuccess(scanId, SurfaceType.DNS);

            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("no-op when already PERMANENTLY_FAILED")
        void updateSuccess_alreadyPermanentlyFailed_noOp() {
            when(hashOps.get(key, "DNS:status")).thenReturn("PERMANENTLY_FAILED");

            stateManager.updateSuccess(scanId, SurfaceType.DNS);

            verify(hashOps, never()).putAll(anyString(), anyMap());
        }
    }

    // ── updateFailure ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateFailure")
    class UpdateFailure {

        @Test
        @DisplayName("marks surface FAILED and records error message")
        void updateFailure_fromPending_marksFailedWithError() {
            when(hashOps.get(key, "DNS:status")).thenReturn("PENDING");

            stateManager.updateFailure(scanId, SurfaceType.DNS, "Connection timeout");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(key), captor.capture());
            assertThat(captor.getValue()).containsEntry("DNS:status", "FAILED");
            assertThat(captor.getValue()).containsEntry("DNS:lastError", "Connection timeout");
            assertThat(captor.getValue()).containsKey("DNS:lastAttemptAt");
        }

        @Test
        @DisplayName("no-op when already SUCCESS")
        void updateFailure_alreadySuccess_noOp() {
            when(hashOps.get(key, "DNS:status")).thenReturn("SUCCESS");

            stateManager.updateFailure(scanId, SurfaceType.DNS, "Timeout");

            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("null error message is replaced with default")
        void updateFailure_nullError_usesDefault() {
            when(hashOps.get(key, "DNS:status")).thenReturn("PENDING");

            stateManager.updateFailure(scanId, SurfaceType.DNS, null);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(key), captor.capture());
            assertThat(captor.getValue()).containsEntry("DNS:lastError", "Unknown error");
        }
    }


    @Nested
    @DisplayName("updateRetrying")
    class UpdateRetrying {

        @Test
        @DisplayName("marks surface RETRYING with retry count and error")
        void updateRetrying_setsCorrectFields() {
            stateManager.updateRetrying(scanId, SurfaceType.DNS, 2, "Temporary failure");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(key), captor.capture());
            assertThat(captor.getValue()).containsEntry("DNS:status", "RETRYING");
            assertThat(captor.getValue()).containsEntry("DNS:retryCount", "2");
            assertThat(captor.getValue()).containsEntry("DNS:lastError", "Temporary failure");
            assertThat(captor.getValue()).containsKey("DNS:lastAttemptAt");
        }
    }

    // ── updatePermanentlyFailed ───────────────────────────────────────────────

    @Nested
    @DisplayName("updatePermanentlyFailed")
    class UpdatePermanentlyFailed {

        @Test
        @DisplayName("marks surface PERMANENTLY_FAILED when in FAILED state")
        void updatePermanentlyFailed_fromFailed_marksPermanent() {
            when(hashOps.get(key, "DNS:status")).thenReturn("FAILED");

            stateManager.updatePermanentlyFailed(scanId, SurfaceType.DNS, "Max retries exceeded");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(key), captor.capture());
            assertThat(captor.getValue()).containsEntry("DNS:status", "PERMANENTLY_FAILED");
            assertThat(captor.getValue()).containsEntry("DNS:lastError", "Max retries exceeded");
            assertThat(captor.getValue()).containsKey("DNS:completedAt");
        }

        @Test
        @DisplayName("no-op when already SUCCESS")
        void updatePermanentlyFailed_alreadySuccess_noOp() {
            when(hashOps.get(key, "DNS:status")).thenReturn("SUCCESS");

            stateManager.updatePermanentlyFailed(scanId, SurfaceType.DNS, "Should not update");

            verify(hashOps, never()).putAll(anyString(), anyMap());
        }
    }

    // ── incrementRetryCount ───────────────────────────────────────────────────

    @Nested
    @DisplayName("incrementRetryCount")
    class IncrementRetryCount {

        @Test
        @DisplayName("returns new count from HINCRBY")
        void incrementRetryCount_returnsNewCount() {
            when(hashOps.increment(key, "DNS:retryCount", 1)).thenReturn(3L);

            int result = stateManager.incrementRetryCount(scanId, SurfaceType.DNS);

            assertThat(result).isEqualTo(3);
        }
    }

    // ── getSurfaceState ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSurfaceState")
    class GetSurfaceState {

        @Test
        @DisplayName("maps all fields correctly from HMGET response")
        void getSurfaceState_allFields_mappedCorrectly() {
            // Fix: use Arrays.asList — List.of does not allow nulls
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList(
                            "SUCCESS", "2", "Error message",
                            "2026-01-01T10:00:00Z", "2026-01-01T09:00:00Z"));

            SurfaceState state = stateManager.getSurfaceState(scanId, SurfaceType.DNS);

            // Fix: compare against SurfaceStatus enum, not String
            assertThat(state.getStatus()).isEqualTo(SurfaceStatus.SUCCESS);
            assertThat(state.getRetryCount()).isEqualTo(2);
            assertThat(state.getLastError()).isEqualTo("Error message");
            assertThat(state.getCompletedAt()).isNotNull();
            assertThat(state.getLastAttemptAt()).isNotNull();
        }

        @Test
        @DisplayName("returns PENDING defaults when HMGET returns nulls")
        void getSurfaceState_nullValues_returnsDefaults() {
            // Fix: Arrays.asList allows null elements; List.of throws NPE
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList(null, null, null, null, null));

            SurfaceState state = stateManager.getSurfaceState(scanId, SurfaceType.DNS);

            assertThat(state.getStatus()).isEqualTo(SurfaceStatus.PENDING);
            assertThat(state.getRetryCount()).isEqualTo(0);
            assertThat(state.getLastError()).isNull();
            assertThat(state.getCompletedAt()).isNull();
            assertThat(state.getLastAttemptAt()).isNull();
        }
    }

    // ── getAllStates ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllStates")
    class GetAllStates {

        @Test
        @DisplayName("returns state for all surfaces, excluding the initialized sentinel")
        void getAllStates_returnsSurfacesOnly() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("DNS:status", "SUCCESS");
            entries.put("DNS:retryCount", "0");
            entries.put("DNS:lastError", "");
            entries.put("SSL:status", "FAILED");
            entries.put("SSL:retryCount", "2");
            entries.put("SSL:lastError", "Timeout");
            entries.put("initialized", "true"); // sentinel — must be excluded
            when(hashOps.entries(key)).thenReturn(entries);

            Map<String, SurfaceState> states = stateManager.getAllStates(scanId);

            assertThat(states).hasSize(2);
            assertThat(states).containsKey("DNS");
            assertThat(states).containsKey("SSL");
            assertThat(states).doesNotContainKey("initialized");
            assertThat(states.get("DNS").isSuccess()).isTrue();
            assertThat(states.get("SSL").isFailed()).isTrue();
        }

        @Test
        @DisplayName("returns empty map when hash has no entries")
        void getAllStates_noEntries_returnsEmpty() {
            when(hashOps.entries(key)).thenReturn(Map.of());

            assertThat(stateManager.getAllStates(scanId)).isEmpty();
        }
    }

    // ── isAllTerminal ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isAllTerminal")
    class IsAllTerminal {

        @Test
        @DisplayName("returns true when all surfaces are terminal (SUCCESS or PERMANENTLY_FAILED)")
        void isAllTerminal_allTerminal_returnsTrue() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("DNS:status", "SUCCESS");
            entries.put("DNS:retryCount", "0");
            entries.put("SSL:status", "PERMANENTLY_FAILED");
            entries.put("SSL:retryCount", "3");
            entries.put("initialized", "true");
            when(hashOps.entries(key)).thenReturn(entries);

            assertThat(stateManager.isAllTerminal(scanId)).isTrue();
        }

        @Test
        @DisplayName("returns false when any surface is still PENDING")
        void isAllTerminal_onePending_returnsFalse() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("DNS:status", "SUCCESS");
            entries.put("DNS:retryCount", "0");
            entries.put("SSL:status", "PENDING");
            entries.put("SSL:retryCount", "0");
            entries.put("initialized", "true");
            when(hashOps.entries(key)).thenReturn(entries);

            assertThat(stateManager.isAllTerminal(scanId)).isFalse();
        }

        @Test
        @DisplayName("returns false when any surface is RETRYING")
        void isAllTerminal_oneRetrying_returnsFalse() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("DNS:status", "SUCCESS");
            entries.put("SSL:status", "RETRYING");
            entries.put("initialized", "true");
            when(hashOps.entries(key)).thenReturn(entries);

            assertThat(stateManager.isAllTerminal(scanId)).isFalse();
        }

        @Test
        @DisplayName("returns false when no surfaces are tracked")
        void isAllTerminal_noSurfaces_returnsFalse() {
            when(hashOps.entries(key)).thenReturn(Map.of());

            assertThat(stateManager.isAllTerminal(scanId)).isFalse();
        }
    }

    // ── getSuccessfulSurfaces / getFailedSurfaces ─────────────────────────────

    @Nested
    @DisplayName("Surface filtering")
    class SurfaceFiltering {

        @Test
        @DisplayName("getSuccessfulSurfaces returns only SUCCESS surfaces")
        void getSuccessfulSurfaces_returnsSuccessOnly() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("DNS:status", "SUCCESS");
            entries.put("SSL:status", "FAILED");
            // Fix: use HTTP_HEADERS not HTTP — must match SurfaceType enum names
            entries.put("HTTP_HEADERS:status", "SUCCESS");
            entries.put("initialized", "true");
            when(hashOps.entries(key)).thenReturn(entries);

            List<String> successful = stateManager.getSuccessfulSurfaces(scanId);

            assertThat(successful).containsExactlyInAnyOrder("DNS", "HTTP_HEADERS");
            assertThat(successful).doesNotContain("SSL");
        }

        @Test
        @DisplayName("getFailedSurfaces includes both FAILED and PERMANENTLY_FAILED")
        void getFailedSurfaces_includesBothFailureVariants() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("DNS:status", "FAILED");
            entries.put("SSL:status", "PERMANENTLY_FAILED");
            // Fix: use HTTP_HEADERS not HTTP
            entries.put("HTTP_HEADERS:status", "SUCCESS");
            entries.put("initialized", "true");
            when(hashOps.entries(key)).thenReturn(entries);

            List<String> failed = stateManager.getFailedSurfaces(scanId);

            assertThat(failed).containsExactlyInAnyOrder("DNS", "SSL");
            assertThat(failed).doesNotContain("HTTP_HEADERS");
        }
    }

    // ── hasSurfaceSucceeded / hasSurfaceFailed ────────────────────────────────

    @Nested
    @DisplayName("hasSurface checks")
    class HasSurfaceChecks {

        @Test
        @DisplayName("hasSurfaceSucceeded returns true for SUCCESS surface")
        void hasSurfaceSucceeded_success_returnsTrue() {
            // Fix: these delegate to getSurfaceState which uses multiGet, not entries
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList("SUCCESS", "0", "", null, null));

            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.DNS)).isTrue();
        }

        @Test
        @DisplayName("hasSurfaceSucceeded returns false for non-SUCCESS surface")
        void hasSurfaceSucceeded_failed_returnsFalse() {
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList("FAILED", "1", "err", null, null));

            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.DNS)).isFalse();
        }

        @Test
        @DisplayName("hasSurfaceFailed returns true for FAILED surface")
        void hasSurfaceFailed_failed_returnsTrue() {
            // Fix: stub multiGet, not entries
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList("FAILED", "2", "Timeout", null, null));

            assertThat(stateManager.hasSurfaceFailed(scanId, SurfaceType.DNS)).isTrue();
        }

        @Test
        @DisplayName("hasSurfaceFailed returns true for PERMANENTLY_FAILED surface")
        void hasSurfaceFailed_permanentlyFailed_returnsTrue() {
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList("PERMANENTLY_FAILED", "3", "exhausted", null, null));

            assertThat(stateManager.hasSurfaceFailed(scanId, SurfaceType.DNS)).isTrue();
        }

        @Test
        @DisplayName("hasSurfaceFailed returns false for SUCCESS surface")
        void hasSurfaceFailed_success_returnsFalse() {
            when(hashOps.multiGet(eq(key), anyCollection()))
                    .thenReturn(Arrays.asList("SUCCESS", "0", "", null, null));

            assertThat(stateManager.hasSurfaceFailed(scanId, SurfaceType.DNS)).isFalse();
        }
    }
}