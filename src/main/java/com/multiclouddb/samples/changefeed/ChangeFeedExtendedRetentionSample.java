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
 * Educational sample demonstrating how to enable <em>extended change-feed
 * retention</em> — reading changes beyond the portable 24-hour baseline.
 *
 * <h3>What is extended retention?</h3>
 * <p>
 * By default, the SDK treats change-feed cursors older than 24 hours as
 * expired. Extended retention lets you read changes days or weeks old —
 * useful for disaster recovery, late-arriving subscribers, and weekend
 * backfills.
 *
 * <h3>How to enable extended retention (two paths)</h3>
 * <p>
 * Cosmos DB has two account types, each requiring different configuration:
 *
 * <table border="1">
 *   <caption>Extended retention configuration by account type</caption>
 *   <tr><th>Account Type</th><th>Account-Level Setup</th><th>SDK Configuration</th><th>Retention Duration</th></tr>
 *   <tr>
 *     <td><b>Continuous Backup (CB) account</b></td>
 *     <td>Enable Continuous Backup (7-day or 30-day tier) in Azure Portal</td>
 *     <td>No {@code extendedRetentionDays} needed — AVAD is automatic</td>
 *     <td>Controlled by backup tier (7d or 30d)</td>
 *   </tr>
 *   <tr>
 *     <td><b>Periodic Backup (non-CB) account</b></td>
 *     <td>No account-level change needed</td>
 *     <td>Set {@code multiclouddb.changefeed.extendedRetentionDays=N} in properties</td>
 *     <td>The value you specify (up to provider limit)</td>
 *   </tr>
 * </table>
 *
 * <h3>Properties file configuration</h3>
 * <pre>
 *   # Required for all accounts:
 *   multiclouddb.provider=cosmos
 *   multiclouddb.connection.endpoint=https://&lt;account&gt;.documents.azure.com:443/
 *   multiclouddb.connection.key=&lt;primary-master-key&gt;
 *   multiclouddb.connection.connectionMode=gateway
 *   multiclouddb.database=multiclouddb-sdk-for-java-changefeed
 *
 *   # Extended retention opt-in (NON-CB accounts only):
 *   # multiclouddb.changefeed.extendedRetentionDays=7
 *   #
 *   # On CB accounts, OMIT this property — retention is automatic from
 *   # the backup tier. Setting it will cause a provisioning error.
 * </pre>
 *
 * <h3>Account-level setup (Cosmos DB)</h3>
 * <ul>
 *   <li><b>CB account:</b> Azure Portal → Cosmos account → Backup &amp; Restore
 *       → Enable Continuous Backup (7-day or 30-day tier). The tier ceiling
 *       determines your maximum retention.</li>
 *   <li><b>Non-CB account:</b> No account-level change. The SDK creates the
 *       container with an explicit AVAD ChangeFeedPolicy.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
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
            System.err.println("ERROR: No connection info found. This sample requires a live cloud account.");
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
        System.out.println();

        // *** TEMPORARY: Only Cosmos DB is supported for change feed ***
        if (!ProviderId.COSMOS.equals(provider)) {
            System.err.println("ERROR: Change-feed samples currently support Cosmos DB only.");
            System.err.println("DynamoDB and Spanner change-feed support is not yet available.");
            System.exit(1);
            return;
        }

        // The Cosmos emulator does not support Continuous Backup.
        if (ChangeFeedSampleSupport.isLocalEndpoint(
                appConfig.sdk().connection().get("endpoint"))) {
            System.err.println("ERROR: Extended-retention requires a live Cosmos account.");
            System.err.println("The emulator does not support Continuous Backup or explicit AVAD retention.");
            System.err.println("Point -Dmulticlouddb.config at a live change-feed-cosmos-cloud.properties.");
            System.exit(2);
            return;
        }

        // ─── Educational: explain extended retention configuration ───────────
        printConfigurationGuide();

        // Determine whether the user configured extendedRetentionDays in properties.
        MulticloudDbClientConfig sdkConfig = appConfig.sdk();
        boolean hasExplicitRetention = sdkConfig.changeFeed() != null
                && sdkConfig.changeFeed().extendedRetention().isPresent();
        Duration retentionWindow;

        if (hasExplicitRetention) {
            retentionWindow = sdkConfig.changeFeed().extendedRetention().get();
            System.out.println("Configuration detected:");
            System.out.println("  multiclouddb.changefeed.extendedRetentionDays = " + retentionWindow.toDays());
            System.out.println("  → This indicates a NON-CB account setup (explicit AVAD opt-in).");
        } else {
            retentionWindow = REQUESTED_RETENTION;
            System.out.println("Configuration detected:");
            System.out.println("  multiclouddb.changefeed.extendedRetentionDays = (not set)");
            System.out.println("  → Assuming Continuous Backup account (AVAD is automatic).");
            System.out.println("  → If you have a non-CB account, set extendedRetentionDays in your properties file.");
        }
        System.out.println();

        // ─── Build the client ────────────────────────────────────────────────
        // Strategy:
        // 1. If extendedRetentionDays is set → build with explicit retention (non-CB path)
        // 2. If not set → build WITHOUT extendedRetention (CB path, AVAD is automatic)
        //    The SDK still allows reading beyond 24h on CB accounts because the
        //    server stores all versions automatically.
        if (hasExplicitRetention) {
            // Non-CB path: SDK will create container with explicit AVAD policy
            System.out.println("--- Path: Explicit AVAD opt-in (non-CB account) ---");
            System.out.println("  Requesting " + retentionWindow.toDays() + " day(s) retention");
            runWithExplicitRetention(appConfig, sdkConfig, retentionWindow);
        } else {
            // CB path: no explicit retention — AVAD is automatic
            System.out.println("--- Path: Continuous Backup (AVAD automatic) ---");
            System.out.println("  Retention is controlled by your backup tier (7d or 30d).");
            System.out.println("  Verify with: az cosmosdb show --name <acct> -g <rg> --query backupPolicy");
            runWithContinuousBackup(appConfig, sdkConfig);
        }
    }

    /**
     * Print a guide explaining what configuration is needed for extended retention.
     */
    private static void printConfigurationGuide() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║         HOW TO ENABLE EXTENDED CHANGE-FEED RETENTION            ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║                                                                  ║");
        System.out.println("║  Extended retention = reading changes older than 24 hours.       ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  OPTION A: Continuous Backup account (RECOMMENDED)               ║");
        System.out.println("║  ─────────────────────────────────────────────────               ║");
        System.out.println("║  Account setup:                                                  ║");
        System.out.println("║    Azure Portal → Cosmos account → Backup & Restore              ║");
        System.out.println("║    → Enable Continuous Backup (7-day or 30-day tier)             ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  Properties file:                                                ║");
        System.out.println("║    (no extendedRetentionDays needed — AVAD is automatic)         ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  Retention: Controlled by backup tier (7d or 30d).               ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  OPTION B: Periodic Backup account (explicit opt-in)             ║");
        System.out.println("║  ────────────────────────────────────────────────────            ║");
        System.out.println("║  Account setup:                                                  ║");
        System.out.println("║    No account-level change needed.                               ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  Properties file:                                                ║");
        System.out.println("║    multiclouddb.changefeed.extendedRetentionDays=7               ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  Retention: The value you specify in the property.               ║");
        System.out.println("║  The SDK creates the container with an explicit AVAD policy.     ║");
        System.out.println("║                                                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * CB account path: build client WITHOUT extendedRetention, provision a
     * plain container (AVAD is automatic), and demonstrate data-plane reads.
     */
    private static void runWithContinuousBackup(ConfigLoader.AppConfig appConfig,
                                                MulticloudDbClientConfig sdkConfig) throws Exception {
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(sdkConfig)) {
            CapabilitySet caps = client.capabilities();
            Capability cap = caps.get(Capability.EXTENDED_CHANGE_FEED_HISTORY);

            System.out.println();
            System.out.println("  Client built successfully.");
            System.out.println();
            System.out.println("--- Capability detail ---");
            System.out.println("  Name      : " + Capability.EXTENDED_CHANGE_FEED_HISTORY);
            System.out.println("  Supported : " + caps.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY));
            if (cap != null && cap.notes() != null && !cap.notes().isBlank()) {
                System.out.println("  Notes     : " + cap.notes());
            }
            System.out.println();
            System.out.println("  On a Continuous Backup account, ALL containers automatically");
            System.out.println("  have All-Versions-and-Deletes (AVAD) change feed enabled.");
            System.out.println("  Retention duration = your backup tier (7d or 30d).");
            System.out.println("  No SDK opt-in is required.");
            System.out.println();

            runDataPlane(client, appConfig, "cb_retention_demo");
        }
    }

    /**
     * Non-CB account path: build client WITH extendedRetention opt-in,
     * provision a container with explicit AVAD policy, and demonstrate reads.
     */
    private static void runWithExplicitRetention(ConfigLoader.AppConfig appConfig,
                                                 MulticloudDbClientConfig sdkConfig,
                                                 Duration retentionWindow) throws Exception {
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(sdkConfig)) {
            CapabilitySet caps = client.capabilities();
            Capability cap = caps.get(Capability.EXTENDED_CHANGE_FEED_HISTORY);

            System.out.println();
            System.out.println("  Client built successfully — capability gate passed.");
            System.out.println();
            System.out.println("--- Capability detail ---");
            System.out.println("  Name      : " + Capability.EXTENDED_CHANGE_FEED_HISTORY);
            System.out.println("  Supported : " + caps.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY));
            if (cap != null && cap.notes() != null && !cap.notes().isBlank()) {
                System.out.println("  Notes     : " + cap.notes());
            }
            System.out.println();
            System.out.println("  The SDK will create the container with an explicit AVAD");
            System.out.println("  ChangeFeedPolicy (retention=" + retentionWindow + ").");
            System.out.println();

            runDataPlane(client, appConfig, DEFAULT_COLLECTION);
        } catch (MulticloudDbException ex) {
            if (ex.error() != null
                    && MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY.equals(ex.error().category())) {
                Map<String, String> details = ex.error().providerDetails();
                String reason = details != null ? details.getOrDefault("reason", "") : "";
                String message = ex.error().message() != null ? ex.error().message() : "";

                System.err.println();
                System.err.println("--- Extended retention REFUSED ---");
                System.err.println("  Provider : " + appConfig.sdk().provider().displayName());
                System.err.println("  Category : " + ex.error().category());
                System.err.println("  Message  : " + message);
                if (details != null && !details.isEmpty()) {
                    System.err.println("  Details  : " + details);
                }
                System.err.println();

                // Detect CB account trying to use explicit retention
                if (message.contains("continuous backup mode is enabled")
                        || message.contains("Continuous Backup")
                        || reason.contains("continuous_backup_required")) {
                    System.err.println("╔══════════════════════════════════════════════════════════════╗");
                    System.err.println("║  YOUR ACCOUNT HAS CONTINUOUS BACKUP ENABLED                 ║");
                    System.err.println("╠══════════════════════════════════════════════════════════════╣");
                    System.err.println("║                                                              ║");
                    System.err.println("║  On CB accounts, you do NOT need extendedRetentionDays.      ║");
                    System.err.println("║  AVAD change feed is AUTOMATIC on every container.           ║");
                    System.err.println("║  Retention is controlled by your backup tier (7d or 30d).    ║");
                    System.err.println("║                                                              ║");
                    System.err.println("║  FIX: Remove or comment out this line from your properties:  ║");
                    System.err.println("║    # multiclouddb.changefeed.extendedRetentionDays=7         ║");
                    System.err.println("║                                                              ║");
                    System.err.println("║  Then rebuild (mvn clean package -DskipTests) and re-run.    ║");
                    System.err.println("║  The sample will use the CB path automatically.              ║");
                    System.err.println("║                                                              ║");
                    System.err.println("╚══════════════════════════════════════════════════════════════╝");
                } else if (reason.contains("not_enacted")) {
                    System.err.println("FIX: The container already exists with a different retention policy.");
                    System.err.println("     Delete the container and re-run, or use a fresh container name.");
                } else {
                    System.err.println("The provider does not support extended retention.");
                }
                System.exit(1);
                return;
            }
            throw ex;
        }
    }

    /**
     * Data-plane round-trip: provision container, write items, consume events
     * with multi-threaded cursor readers.
     */
    private static void runDataPlane(MulticloudDbClient client,
                                     ConfigLoader.AppConfig appConfig,
                                     String collection) throws Exception {
        String database = appConfig.property("multiclouddb.database", DEFAULT_DATABASE);
        ResourceAddress address = new ResourceAddress(database, collection);

        System.out.println("--- Provisioning '" + database + "/" + collection + "' ---");
        client.ensureDatabase(database);
        client.ensureContainer(address);
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
        System.out.println();
        System.out.println("  Extended retention is working! Changes older than 24h can be read");
        System.out.println("  using cursors obtained from listCursors() or startFrom(Instant).");
    }

    /**
     * Returns a copy of {@code base} with {@code .changeFeed(...)} set to an
     * extended-retention opt-in for the given window.
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
            for (int i = 0; i < 5; i++) {
                MulticloudDbKey key = MulticloudDbKey.of("ret-" + i, "ret-" + i);
                client.upsert(address, key, Map.of(
                        "title", "Retention item " + i,
                        "seq", i));
                System.out.println("  [writer] upsert ret-" + i);
                Thread.sleep(200);
            }
            for (int i = 0; i < 2; i++) {
                MulticloudDbKey key = MulticloudDbKey.of("ret-" + i, "ret-" + i);
                client.upsert(address, key, Map.of(
                        "title", "Retention item " + i + " (updated)",
                        "seq", i));
                System.out.println("  [writer] update ret-" + i);
                Thread.sleep(200);
            }
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
