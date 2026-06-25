// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.changefeed;

import com.multiclouddb.samples.ConfigLoader;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Portable change-feed sample demonstrating the SDK's pull-mode change-feed
 * primitives: {@code listCursors}, {@code readChanges}, and the opaque
 * cursor-token round-trip.
 * <p>
 * <b>Note:</b> This sample currently supports <b>Azure Cosmos DB only</b>.
 * Other providers will error until they ship change-feed support.
 * <p>
 * Usage:
 *
 * <pre>
 *   # Default: Cosmos DB emulator (uses change-feed-cosmos.properties)
 *   java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedSample
 *
 *   # Live Cosmos account (master-key auth)
 *   #   1. cp src/main/resources/change-feed-cosmos-cloud.properties.template \
 *   #         src/main/resources/change-feed-cosmos-cloud.properties
 *   #      (then fill in endpoint+key — the runtime file must live under
 *   #       src/main/resources/ so it ends up on the fat-jar classpath; the
 *   #       runtime file is gitignored)
 *   #   2. mvn clean package -DskipTests
 *   java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedSample
 * </pre>
 *
 * <h3>Provisioning</h3>
 * Change feed requires provider-side setup that {@code ensureContainer} does
 * NOT enable by default:
 * <ul>
 *   <li><b>Cosmos DB (live, Continuous-Backup account)</b> — when the account
 *       has Continuous Backup enabled, the All-Versions-and-Deletes (AVAD)
 *       change feed is available automatically on every container; no
 *       explicit {@code ChangeFeedPolicy} is required. The sample just calls
 *       {@code ensureContainer} for a plain container and reads in AVAD mode.
 *       Verify CB with
 *       {@code az cosmosdb show --query backupPolicy.type -o tsv} (expect
 *       {@code Continuous}).</li>
 *   <li><b>Cosmos DB (local emulator)</b> — the emulator does not support
 *       Continuous Backup. The sample detects {@code localhost}/{@code 127.0.0.1}
 *       endpoints and pre-provisions the AVAD container directly via the
 *       Cosmos SDK with a 10-minute {@code ChangeFeedPolicy} (the emulator's
 *       hard ceiling).</li>
 * </ul>
 * This sample uses a dedicated database ({@code multiclouddb-sdk-for-java-changefeed})
 * and container ({@code change-feed-demo}) to avoid conflicting with non-AVAD
 * containers created by other sample runs. Both names are configurable via
 * the {@code multiclouddb.database} / {@code multiclouddb.collection}
 * properties in {@code change-feed-cosmos.properties}.
 */
public class ChangeFeedSample {

    private static final Logger log = LoggerFactory.getLogger(ChangeFeedSample.class);

    private static final String DEFAULT_CONFIG = "change-feed-cosmos.properties";
    private static final String DEFAULT_DATABASE = "multiclouddb-sdk-for-java-changefeed";
    private static final String DEFAULT_COLLECTION = "change-feed-demo";
    private static final int WRITER_OPERATIONS = 6;

