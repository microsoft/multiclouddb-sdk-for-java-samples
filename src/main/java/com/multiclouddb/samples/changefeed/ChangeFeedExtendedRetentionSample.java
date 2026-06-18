// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.changefeed;

import com.multiclouddb.samples.ConfigLoader;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedConfig;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Escape-hatch sample for the Multicloud DB SDK's <em>extended change-feed
 * retention</em> opt-in.
 * <p>
 * The portable change-feed baseline ({@link ChangeFeedSample} and
 * {@link ChangeFeedWatcherSample}) is fixed at 24 hours — every cursor token
 * older than that is treated as expired regardless of what the underlying
 * provider stores. To resume from cursors older than 24 hours, callers opt in
 * via {@link ChangeFeedConfig.Builder#extendedRetention(Duration)} and pass
 * the resulting config to
 * {@link MulticloudDbClientConfig.Builder#changeFeed(ChangeFeedConfig)}.
 * <p>
 * Opting in is a portable contract, not a guarantee: the SDK refuses to build
 * a client whose provider does not declare
 * {@link Capability#EXTENDED_CHANGE_FEED_HISTORY}. The refusal happens
 * <strong>before any network I/O</strong> with
 * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} (reason
 * {@code extended_retention_unavailable}), so misconfiguration cannot lurk
 * until the first {@code listCursors} / {@code readChanges} call.
 *
 * <h3>Per-provider expected outcome</h3>
 * <table border="1">
 *   <caption>What this sample prints per provider when requesting 7-day retention</caption>
 *   <tr><th>Provider</th><th>Build-time gate</th><th>Notes (printed)</th></tr>
 *   <tr>
 *     <td>Azure Cosmos DB</td>
 *     <td>SUCCESS</td>
 *     <td>Up to 30 days via Continuous Backup 30d tier; 7d minimum (AVAD requires Continuous Backup).</td>
 *   </tr>
 *   <tr>
 *     <td>Google Cloud Spanner</td>
 *     <td>NOT YET SUPPORTED</td>
 *     <td>Change-feed support for Spanner is not yet available.</td>
 *   </tr>
 *   <tr>
 *     <td>AWS DynamoDB</td>
 *     <td>NOT YET SUPPORTED</td>
 *     <td>Change-feed support for DynamoDB is not yet available.</td>
 *   </tr>
 * </table>
 *
 * <h3>Data-plane round-trip</h3>
 * After the capability gate passes, this sample provisions a container,
 * writes test items, and consumes change events using multi-threaded
 * cursor readers (one thread per partition). This demonstrates
 * extended-retention reads end-to-end on Cosmos DB.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 *   # Cosmos (live, Continuous-Backup account)
 *   java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
 * </pre>
 */
public class ChangeFeedExtendedRetentionSample {

    private static final String DEFAULT_CONFIG = "change-feed-cosmos-cloud.properties";
    private static final String DEFAULT_DATABASE = "multiclouddb_samples";
    private static final String DEFAULT_COLLECTION = "retention_demo";

    /**
     * 7 days — strictly greater than the 24-hour portable baseline (so the
     * extended-retention opt-in is actually triggered) and within the Cosmos
     * DB Continuous Backup 30d tier ceiling.
     */
    private static final Duration REQUESTED_RETENTION = Duration.ofDays(7);

    public static void main(String[] args) throws Exception {
        ConfigLoader.AppConfig appConfig = ConfigLoader.load(DEFAULT_CONFIG);
        ProviderId provider = appConfig.sdk().provider();

        // DEFAULT_CONFIG (change-feed-cosmos-cloud.properties) is git-ignored
        // because it contains secrets. If it is missing, ConfigLoader proceeds
        // with system properties only, which will lack connection info.
        if (appConfig.sdk().connection().get("endpoint") == null
                && appConfig.sdk().connection().get("region") == null
                && appConfig.sdk().connection().get("projectId") == null) {
            System.err.println("ERROR: No connection info found. This sample requires a live "
                    + "cloud account (the emulator does not support extended retention).");
            System.err.println();
            System.err.println("Copy the template and fill in your credentials:");
            System.err.println("  cp src/main/resources/change-feed-cosmos-cloud.properties.template \\");
            System.err.println("     src/main/resources/change-feed-cosmos-cloud.properties");
            System.err.println("Then rebuild: mvn clean package -DskipTests");
            System.exit(2);
            return;
        }

        System.out.println("=== Multicloud DB Change Feed — Extended Retention Sample ===");
        System.out.println("Provider: " + provider.displayName());

        // *** TEMPORARY: Only Cosmos DB is supported for change feed ***
        if (!ProviderId.COSMOS.equals(provider)) {
            System.err.println();
            System.err.println("ERROR: Change-feed samples currently support Cosmos DB only.");
            System.err.println("DynamoDB and Spanner change-feed support is not yet available.");
            System.exit(1);
            return;
        }
        System.out.println();

        // The Cosmos emulator does not support Continuous Backup, which is a
        // prerequisite for extended-retention reads on the Cosmos data plane.
        // The build-time gate would still succeed (Cosmos declares the
        // capability), but the demo would be misleading — bail out with a
        // clear message instead.
        if (ProviderId.COSMOS.equals(provider)
                && ChangeFeedSampleSupport.isLocalEndpoint(
                        appConfig.sdk().connection().get("endpoint"))) {
            System.err.println("Extended-retention reads require a live Continuous-Backup "
                    + "Cosmos account; the Cosmos emulator does not support Continuous "
                    + "Backup. Point -Dmulticlouddb.config at a live "
                    + "change-feed-cosmos-cloud.properties instead.");
            System.exit(2);
            return;
        }

        // If the properties file includes multiclouddb.changefeed.extendedRetentionDays,
        // ConfigLoader already wired the extended retention into the SDK config.
        // Otherwise, apply the sample's default programmatically.
        MulticloudDbClientConfig sdkConfig = appConfig.sdk();
        Duration retentionWindow;
        if (sdkConfig.changeFeed() != null && sdkConfig.changeFeed().extendedRetention().isPresent()) {
            retentionWindow = sdkConfig.changeFeed().extendedRetention().get();
            System.out.println("Extended retention: " + retentionWindow.toDays()
                    + " days (from config: multiclouddb.changefeed.extendedRetentionDays)");
        } else {
            retentionWindow = REQUESTED_RETENTION;
            sdkConfig = withExtendedRetention(sdkConfig, retentionWindow);
            System.out.println("Extended retention: " + retentionWindow.toDays()
                    + " days (programmatic default — set multiclouddb.changefeed.extendedRetentionDays in config to override)");
        }
        System.out.println();
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(sdkConfig)) {
            // The build-time gate passed — the provider declares the capability.
            CapabilitySet caps = client.capabilities();
            Capability cap = caps.get(Capability.EXTENDED_CHANGE_FEED_HISTORY);

            System.out.println("  Client built successfully — capability gate passed.");
            System.out.println();
            System.out.println("--- Capability detail ---");
            System.out.println("  Name      : " + Capability.EXTENDED_CHANGE_FEED_HISTORY);
            System.out.println("  Supported : " + caps.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY));
            if (cap != null && cap.notes() != null && !cap.notes().isBlank()) {
                System.out.println("  Notes     : " + cap.notes());
            }
            System.out.println();

            // === Data-plane: multi-threaded cursor reads ===
            String database = appConfig.property("multiclouddb.database", DEFAULT_DATABASE);
            String collection = appConfig.property("multiclouddb.collection", DEFAULT_COLLECTION);
            ResourceAddress address = new ResourceAddress(database, collection);

            // Provision schema
            System.out.println("--- Provisioning '" + database + "/" + collection + "' ---");
            client.ensureDatabase(database);
            client.ensureContainer(address);

            // DynamoDB workaround: enable Streams
            if (ProviderId.DYNAMO.equals(provider)) {
                String tableName = database + "__" + collection;
                ChangeFeedSampleSupport.enableDynamoStreams(appConfig, tableName);
            }
            System.out.println();

            // List cursors at the live tip
            System.out.println("--- listCursors (live tip) ---");
            List<ChangeFeedCursor> cursors = client.listCursors(address);
            System.out.println("  Discovered " + cursors.size() + " partition cursor(s)");
            for (int i = 0; i < cursors.size(); i++) {
                String token = cursors.get(i).toToken();
                System.out.println("  cursor-" + i + ": "
                        + token.substring(0, Math.min(80, token.length())) + "…");
            }
            if (cursors.isEmpty()) {
                System.err.println("  No partition cursors. Aborting sample.");
                return;
            }
            System.out.println();

            // Spawn a writer thread that produces events
            AtomicBoolean writerDone = new AtomicBoolean(false);
            Thread writer = new Thread(() -> runWriter(client, address, writerDone),
                    "retention-writer");
            writer.setDaemon(true);
            writer.start();

            // Multi-threaded drain: one thread per cursor
            System.out.println("--- readChanges (multi-threaded consumption) ---");
            int total = drainAll(client, address, cursors, writerDone);
            System.out.println();
            System.out.println("  Total events consumed: " + total
                    + " across " + cursors.size() + " parallel thread(s).");
            System.out.println();
            System.out.println("=== Sample complete ===");
        } catch (MulticloudDbException ex) {
            if (ex.error() != null
                    && MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY.equals(ex.error().category())) {
                System.err.println();
                System.err.println("--- Build-time gate REFUSED extended retention ---");
                System.err.println("  Provider : " + provider.displayName());
                System.err.println("  Category : " + ex.error().category());
                System.err.println("  Message  : " + ex.error().message());
                Map<String, String> details = ex.error().providerDetails();
                if (details != null && !details.isEmpty()) {
                    System.err.println("  Details  : " + details);
                }
                System.err.println();
                System.err.println("This is the expected outcome on providers that do not "
                        + "declare Capability.EXTENDED_CHANGE_FEED_HISTORY (e.g. AWS "
                        + "DynamoDB, whose Streams retention is fixed at 24h server-side).");
                System.exit(1);
                return;
            }
            throw ex;
        }
    }

    /**
     * Returns a copy of {@code base} with {@code .changeFeed(...)} set to an
     * extended-retention opt-in for the given window. All other builder
     * fields are preserved.
     * <p>
     * {@link ConfigLoader} produces a provider-neutral
     * {@link MulticloudDbClientConfig} that does not wire the change-feed
     * config; the sample composes the opt-in on top here.
     */
    private static MulticloudDbClientConfig withExtendedRetention(
            MulticloudDbClientConfig base, Duration retention) {
        ChangeFeedConfig cf = ChangeFeedConfig.builder()
                .extendedRetention(retention)
                .build();

        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(base.provider())
                .connection(base.connection())
                .auth(base.auth())
                .defaultOptions(base.defaultOptions())
                .nativeDiagnosticsEnabled(base.nativeDiagnosticsEnabled())
                .changeFeed(cf);

        if (base.userAgentSuffix() != null) {
            builder.userAgentSuffix(base.userAgentSuffix());
        }
        return builder.build();
    }

    // ── Writer: produces CREATE/UPDATE/DELETE events ─────────────────────────

    private static void runWriter(MulticloudDbClient client,
                                  ResourceAddress address,
                                  AtomicBoolean done) {
        try {
            Thread.sleep(500);
            // CREATEs
            for (int i = 0; i < 5; i++) {
                MulticloudDbKey key = MulticloudDbKey.of("ret-" + i, "ret-" + i);
                client.upsert(address, key, Map.of(
                        "title", "Retention item " + i,
                        "seq", i));
                System.out.println("  [writer] upsert ret-" + i);
                Thread.sleep(200);
            }
            // UPDATEs
            for (int i = 0; i < 2; i++) {
                MulticloudDbKey key = MulticloudDbKey.of("ret-" + i, "ret-" + i);
                client.upsert(address, key, Map.of(
                        "title", "Retention item " + i + " (updated)",
                        "seq", i));
                System.out.println("  [writer] update ret-" + i);
                Thread.sleep(200);
            }
            // DELETEs
            for (int i = 3; i < 5; i++) {
                MulticloudDbKey key = MulticloudDbKey.of("ret-" + i, "ret-" + i);
                client.delete(address, key);
                System.out.println("  [writer] delete ret-" + i);
                Thread.sleep(200);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            done.set(true);
        }
    }

    // ── Consumer: one thread per cursor (parallel partition consumption) ─────

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
                            System.out.printf("  [cursor-%d] %-6s %s @ %s%n",
                                    cursorIndex, ev.type(), ev.key(), ev.commitTimestamp());
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

        System.out.println("  Started " + consumers.size() + " parallel consumer thread(s).");
        allDone.await();
        return total.get();
    }
}
