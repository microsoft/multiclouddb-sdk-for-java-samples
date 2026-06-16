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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portable change-feed sample demonstrating the SDK's pull-mode change-feed
 * primitives: {@code listCursors}, {@code readChanges}, and the opaque
 * cursor-token round-trip.
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
 *   <li><b>DynamoDB</b> — table needs {@code StreamSpecification(NEW_AND_OLD_IMAGES)}
 *       (must be set out-of-band).</li>
 *   <li><b>Spanner</b> — needs a {@code CHANGE STREAM ... OPTIONS(
 *       value_capture_type = 'NEW_ROW')} (must be created out-of-band).</li>
 * </ul>
 * This sample uses a dedicated database ({@code multiclouddb-sdk-for-java-changefeed})
 * and container ({@code change-feed-demo}) to avoid conflicting with non-AVAD
 * containers created by other sample runs. Both names are configurable via
 * the {@code multiclouddb.database} / {@code multiclouddb.collection}
 * properties in {@code change-feed-cosmos.properties}.
 */
public class ChangeFeedSample {

    private static final String DEFAULT_CONFIG = "change-feed-cosmos.properties";
    private static final String DEFAULT_DATABASE = "multiclouddb-sdk-for-java-changefeed";
    private static final String DEFAULT_COLLECTION = "change-feed-demo";
    private static final int WRITER_OPERATIONS = 6;

    public static void main(String[] args) throws Exception {
        ConfigLoader.AppConfig appConfig = ConfigLoader.load(DEFAULT_CONFIG);
        ProviderId provider = appConfig.sdk().provider();

        System.out.println("=== Multicloud DB Change Feed Sample ===");
        System.out.println("Provider: " + provider.displayName());
        boolean isCosmos = ProviderId.COSMOS.equals(provider);
        boolean isCosmosEmulator = isCosmos && ChangeFeedSampleSupport.isLocalEndpoint(
                appConfig.sdk().connection().get("endpoint"));
        if (isCosmos) {
            System.out.println("Mode    : " + (isCosmosEmulator ? "EMULATOR" : "LIVE"));
        }
        System.out.println();

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

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdk())) {

            // Bail out early if the active provider doesn't support change
            // feed (Capability.CHANGE_FEED is gated to Cosmos in the SDK).
            // This is the portable way to detect a Capability-gated feature.
            CapabilitySet caps = client.capabilities();
            if (!caps.isSupported(Capability.CHANGE_FEED)) {
                System.err.println("Change feed is not supported on "
                        + provider.displayName()
                        + ". Use a Cosmos config (e.g. change-feed-cosmos.properties).");
                return;
            }

            // === 1. Provision schema ===
            // On a live Continuous-Backup account this creates a plain
            // container — AVAD is available automatically because CB is
            // enabled at the account level. On the emulator the container
            // was already created above and this is a no-op verification.
            System.out.println("--- Provisioning '" + database + "/" + collection + "' ---");
            client.ensureDatabase(database);
            client.ensureContainer(address);
            System.out.println();

            // === 2. List cursors at the live tip ===
            // No events committed before this call will be surfaced.
            System.out.println("--- listCursors (live tip) ---");
            List<ChangeFeedCursor> cursors = client.listCursors(address);
            System.out.println("  Discovered " + cursors.size() + " partition cursor(s)");
            for (int i = 0; i < cursors.size(); i++) {
                String token = cursors.get(i).toToken();
                System.out.println("  cursor-" + i + ": " + token.substring(0, Math.min(80, token.length())) + "…");
            }
            if (cursors.isEmpty()) {
                System.err.println("  No partition cursors returned. The container exists but the "
                        + "provider did not report any feed ranges — likely a configuration or "
                        + "change-feed-mode mismatch. Aborting sample.");
                return;
            }
            System.out.println();

            // === 3. Spawn a writer thread that produces CREATE/UPDATE/DELETE events ===
            AtomicBoolean writerDone = new AtomicBoolean(false);
            Thread writer = new Thread(() -> runWriter(client, address, writerDone),
                    "change-feed-writer");
            writer.setDaemon(true);
            writer.start();

            // === 4. Drain change events until the writer is done AND every cursor is caught up ===
            System.out.println("--- readChanges (consuming events) ---");
            int totalEvents = drainAll(client, address, cursors, writerDone);
            writer.join();
            System.out.println();
            System.out.println("  Total events observed: " + totalEvents);
            System.out.println();

            // === 5. Cursor-token round-trip: persist + resume ===
            System.out.println("--- Cursor token round-trip ---");
            List<ChangeFeedCursor> tipCursors = client.listCursors(address);
            if (tipCursors.isEmpty()) {
                System.err.println("  No partition cursors returned; skipping token round-trip.");
            } else {
                ChangeFeedCursor liveTip = tipCursors.get(0);
                String token = liveTip.toToken();
                System.out.println("  Persisted token (truncated): " + token.substring(0, Math.min(60, token.length())) + "...");
                ChangeFeedCursor resumed = ChangeFeedCursor.fromToken(token);
                ChangeFeedPage page = client.readChanges(address, resumed);
                System.out.println("  Resumed cursor read " + page.events().size()
                        + " event(s); hasMore=" + page.hasMore()
                        + ", terminal=" + page.isTerminal());
            }

            System.out.println();
            System.out.println("=== Sample complete ===");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    static int parseThroughputRU(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            System.err.println("multiclouddb.throughput=" + raw
                    + " is not a valid integer; using Cosmos default.");
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
                System.out.println("  [writer] upsert cf-" + i);
                Thread.sleep(200);
            }

            // One UPDATE on cf-1 to demonstrate the UPDATE event type.
            MulticloudDbKey first = MulticloudDbKey.of("cf-1", "cf-1");
            client.upsert(address, first, Map.of("title", "Event 1 (updated)", "iteration", 99));
            System.out.println("  [writer] update cf-1");
            Thread.sleep(200);

            // One DELETE to demonstrate the DELETE event type. DELETE events
            // require AVAD on Cosmos, which is enabled here either by the
            // emulator pre-provisioning above (Cosmos emulator path) or
            // implicitly via Continuous Backup (live Cosmos path).
            client.delete(address, first);
            System.out.println("  [writer] delete cf-1");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            done.set(true);
        }
    }

    // ── Consumer loop ────────────────────────────────────────────────────────

    /** Round-robin across cursors until the writer is done AND every cursor returns hasMore=false. */
    private static int drainAll(MulticloudDbClient client,
                                ResourceAddress address,
                                List<ChangeFeedCursor> initial,
                                AtomicBoolean writerDone) throws InterruptedException {
        List<ChangeFeedCursor> cursors = new ArrayList<>(initial);
        int total = 0;
        long deadline = System.currentTimeMillis() + 30_000L; // hard safety bound

        while (System.currentTimeMillis() < deadline) {
            boolean anyHasMore = false;
            for (int i = 0; i < cursors.size(); i++) {
                ChangeFeedPage page = client.readChanges(address, cursors.get(i));
                for (ChangeEvent ev : page.events()) {
                    System.out.printf("  [consumer] cursor-%d  %-6s %s @ %s%n",
                            i, ev.type(), ev.key(), ev.commitTimestamp());
                    total++;
                }
                cursors.set(i, page.nextCursor());
                if (page.hasMore()) anyHasMore = true;
            }
            if (writerDone.get() && !anyHasMore) {
                // Writer is done and every cursor reports caught-up — drain complete.
                return total;
            }
            if (!anyHasMore) {
                Thread.sleep(250); // back off when at the live tip
            }
        }
        System.out.println("  [consumer] hit 30s safety deadline");
        return total;
    }
}
