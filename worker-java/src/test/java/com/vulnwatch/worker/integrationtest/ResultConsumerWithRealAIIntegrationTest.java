package com.vulnwatch.worker.integrationtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import com.vulnwatch.worker.config.RedisConfig;
import com.vulnwatch.worker.consumer.ResultConsumer;
import com.vulnwatch.worker.entity.Finding;
import com.vulnwatch.worker.entity.Scan;
import com.vulnwatch.worker.enums.*;
import com.vulnwatch.worker.event.SurfaceResultEvent;
import com.vulnwatch.worker.interfaces.SurfaceStateManager;
import com.vulnwatch.worker.queue.DeadLetterQueueHandler;
import com.vulnwatch.worker.queue.ScanCompletionPublisher;
import com.vulnwatch.worker.queue.SurfaceEventPublisher;
import com.vulnwatch.worker.repository.FindingRepository;
import com.vulnwatch.worker.repository.ScanRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for ResultConsumer with REAL OpenAI API.
 *
 * <p>Tagged {@code @Tag("real-ai")} so it is excluded from standard CI runs.
 * To include it: {@code mvn test -Dgroups=real-ai}
 *
 * <p>Set your OpenAI API key before running:
 * <pre>export OPENAI_API_KEY=sk-proj-your-actual-key-here</pre>
 *
 * <p><b>Warning:</b> makes real API calls (~$0.01–0.03 per run).
 */
@Tag("real-ai")
@Testcontainers
@SpringBootTest
class ResultConsumerWithRealAIIntegrationTest {

