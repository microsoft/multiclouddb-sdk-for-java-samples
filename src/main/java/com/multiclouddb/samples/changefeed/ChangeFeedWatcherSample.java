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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Continuous change-feed watcher — keeps running until Ctrl+C and prints
 * every CREATE / UPDATE / DELETE event as it arrives. Use this to observe
 * the change feed while you add or delete items manually (e.g., from the
 * Azure Portal Data Explorer, the Cosmos emulator UI, or another writer).
 * <p>
 * Unlike {@link ChangeFeedSample}, this watcher does not produce any writes
 * of its own — it only consumes.
 * <p>
 * Usage:
 *
 * <pre>
 *   # Live Cosmos account (master-key auth — see ChangeFeedSample javadoc
 *   # for the cp + mvn package setup steps that put the cloud config on
 *   # the fat-jar classpath)
 *   java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
 *
 *   # Local Cosmos emulator (default)
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
 *   <li>In the Azure Portal, open Data Explorer for the
 *       {@code multiclouddb-sdk-for-java-changefeed/change-feed-demo}
 *       container and create, edit, or delete items.</li>
 *   <li>Watch the console — each operation surfaces as a
 *       {@code CREATE} / {@code UPDATE} / {@code DELETE} line within the
 *       poll interval.</li>
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
        boolean isCosmos = ProviderId.COSMOS.equals(provider);
        boolean isCosmosEmulator = isCosmos && ChangeFeedSampleSupport.isLocalEndpoint(
                appConfig.sdk().connection().get("endpoint"));
        if (isCosmos) {
            System.out.println("Mode         : " + (isCosmosEmulator ? "EMULATOR" : "LIVE"));
        }

        String database = appConfig.property("multiclouddb.database", DEFAULT_DATABASE);
        String collection = appConfig.property("multiclouddb.collection", DEFAULT_COLLECTION);
        ResourceAddress address = new ResourceAddress(database, collection);
        System.out.println("Container    : " + database + "/" + collection);
        System.out.println("Poll interval: " + pollIntervalMs + " ms");
        System.out.println();

        if (isCosmosEmulator) {
            ChangeFeedSampleSupport.provisionCosmosAvadContainer(appConfig, database, collection);
        }

        AtomicBoolean shutdown = new AtomicBoolean(false);
        AtomicLong totalEvents = new AtomicLong(0);

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdk())) {

            // Bail out early on providers that don't support change feed
            // (Capability.CHANGE_FEED is gated to Cosmos in the SDK).
            CapabilitySet caps = client.capabilities();
            if (!caps.isSupported(Capability.CHANGE_FEED)) {
                System.err.println("Change feed is not supported on "
                        + provider.displayName()
                        + ". Use a Cosmos config (e.g. change-feed-cosmos.properties).");
                return;
            }

            System.out.println("--- Provisioning '" + database + "/" + collection + "' ---");
            client.ensureDatabase(database);
            client.ensureContainer(address);
            System.out.println();

            // Discover one cursor per feed range, positioned at the live tip.
            // Anything written BEFORE this call will not be surfaced.
            List<ChangeFeedCursor> cursors = new ArrayList<>(client.listCursors(address));
            System.out.println("Discovered " + cursors.size() + " partition cursor(s) at the live tip.");
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

            // Graceful shutdown: Ctrl+C flips the flag AND interrupts the
            // polling thread so a sleeping Thread.sleep returns immediately
            // (otherwise we'd wait up to pollIntervalMs before exiting).
            Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdown.set(true);
                mainThread.interrupt();
            }, "change-feed-watcher-shutdown"));

            // Polling loop: round-robin across cursors. When every cursor
            // reports caught-up, sleep for pollIntervalMs before retrying.
            // The final tally is printed in the finally block below, on the
            // main thread, so the count reflects every event the loop printed
            // (printing it from the shutdown hook would race with the loop).
            try {
                while (!shutdown.get()) {
                    boolean anyHasMore = false;
                    for (int i = 0; i < cursors.size(); i++) {
                        if (shutdown.get()) break;
                        ChangeFeedPage page = client.readChanges(address, cursors.get(i));
                        for (ChangeEvent ev : page.events()) {
                            printEvent(ev);
                            totalEvents.incrementAndGet();
                        }
                        cursors.set(i, page.nextCursor());
                        if (page.hasMore()) anyHasMore = true;
                    }
                    if (!anyHasMore && !shutdown.get()) {
                        Thread.sleep(pollIntervalMs);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
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
     * because {@link Thread#sleep(long)} rejects values {@code < 0}.
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

    private static void printEvent(ChangeEvent ev) {
        StringBuilder sb = new StringBuilder()
                .append('[').append(TS.format(ev.commitTimestamp())).append("] ")
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