    public static void main(String[] args) throws Exception {
        ConfigLoader.AppConfig appConfig = ConfigLoader.load(DEFAULT_CONFIG);
        ProviderId provider = appConfig.sdk().provider();

        log.info("=== Multicloud DB Change Feed Sample ===");
        log.info("Provider: {}", provider.displayName());

        // *** TEMPORARY: Only Cosmos DB is supported for change feed ***
        if (!ProviderId.COSMOS.equals(provider)) {
            log.error("Change-feed samples currently support Cosmos DB only.");
            log.error("Set multiclouddb.provider=cosmos in your config. Other providers will be supported in a future release.");
            System.exit(1);
            return;
        }

        boolean isCosmosEmulator = ChangeFeedSampleSupport.isLocalEndpoint(
                appConfig.sdk().connection().get("endpoint"));
        log.info("Mode    : {}", isCosmosEmulator ? "EMULATOR" : "LIVE");

        String database = appConfig.property("multiclouddb.database", DEFAULT_DATABASE);
        String collection = appConfig.property("multiclouddb.collection", DEFAULT_COLLECTION);
        ResourceAddress address = new ResourceAddress(database, collection);

        // Emulator-only workaround: pre-provision AVAD container directly via
        // the Cosmos SDK with a 10-min policy (emulator's hard ceiling). On a
        // live Continuous-Backup account the SDK's portable ensureContainer()
        // creates a plain container and AVAD is available automatically (see
        // sample javadoc).
        if (isCosmosEmulator) {
            int throughputRU = parseThroughputRU(
                    appConfig.property("multiclouddb.throughput", null));
            ChangeFeedSampleSupport.provisionCosmosAvadContainer(
                    appConfig, database, collection, throughputRU);
        }

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdkWithoutExtendedRetention())) {

            // Bail out early if the active provider doesn't support change feed.
            CapabilitySet caps = client.capabilities();
            if (!caps.isSupported(Capability.CHANGE_FEED)) {
                log.error("Change feed is not supported on {}.", provider.displayName());
                return;
            }

            // === 1. Provision schema ===
            // On a live Continuous-Backup account this creates a plain
            // container — AVAD is available automatically because CB is
            // enabled at the account level. On the emulator the container
            // was already created above and this is a no-op verification.
            log.info("--- Provisioning '{}/{}' ---", database, collection);
            if (ChangeFeedSampleSupport.usesEntraId(appConfig)) {
                // Entra ID is granted only a data-plane RBAC role, so the
                // control-plane ensureDatabase()/ensureContainer() calls (which
                // run createDatabaseIfNotExists under the hood) would fail. The
                // database and container must be pre-created — see
                // README-change-feed.md → "Cosmos DB Cloud Setup".
                log.info("  Entra ID auth detected — skipping ensureDatabase()/ensureContainer(); "
                        + "expecting pre-created database '{}' and container '{}'.", database, collection);
            } else {
                client.ensureDatabase(database);
                client.ensureContainer(address);
            }

            // === 2. List cursors at the live tip ===
            // No events committed before this call will be surfaced.
            log.info("--- listCursors (live tip) ---");
            List<ChangeFeedCursor> cursors = client.listCursors(address);
            log.info("  Discovered {} partition cursor(s)", cursors.size());
            for (int i = 0; i < cursors.size(); i++) {
                String token = cursors.get(i).toToken();
                log.info("  cursor-{}: {}…", i, token.substring(0, Math.min(80, token.length())));
            }
            if (cursors.isEmpty()) {
                log.error("  No partition cursors returned. The container exists but the "
                        + "provider did not report any feed ranges — likely a configuration or "
                        + "change-feed-mode mismatch. Aborting sample.");
                return;
            }

            // === 3. Spawn a writer thread that produces CREATE/UPDATE/DELETE events ===
            AtomicBoolean writerDone = new AtomicBoolean(false);
            Thread writer = new Thread(() -> runWriter(client, address, writerDone),
                    "change-feed-writer");
            writer.setDaemon(true);
            writer.start();

            // === 4. Drain change events until the writer is done AND every cursor is caught up ===
            log.info("--- readChanges (consuming events) ---");
            int totalEvents = drainAll(client, address, cursors, writerDone);
            writer.join();
            log.info("  Total events observed: {}", totalEvents);

            // === 5. Cursor-token round-trip: persist + resume ===
            log.info("--- Cursor token round-trip ---");
            List<ChangeFeedCursor> tipCursors = client.listCursors(address);
            if (tipCursors.isEmpty()) {
                log.error("  No partition cursors returned; skipping token round-trip.");
            } else {
                ChangeFeedCursor liveTip = tipCursors.get(0);
                String token = liveTip.toToken();
                log.info("  Persisted token (truncated): {}...", token.substring(0, Math.min(60, token.length())));
                ChangeFeedCursor resumed = ChangeFeedCursor.fromToken(token);
                ChangeFeedPage page = client.readChanges(address, resumed);
                log.info("  Resumed cursor read {} event(s); hasMore={}, terminal={}",
                        page.events().size(), page.hasMore(), page.isTerminal());
            }

            log.info("=== Sample complete ===");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    static int parseThroughputRU(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            log.warn("multiclouddb.throughput={} is not a valid integer; using Cosmos default.", raw);
            return 0;
        }
    }

    // ── Writer thread ────────────────────────────────────────────────────────

    private static void runWriter(MulticloudDbClient client,
                                  ResourceAddress address,
                                  AtomicBoolean done) {
        try {
            // Small pause so the consumer hits the loop first and we exercise
            // the "wait at live tip" path at least once.
            Thread.sleep(500);

            for (int i = 1; i <= WRITER_OPERATIONS; i++) {
                MulticloudDbKey key = MulticloudDbKey.of("cf-" + i, "cf-" + i);
                Map<String, Object> doc = Map.of(
                        "title", "Event " + i,
                        "iteration", i);
                client.upsert(address, key, doc);
                log.info("  [writer] upsert cf-{}", i);
                Thread.sleep(200);
            }

            // One UPDATE on cf-1 to demonstrate the UPDATE event type.
            MulticloudDbKey first = MulticloudDbKey.of("cf-1", "cf-1");
            client.upsert(address, first, Map.of("title", "Event 1 (updated)", "iteration", 99));
            log.info("  [writer] update cf-1");
            Thread.sleep(200);

            // One DELETE to demonstrate the DELETE event type. DELETE events
            // require AVAD on Cosmos, which is enabled here either by the
            // emulator pre-provisioning above (Cosmos emulator path) or
            // implicitly via Continuous Backup (live Cosmos path).
            client.delete(address, first);
            log.info("  [writer] delete cf-1");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            done.set(true);
        }
    }

    // ── Consumer: one thread per cursor (parallel partition consumption) ────

    /**
     * Spawns one consumer thread per cursor. Each thread independently polls
     * its partition until the writer signals done AND the cursor reports no
     * more events. Returns total events consumed across all threads.
     */
    private static int drainAll(MulticloudDbClient client,
                                ResourceAddress address,
                                List<ChangeFeedCursor> initial,
                                AtomicBoolean writerDone) throws InterruptedException {
        AtomicInteger total = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(initial.size());
        List<Thread> consumers = new ArrayList<>(initial.size());

        for (int i = 0; i < initial.size(); i++) {
            final int cursorIndex = i;
            final ChangeFeedCursor startCursor = initial.get(i);
            Thread consumer = new Thread(() -> {
                ChangeFeedCursor cursor = startCursor;
                long deadline = System.currentTimeMillis() + 30_000L;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        ChangeFeedPage page = client.readChanges(address, cursor);
                        for (ChangeEvent ev : page.events()) {
                            log.info("  [cursor-{}] {} {} @ {}",
                                    cursorIndex,
                                    String.format("%-6s", ev.type()),
                                    ev.key(), ev.commitTimestamp());
                            total.incrementAndGet();
                        }
                        cursor = page.nextCursor();
                        if (writerDone.get() && !page.hasMore()) {
                            break;
                        }
                        if (!page.hasMore()) {
                            Thread.sleep(250);
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            }, "cursor-consumer-" + cursorIndex);
            consumer.setDaemon(true);
            consumers.add(consumer);
            consumer.start();
        }

        log.info("  Started {} parallel consumer thread(s).", consumers.size());
        allDone.await();
        return total.get();
    }
}
