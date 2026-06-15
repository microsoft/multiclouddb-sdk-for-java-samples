// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.models.ChangeFeedPolicy;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;

import java.time.Duration;
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
 *   # Live Cosmos account (master-key auth)
 *   java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.ChangeFeedWatcherSample
 *
 *   # Local Cosmos emulator (default)
 *   java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.ChangeFeedWatcherSample
 *
 *   # Tune the poll interval (milliseconds; default 1000)
 *   java -Dchangefeed.poll.intervalMs=500 \
 *        -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.ChangeFeedWatcherSample
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

    public static void main(String[] args) throws Exception {
        long pollIntervalMs = Long.parseLong(
                System.getProperty("changefeed.poll.intervalMs", "1000"));

        ConfigLoader.AppConfig appConfig = ConfigLoader.load(DEFAULT_CONFIG);
        ProviderId provider = appConfig.sdk().provider();

        System.out.println("=== Multicloud DB Change Feed Watcher ===");
        System.out.println("Provider     : " + provider.displayName());
        boolean isCosmos = provider.id().equals("cosmos");
        boolean isCosmosEmulator = isCosmos && isLocalEndpoint(
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
            provisionCosmosAvadContainer(appConfig, database, collection);
        }

        AtomicBoolean shutdown = new AtomicBoolean(false);
        AtomicLong totalEvents = new AtomicLong(0);

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(appConfig.sdk())) {

            System.out.println("--- Provisioning '" + database + "/" + collection + "' ---");
            client.ensureDatabase(database);
            client.ensureContainer(address);
            System.out.println();

            // Discover one cursor per feed range, positioned at the live tip.
            // Anything written BEFORE this call will not be surfaced.
            List<ChangeFeedCursor> cursors = new ArrayList<>(client.listCursors(address));
            System.out.println("Discovered " + cursors.size() + " partition cursor(s) at the live tip.");
            System.out.println();
            System.out.println("Watching " + database + "/" + collection
                    + " — go add/update/delete items (e.g., in the Azure Portal Data Explorer).");
            System.out.println("Press Ctrl+C to stop.");
            System.out.println();

            // Graceful shutdown: Ctrl+C triggers a final tally.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdown.set(true);
                System.out.println();
                System.out.println("--- Stopping watcher ---");
                System.out.println("Total events observed: " + totalEvents.get());
            }, "change-feed-watcher-shutdown"));

            // Polling loop: round-robin across cursors. When every cursor
            // reports caught-up, sleep for pollIntervalMs before retrying.
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

    // ── Helper: detect emulator endpoints (localhost / 127.0.0.1) ───────────
    private static boolean isLocalEndpoint(String endpoint) {
        if (endpoint == null) return false;
        String lower = endpoint.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1");
    }

    // ── Helper: pre-provision Cosmos AVAD container directly (emulator) ─────
    //
    // The Cosmos emulator does not support Continuous Backup, so AVAD must be
    // configured explicitly via ChangeFeedPolicy.createAllVersionsAndDeletesPolicy.
    // The emulator caps AVAD retention at 10 minutes.
    private static void provisionCosmosAvadContainer(ConfigLoader.AppConfig appConfig,
                                                     String database,
                                                     String collection) {
        String endpoint = appConfig.sdk().connection().get("endpoint");
        String key = appConfig.sdk().connection().get("key");
        String mode = appConfig.sdk().connection().getOrDefault("connectionMode", "gateway");

        CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(endpoint)
                .key(key)
                .consistencyLevel(ConsistencyLevel.SESSION);
        if ("gateway".equalsIgnoreCase(mode)) {
            builder.gatewayMode(new GatewayConnectionConfig());
        }

        try (CosmosClient cosmos = builder.buildClient()) {
            cosmos.createDatabaseIfNotExists(database);
            CosmosContainerProperties props = new CosmosContainerProperties(collection, "/partitionKey");
            props.setChangeFeedPolicy(
                    ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(Duration.ofMinutes(10)));
            cosmos.getDatabase(database).createContainerIfNotExists(props);
            System.out.println("  [provision] AVAD container '" + database + "/" + collection
                    + "' ready (emulator retention=10min)");
        }
    }
}
