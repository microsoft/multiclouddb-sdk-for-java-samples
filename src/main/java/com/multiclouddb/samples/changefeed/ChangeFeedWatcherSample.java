// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.changefeed;

import com.multiclouddb.samples.ConfigLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Continuous change-feed watcher — keeps running until Ctrl+C and prints
 * every CREATE / UPDATE / DELETE event as it arrives. Use this to observe
 * the change feed while you add or delete items manually (e.g., from the
 * Azure Portal Data Explorer, the Cosmos emulator UI, the DynamoDB console,
 * the Spanner console, or another writer).
 * <p>
 * Unlike {@link ChangeFeedSample}, this watcher does not produce any writes
 * of its own — it only consumes.
 * <p>
 * Usage:
 *
 * <pre>
 *   # Live Cosmos account (master-key auth)
 *   java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
 *
 *   # DynamoDB (local or cloud)
 *   java -Dmulticlouddb.config=change-feed-dynamo.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
 *
 *   # Spanner (emulator or cloud)
 *   java -Dmulticlouddb.config=change-feed-spanner.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
 *
 *   # Local Cosmos emulator (default — no -D flag needed)
 *   java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
 *
 *   # Tune the poll interval (milliseconds; default 1000, minimum 1)
 *   java -Dchangefeed.poll.intervalMs=500 \
 *        -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
 * </pre>
 *
 * <h3>Try it</h3>
 * <ol>
 *   <li>Start the watcher. It prints "Watching … (press Ctrl+C to stop)".</li>
 *   <li>In the provider's console (Azure Portal Data Explorer, DynamoDB
 *       console, Spanner console) create, edit, or delete items in the
 *       target container/table.</li>
 *   <li>Watch the console — each operation surfaces as a
 *       {@code CREATE} / {@code UPDATE} / {@code DELETE} line within the
 *       poll interval, tagged with the cursor index.</li>
 *   <li>Press Ctrl+C to stop. The watcher prints a final tally.</li>
 * </ol>
 *
 * <h3>Provisioning</h3>
 * Same requirements as {@link ChangeFeedSample}:
 * <ul>
 *   <li><b>Live Cosmos</b> — account must have Continuous Backup enabled
 *       (AVAD is then available automatically; a plain container works).</li>
 *   <li><b>Cosmos emulator</b> — the watcher pre-provisions the AVAD
 *       container with a 10-minute retention (the emulator's hard ceiling)
 *       on first run.</li>
 *   <li><b>DynamoDB</b> — table needs {@code StreamSpecification(NEW_AND_OLD_IMAGES)}
 *       enabled.</li>
 *   <li><b>Spanner</b> — needs a change stream:
 *       {@code CREATE CHANGE STREAM ... OPTIONS(value_capture_type='NEW_ROW')}.</li>
 * </ul>
 */
public class ChangeFeedWatcherSample {

    private static final String DEFAULT_CONFIG = "change-feed-cosmos.properties";
    private static final String DEFAULT_DATABASE = "multiclouddb-sdk-for-java-changefeed";
    private static final String DEFAULT_COLLECTION = "change-feed-demo";
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;
    private static final long DEFAULT_POLL_INTERVAL_MS = 1000L;

    public static void main(String[] args) throws Exception {
        long pollIntervalMs = parsePollIntervalMs(
                System.getProperty("changefeed.poll.intervalMs"));

        ConfigLoader.AppConfig appConfig = ConfigLoader.load(DEFAULT_CONFIG);
        ProviderId provider = appConfig.sdk().provider();

        System.out.println("=== Multicloud DB Change Feed Watcher ===");
        System.out.println("Provider     : " + provider.displayName());

        // *** TEMPORARY: Only Cosmos DB is supported for change feed ***
        if (!ProviderId.COSMOS.equals(provider)) {
            System.err.println();
            System.err.println("ERROR: Change-feed samples currently support Cosmos DB only.");
            System.err.println("DynamoDB and Spanner change-feed support is not yet available.");
            System.exit(1);
            return;
        }

        boolean isCosmosEmulator = ChangeFeedSampleSupport.isLocalEndpoint(
                appConfig.sdk().connection().get("endpoint"));
        System.out.println("Mode         : " + (isCosmosEmulator ? "EMULATOR" : "LIVE"));

        String database = appConfig.property("multiclouddb.database", DEFAULT_DATABASE);
        String collection = appConfig.property("multiclouddb.collection", DEFAULT_COLLECTION);
        ResourceAddress address = new ResourceAddress(database, collection);
        System.out.println("Container    : " + database + "/" + collection);
        System.out.println("Poll interval: " + pollIntervalMs + " ms");
        System.out.println();

        if (isCosmosEmulator) {
            int throughputRU = parseThroughputRU(
                    appConfig.property("multiclouddb.throughput", null));
            ChangeFeedSampleSupport.provisionCosmosAvadContainer(
                    appConfig, database, collection, throughputRU);
        }

        AtomicBoolean shutdown = new AtomicBoolean(false);
        AtomicLong totalEvents = new AtomicLong(0);

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdk())) {

            // Bail out early if the active provider doesn't support change feed.
            // Currently only Cosmos DB is supported; DynamoDB and Spanner
            // change-feed support is not yet available.
            CapabilitySet caps = client.capabilities();
            if (!caps.isSupported(Capability.CHANGE_FEED)) {
                System.err.println("Change feed is not supported on "
                        + provider.displayName() + ".");
                return;
            }

            System.out.println("--- Provisioning '" + database + "/" + collection + "' ---");
            client.ensureDatabase(database);
            client.ensureContainer(address);

            // DynamoDB workaround: ensureContainer() creates the table but
            // does not enable DynamoDB Streams. Enable it now so listCursors
            // can discover the stream.
            if (ProviderId.DYNAMO.equals(provider)) {
                String tableName = database + "__" + collection;
                ChangeFeedSampleSupport.enableDynamoStreams(appConfig, tableName);
            }
            System.out.println();

            // Discover one cursor per feed range, positioned at the live tip.
            // Anything written BEFORE this call will not be surfaced.
            List<ChangeFeedCursor> cursors = new ArrayList<>(client.listCursors(address));
            System.out.println("Discovered " + cursors.size() + " partition cursor(s) at the live tip.");
            for (int i = 0; i < cursors.size(); i++) {
                String token = cursors.get(i).toToken();
                System.out.println("  cursor-" + i + ": " + token.substring(0, Math.min(80, token.length())) + "…");
            }
            if (cursors.isEmpty()) {
                System.err.println("No partition cursors returned. The container exists but the "
                        + "provider did not report any feed ranges — likely a configuration or "
                        + "change-feed-mode mismatch. Aborting watcher.");
                return;
            }
            System.out.println();
            System.out.println("Watching " + database + "/" + collection
                    + " — go add/update/delete items (e.g., in the Azure Portal Data Explorer).");
            System.out.println("Press Ctrl+C to stop.");
            System.out.println();

            // === Multi-threaded consumption: one thread per cursor ===
            // Each cursor (physical partition) gets its own polling thread.
            // This pattern maximizes throughput by consuming all partitions
            // in parallel — no partition blocks another. See SDK spec §5.
            List<Thread> pollers = new ArrayList<>(cursors.size());
            CountDownLatch started = new CountDownLatch(cursors.size());

            for (int i = 0; i < cursors.size(); i++) {
                final int cursorIndex = i;
                final ChangeFeedCursor initialCursor = cursors.get(i);
                Thread poller = new Thread(() -> {
                    ChangeFeedCursor cursor = initialCursor;
                    started.countDown();
                    while (!shutdown.get()) {
                        try {
                            ChangeFeedPage page = client.readChanges(address, cursor);
                            for (ChangeEvent ev : page.events()) {
                                printEvent(ev, cursorIndex);
                                totalEvents.incrementAndGet();
                            }
                            cursor = page.nextCursor();
                            if (!page.hasMore() && !shutdown.get()) {
                                Thread.sleep(pollIntervalMs);
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception ex) {
                            System.err.println("  [cursor-" + cursorIndex + "] error: " + ex.getMessage());
                            try { Thread.sleep(pollIntervalMs); } catch (InterruptedException ie) { break; }
                        }
                    }
                }, "cursor-poller-" + cursorIndex);
                poller.setDaemon(true);
                pollers.add(poller);
                poller.start();
            }

            // Wait for all poller threads to start
            started.await();
            System.out.println("Started " + pollers.size() + " parallel cursor poller(s).");
            System.out.println();

            // Graceful shutdown: Ctrl+C interrupts all poller threads
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdown.set(true);
                for (Thread t : pollers) t.interrupt();
            }, "change-feed-watcher-shutdown"));

            // Main thread waits for shutdown signal
            try {
                for (Thread t : pollers) t.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                shutdown.set(true);
                for (Thread t : pollers) t.interrupt();
                System.out.println();
                System.out.println("--- Stopping watcher ---");
                System.out.println("Total events observed: " + totalEvents.get());
            }
        }
    }

    /**
     * Parse the poll interval from the {@code changefeed.poll.intervalMs}
     * system property. Falls back to {@link #DEFAULT_POLL_INTERVAL_MS} when
     * the property is missing or unparseable, and clamps to a minimum of 1 ms
     * to avoid a 0 ms busy-loop ({@link Thread#sleep(long)} accepts 0 but
     * that would spin the CPU wastefully).
     */
    static long parsePollIntervalMs(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_POLL_INTERVAL_MS;
        try {
            long v = Long.parseLong(raw.trim());
            if (v < 1L) {
                System.err.println("changefeed.poll.intervalMs=" + raw
                        + " is < 1; clamping to 1 ms.");
                return 1L;
            }
            return v;
        } catch (NumberFormatException nfe) {
            System.err.println("changefeed.poll.intervalMs=" + raw
                    + " is not a valid long; using default "
                    + DEFAULT_POLL_INTERVAL_MS + " ms.");
            return DEFAULT_POLL_INTERVAL_MS;
        }
    }

    /**
     * Parse the optional {@code multiclouddb.throughput} property as an
     * integer RU/s value. Returns 0 (let Cosmos pick the default) when the
     * property is absent or unparseable.
     */
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

    private static void printEvent(ChangeEvent ev, int cursorIndex) {
        StringBuilder sb = new StringBuilder()
                .append('[').append(TS.format(ev.commitTimestamp())).append("] ")
                .append("cursor-").append(cursorIndex).append("  ")
                .append(String.format("%-6s ", ev.type()))
                .append(ev.key());
        JsonNode doc = ev.data();
        if (doc != null && !doc.isMissingNode() && !doc.isNull()) {
            // Compact one-line payload preview; trim very long docs.
            String json = doc.toString();
            if (json.length() > 400) json = json.substring(0, 400) + "…";
            sb.append("  ").append(json);
        }
        System.out.println(sb);
    }
}