    // ==================== TESTCONTAINERS ====================

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("vulnwatch_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.ai.openai.api-key", () -> System.getenv("OPENAI_API_KEY"));
        registry.add("spring.ai.openai.model", () -> "gpt-4");
        registry.add("spring.ai.openai.temperature", () -> "0.2");
        registry.add("spring.ai.openai.max-tokens", () -> "2000");
    }

    // ==================== AUTOWIRED BEANS ====================

    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private ScanRepository scanRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SurfaceStateManager stateManager;
    @Autowired private SurfaceEventPublisher surfaceEventPublisher;
    @Autowired private ScanCompletionPublisher scanCompletionPublisher;
    @Autowired private DeadLetterQueueHandler dlqHandler;
    @Autowired private ResultConsumer resultConsumer;

    // ==================== FIELDS ====================

    private ExecutorService consumerExecutor;
    private UUID scanId;
    private Scan scan;

    // ==================== LIFECYCLE ====================

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isEmpty(),
                "OPENAI_API_KEY not set — skipping real AI integration tests. " +
                        "Set it with: export OPENAI_API_KEY=sk-proj-your-key"
        );

        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        consumerExecutor = Executors.newSingleThreadExecutor();
        org.springframework.test.util.ReflectionTestUtils.setField(resultConsumer, "consumerExecutor", consumerExecutor);
        org.springframework.test.util.ReflectionTestUtils.setField(resultConsumer, "maxRetries", 3);
        org.springframework.test.util.ReflectionTestUtils.setField(resultConsumer, "shutdownTimeoutSeconds", 15);

        scanId = UUID.randomUUID();
        scan = Scan.builder()
                .id(scanId)
                .userId(UUID.randomUUID())
                .status(ScanStatus.QUEUED)
                .targetType(TargetType.DOMAIN)
                .build();
        scanRepository.save(scan);

        // Ensure the stream key exists before creating the consumer group —
        // some Redis versions reject XGROUP CREATE on a non-existent key without MKSTREAM.
        redisTemplate.opsForStream().add(
                RedisConfig.Keys.SURFACE_RESULT_STREAM,
                Map.of("_init", "true")
        );
        try {
            redisTemplate.opsForStream().createGroup(
                    RedisConfig.Keys.SURFACE_RESULT_STREAM,
                    ReadOffset.latest(),
                    RedisConfig.CONSUMER_GROUP
            );
        } catch (Exception ignored) {
            // Group already exists from a previous test — fine.
        }

        System.out.println("\n========================================");
        System.out.println("✅ Test setup complete. Using REAL OpenAI API.");
        System.out.println("⚠️  This test will incur OpenAI API costs.");
        System.out.println("========================================\n");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        stopConsumer();

        // Shutdown executor before cleaning Redis to avoid a racing consumer
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            consumerExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        Set<String> keys = redisTemplate.keys("scan:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(RedisConfig.Keys.SURFACE_RESULT_STREAM);
        redisTemplate.delete(RedisConfig.Keys.RETRY_ZSET);
        redisTemplate.delete(RedisConfig.Keys.DEAD_LETTER_LIST);

        findingRepository.deleteAll();
        scanRepository.deleteAll();

        System.out.println("✅ Test cleanup complete.\n");
    }

    // ==================== CONSUMER HELPERS ====================

    private void startConsumer() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                resultConsumer, "running", new AtomicBoolean(true));
        consumerExecutor.submit(() -> {
            try {
                var method = ResultConsumer.class.getDeclaredMethod("consumeLoop");
                method.setAccessible(true);
                method.invoke(resultConsumer);
            } catch (Exception ignored) {
                // Consumer was stopped intentionally.
            }
        });
    }

    private void stopConsumer() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                resultConsumer, "running", new AtomicBoolean(false));
    }

    /**
     * Publishes an event via the real {@link SurfaceEventPublisher} bean so the
     * full offload/serialisation path is exercised, consistent with production.
     */
    private void publishEvent(SurfaceResultEvent event) {
        surfaceEventPublisher.publish(event);
    }

    // ==================== TESTS ====================

    @Test
    @DisplayName("REAL AI: DNS scan — should generate finding about missing DMARC")
    void testRealAiDnsMissingDmarc() {
        // Only one surface in play for this test
        stateManager.initSurfaces(scanId, List.of(SurfaceType.DNS));

        Map<String, Object> rawData = Map.of(
                "domain", "example.com",
                "has_spf", true,
                "has_dmarc", false,
                "dnssec_enabled", false,
                "a_records", List.of("93.184.216.34"),
                "mx_records", List.of("mail.example.com"),
                "spf_record", "v=spf1 include:_spf.google.com ~all"
        );

        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.DNS, rawData, 0));
        startConsumer();

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            var state = stateManager.getSurfaceState(scanId, SurfaceType.DNS);
            assertThat(state.getStatus()).isEqualTo("SUCCESS");

            List<Finding> findings = findingRepository.findByScanId(scanId);
            assertThat(findings).isNotEmpty();

            Finding finding = findings.stream()
                    .filter(f -> f.getSurface() == SurfaceType.DNS)
                    .findFirst()
                    .orElse(null);

            assertThat(finding).isNotNull();
            assertThat(finding.getAiExplanation()).isNotBlank();
            assertThat(finding.getRemediationSteps()).isNotBlank();

            System.out.println("\n📋 AI Generated DNS Finding:");
            System.out.println("   Title: " + finding.getTitle());
            System.out.println("   Severity: " + finding.getSeverity());
            System.out.println("   Explanation: " + finding.getAiExplanation());
            System.out.println("   Remediation: " + finding.getRemediationSteps());
        });
    }

    @Test
    @DisplayName("REAL AI: SSL scan — should generate finding about expiring certificate")
    void testRealAiSslExpiring() {
        stateManager.initSurfaces(scanId, List.of(SurfaceType.SSL));

        Map<String, Object> rawData = Map.of(
                "expiry_days", 25,
                "issuer", "Let's Encrypt",
                "weak_protocols", List.of("TLSv1.0"),
                "valid", true,
                "expiry_date", "2026-06-15T00:00:00Z"
        );

        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.SSL, rawData, 0));
        startConsumer();

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Finding> findings = findingRepository.findByScanId(scanId);
            assertThat(findings).isNotEmpty();

            Finding finding = findings.stream()
                    .filter(f -> f.getSurface() == SurfaceType.SSL)
                    .findFirst()
                    .orElse(null);

            assertThat(finding).isNotNull();
            // satisfiesAnyOf is the correct AssertJ API for OR conditions on the same subject
            assertThat(finding.getTitle()).satisfiesAnyOf(
                    t -> assertThat(t).containsIgnoringCase("expir"),
                    t -> assertThat(t).containsIgnoringCase("certificate")
            );
            assertThat(finding.getRemediationSteps()).containsIgnoringCase("renew");

            System.out.println("\n📋 AI Generated SSL Finding:");
            System.out.println("   Title: " + finding.getTitle());
            System.out.println("   Severity: " + finding.getSeverity());
            System.out.println("   Remediation: " + finding.getRemediationSteps());
        });
    }

    @Test
    @DisplayName("REAL AI: HTTP headers scan — should generate finding about missing CSP")
    void testRealAiHttpMissingCsp() {
        stateManager.initSurfaces(scanId, List.of(SurfaceType.HTTP_HEADERS));

        Map<String, Object> rawData = Map.of(
                "has_csp", false,
                "has_hsts", true,
                "has_x_frame_options", true,
                "has_x_content_type_options", true,
                "server", "nginx/1.18.0",
                "status_code", 200
        );

        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.HTTP_HEADERS, rawData, 0));
        startConsumer();

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Finding> findings = findingRepository.findByScanId(scanId);
            assertThat(findings).isNotEmpty();

            Finding finding = findings.stream()
                    .filter(f -> f.getSurface() == SurfaceType.HTTP_HEADERS)
                    .findFirst()
                    .orElse(null);

            assertThat(finding).isNotNull();
            assertThat(finding.getTitle()).satisfiesAnyOf(
                    t -> assertThat(t).containsIgnoringCase("CSP"),
                    t -> assertThat(t).containsIgnoringCase("Content Security")
            );

            System.out.println("\n📋 AI Generated HTTP Headers Finding:");
            System.out.println("   Title: " + finding.getTitle());
            System.out.println("   Severity: " + finding.getSeverity());
            System.out.println("   Explanation: " + finding.getAiExplanation());
        });
    }

    @Test
    @DisplayName("REAL AI: Multiple surfaces — should generate separate findings for each")
    void testRealAiMultipleSurfaces() {
        stateManager.initSurfaces(scanId, List.of(SurfaceType.DNS, SurfaceType.SSL));

        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.DNS,
                Map.of("has_dmarc", false, "dnssec_enabled", false), 0));
        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.SSL,
                Map.of("expiry_days", 25, "weak_protocols", List.of("TLSv1.0")), 0));

        startConsumer();

        await().atMost(90, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.DNS)).isTrue();
            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.SSL)).isTrue();

            List<Finding> findings = findingRepository.findByScanId(scanId);
            assertThat(findings).hasSize(2);
            assertThat(findings).anyMatch(f -> f.getSurface() == SurfaceType.DNS);
            assertThat(findings).anyMatch(f -> f.getSurface() == SurfaceType.SSL);

            System.out.println("\n📋 Multiple Surface Findings:");
            findings.forEach(f -> System.out.println("   - [" + f.getSurface() + "] " + f.getTitle()));
        });
    }

    @Test
    @DisplayName("REAL AI: Complete scan — all surfaces succeed and security score is calculated")
    void testRealAiCompleteScan() {
        stateManager.initSurfaces(scanId, List.of(SurfaceType.DNS, SurfaceType.SSL, SurfaceType.HTTP_HEADERS));

        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.DNS,
                Map.of("has_dmarc", false, "dnssec_enabled", false), 0));
        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.SSL,
                Map.of("expiry_days", 25), 0));
        publishEvent(SurfaceResultEvent.success(scanId, SurfaceType.HTTP_HEADERS,
                Map.of("has_csp", false), 0));

        startConsumer();

        await().atMost(120, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(stateManager.isAllTerminal(scanId)).isTrue();
            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.DNS)).isTrue();
            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.SSL)).isTrue();
            assertThat(stateManager.hasSurfaceSucceeded(scanId, SurfaceType.HTTP_HEADERS)).isTrue();

            Scan updatedScan = scanRepository.findById(scanId).orElseThrow();
            assertThat(updatedScan.getSecurityScore()).isBetween(0, 100);

            List<Finding> findings = findingRepository.findByScanId(scanId);
            assertThat(findings).hasSize(3);

            System.out.println("\n📋 Complete Scan Results:");
            System.out.println("   Security Score: " + updatedScan.getSecurityScore());
            findings.forEach(f -> System.out.println("   - [" + f.getSurface() + "] " + f.getTitle()));
        });
    }
}