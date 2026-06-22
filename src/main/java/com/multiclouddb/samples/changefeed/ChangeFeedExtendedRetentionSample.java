// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.changefeed;

import com.multiclouddb.samples.ConfigLoader;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.CursorExpiredException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
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
 *     <td>No {@code retentionDays} needed — AVAD is automatic</td>
 *     <td>Controlled by backup tier (7d or 30d)</td>
 *   </tr>
 *   <tr>
 *     <td><b>Periodic Backup (non-CB) account</b></td>
 *     <td>No account-level change needed</td>
 *     <td>Set {@code multiclouddb.changefeed.retentionDays=N} in properties</td>
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
 *   # Extended retention opt-in:
 *   # multiclouddb.changefeed.retentionDays=7
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
    private static final String CURSOR_FILE = "retention_cursors.json";

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
            System.err.println("Other providers are not yet supported. Set multiclouddb.provider=cosmos.");
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

        // REQUIRED: retentionDays must be set in properties file.
        // This is the explicit user action to enable extended retention.
        MulticloudDbClientConfig sdkConfig = appConfig.sdk();
        boolean hasRetentionConfig = sdkConfig.changeFeed() != null
                && sdkConfig.changeFeed().extendedRetention().isPresent();

        if (!hasRetentionConfig) {
            System.err.println("ERROR: multiclouddb.changefeed.retentionDays is not set.");
            System.err.println();
            System.err.println("To enable extended retention, add this to your properties file:");
            System.err.println("  multiclouddb.changefeed.retentionDays=7");
            System.err.println();
            System.err.println("Then rebuild: mvn clean package -DskipTests");
            System.exit(2);
            return;
        }

        Duration retentionWindow = sdkConfig.changeFeed().extendedRetention().get();
        System.out.println("Configuration:");
        System.out.println("  multiclouddb.changefeed.retentionDays = " + retentionWindow.toDays());
        System.out.println("  Requesting " + retentionWindow.toDays() + " day(s) extended retention");
        System.out.println();

        // ─── Build the client and run ────────────────────────────────────────
        runWithExtendedRetention(appConfig, sdkConfig, retentionWindow);
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
        System.out.println("║  STEP 1 — Account-level setup (Azure Portal):                   ║");
        System.out.println("║    • Continuous Backup account (RECOMMENDED):                    ║");
        System.out.println("║      Azure Portal → Cosmos account → Backup & Restore           ║");
        System.out.println("║      → Enable Continuous Backup (7-day or 30-day tier)          ║");
        System.out.println("║      Retention = backup tier duration (7d or 30d).               ║");
        System.out.println("║    • Periodic Backup account:                                    ║");
        System.out.println("║      No account-level change needed.                             ║");
        System.out.println("║      The SDK creates the container with explicit AVAD policy.    ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  STEP 2 — Properties file (REQUIRED for this sample):         ║");
        System.out.println("║    multiclouddb.changefeed.retentionDays=7                     ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  If omitted → ChangeFeedConfig.defaults() (24h baseline).       ║");
        System.out.println("║  If set → SDK opts into extended retention via                   ║");
        System.out.println("║    ChangeFeedConfig.builder()                                    ║");
        System.out.println("║        .extendedRetention(Duration.ofDays(N)).build()            ║");
        System.out.println("║                                                                  ║");
        System.out.println("║  Leave the property OUT for providers that don't support it.     ║");
        System.out.println("║  The SDK fails fast if you set it on an unsupported provider.    ║");
        System.out.println("║                                                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Build client with extended retention, provision container, and run
     * data-plane reads. Uses a plain client for provisioning (to avoid CB
     * conflicts), then the extended-retention client for reading.
     */
    private static void runWithExtendedRetention(ConfigLoader.AppConfig appConfig,
                                                 MulticloudDbClientConfig sdkConfig,
                                                 Duration retentionWindow) throws Exception {
        // Step 1: Build the extended-retention client (capability gate check)
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(sdkConfig)) {
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

            // Verify base change-feed capability before proceeding to data-plane
            if (!caps.isSupported(Capability.CHANGE_FEED)) {
                System.err.println("ERROR: Provider supports extended retention but does not "
                        + "support base change feed. Data-plane operations will fail.");
                System.exit(2);
                return;
            }
        }

        // Step 2: Provision container using a plain client (no extended retention)
        // On CB accounts, AVAD is automatic — setting explicit retention is rejected.
        // On non-CB accounts, ensureContainer creates a plain container.
        String database = appConfig.property("multiclouddb.database", DEFAULT_DATABASE);
        ResourceAddress address = new ResourceAddress(database, DEFAULT_COLLECTION);

        System.out.println("--- Provisioning '" + database + "/" + DEFAULT_COLLECTION + "' ---");
        try (MulticloudDbClient provisionClient = MulticloudDbClientFactory.create(
                appConfig.sdkWithoutExtendedRetention())) {
            provisionClient.ensureDatabase(database);
            provisionClient.ensureContainer(address);
        }
        System.out.println("  Container ready.");
        System.out.println();

        // Step 3: Check for saved cursors → resume mode or fresh mode
        Path cursorPath = Paths.get(CURSOR_FILE);
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(sdkConfig)) {
            if (Files.exists(cursorPath)) {
                resumeFromSavedCursors(client, address, cursorPath);
            } else {
                freshRun(client, address, cursorPath);
            }
        }
    }

    // ── Fresh run: write items, read changes, save cursors ──────────────────

    private static void freshRun(MulticloudDbClient client,
                                 ResourceAddress address,
                                 Path cursorPath) throws Exception {
        System.out.println("--- MODE: Fresh run (no saved cursors found) ---");
        System.out.println();

        System.out.println("--- listCursors (live tip) ---");
        List<ChangeFeedCursor> cursors = client.listCursors(address);
        System.out.println("  Discovered " + cursors.size() + " partition cursor(s)");
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

        // Multi-threaded drain
        System.out.println("--- readChanges (multi-threaded consumption) ---");
        DrainResult result = drainAll(client, address, cursors, writerDone);
        System.out.println();
        System.out.println("  Total events consumed: " + result.totalEvents
                + " across " + cursors.size() + " parallel thread(s).");
        System.out.println();

        // Save cursor tokens for later resume
        saveCursors(cursorPath, result.finalCursors);
        System.out.println("=== Fresh run complete ===");
        System.out.println();
        System.out.println("  Cursor tokens saved to: " + cursorPath.toAbsolutePath());
        System.out.println("  Saved at: " + Instant.now());
        System.out.println();
        System.out.println("  ┌─────────────────────────────────────────────────────────┐");
        System.out.println("  │  TO TEST EXTENDED RETENTION:                             │");
        System.out.println("  │                                                          │");
        System.out.println("  │  1. Wait more than 24 hours                              │");
        System.out.println("  │  2. (Optional) Add items in Azure Portal Data Explorer   │");
        System.out.println("  │  3. Run this sample again                                │");
        System.out.println("  │                                                          │");
        System.out.println("  │  If it reads events → extended retention works!          │");
        System.out.println("  │  If CursorExpiredException → limited to 24h baseline.   │");
        System.out.println("  └─────────────────────────────────────────────────────────┘");
    }

    // ── Resume run: load saved cursors and read from where we left off ───────

    private static void resumeFromSavedCursors(MulticloudDbClient client,
                                               ResourceAddress address,
                                               Path cursorPath) throws Exception {
        System.out.println("--- MODE: Resume (loading saved cursors) ---");
        System.out.println("  Cursor file: " + cursorPath.toAbsolutePath());

        List<String> lines = Files.readAllLines(cursorPath);
        if (lines.isEmpty()) {
            System.err.println("  Cursor file is empty. Delete it and re-run for a fresh start.");
            return;
        }

        // First line is metadata (saved timestamp)
        String savedAt = lines.get(0);
        System.out.println("  Saved at  : " + savedAt);
        System.out.println("  Now       : " + Instant.now());

        try {
            Instant savedInstant = Instant.parse(savedAt);
            Duration age = Duration.between(savedInstant, Instant.now());
            long hours = age.toHours();
            System.out.println("  Cursor age: " + hours + " hours (" + age.toDays() + " days)");
            if (hours < 24) {
                System.out.println("  ⚠ Cursors are less than 24h old — this doesn't prove extended retention.");
                System.out.println("    Wait at least 24h to confirm retention beyond the baseline.");
            } else {
                System.out.println("  ✓ Cursors are older than 24h — resuming will prove extended retention!");
            }
        } catch (Exception e) {
            System.out.println("  (Could not parse saved timestamp)");
        }
        System.out.println();

        // Load cursor tokens (lines 1..N)
        List<ChangeFeedCursor> savedCursors = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String token = lines.get(i).trim();
            if (!token.isEmpty()) {
                savedCursors.add(ChangeFeedCursor.fromToken(token));
            }
        }
        System.out.println("  Loaded " + savedCursors.size() + " saved cursor(s)");
        System.out.println();

        // Try to resume reading from saved cursors
        System.out.println("--- Resuming readChanges from saved cursors ---");
        try {
            AtomicBoolean done = new AtomicBoolean(true); // no writer — just read what's there
            DrainResult result = drainAll(client, address, savedCursors, done);
            System.out.println();
            System.out.println("  ╔═══════════════════════════════════════════════════╗");
            System.out.println("  ║  ✓ EXTENDED RETENTION CONFIRMED!                  ║");
            System.out.println("  ╠═══════════════════════════════════════════════════╣");
            System.out.println("  ║  Successfully resumed reading from saved cursors. ║");
            System.out.println("  ║  Events read: " + String.format("%-35d", result.totalEvents) + "║");
            System.out.println("  ║  Cursors did NOT expire after >24h.               ║");
            System.out.println("  ║  Extended retention is working!                   ║");
            System.out.println("  ╚═══════════════════════════════════════════════════╝");
            System.out.println();

            // Save updated cursors for next run
            saveCursors(cursorPath, result.finalCursors);
            System.out.println("  Updated cursor tokens saved for next run.");

        } catch (CursorExpiredException ex) {
            System.err.println();
            System.err.println("  ╔═══════════════════════════════════════════════════╗");
            System.err.println("  ║  ✗ CURSOR EXPIRED — 24h baseline only             ║");
            System.err.println("  ╠═══════════════════════════════════════════════════╣");
            System.err.println("  ║  The saved cursors have expired. This means       ║");
            System.err.println("  ║  extended retention is NOT active on this account.║");
            System.err.println("  ║                                                   ║");
            System.err.println("  ║  To fix:                                          ║");
            System.err.println("  ║  • Enable Continuous Backup on the Cosmos account ║");
            System.err.println("  ║  • Or verify retentionDays is set correctly       ║");
            System.err.println("  ╚═══════════════════════════════════════════════════╝");
            System.err.println();
            System.err.println("  Error: " + ex.getMessage());

            // Delete stale cursor file so next run starts fresh
            Files.deleteIfExists(cursorPath);
            System.out.println("  Deleted stale cursor file. Re-run for a fresh start.");
        }
    }

    // ── Cursor persistence ──────────────────────────────────────────────────

    private static void saveCursors(Path path, List<String> cursorTokens) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(Instant.now().toString()); // first line = timestamp
        lines.addAll(cursorTokens);
        Files.write(path, lines);
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

    private static final class DrainResult {
        final int totalEvents;
        final List<String> finalCursors;

        DrainResult(int totalEvents, List<String> finalCursors) {
            this.totalEvents = totalEvents;
            this.finalCursors = finalCursors;
        }
    }

    private static DrainResult drainAll(MulticloudDbClient client,
                                        ResourceAddress address,
                                        List<ChangeFeedCursor> initial,
                                        AtomicBoolean writerDone) throws Exception {
        AtomicInteger total = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(initial.size());
        List<Thread> consumers = new ArrayList<>(initial.size());
        String[] finalTokens = new String[initial.size()];
        Exception[] threadException = new Exception[1];

        for (int i = 0; i < initial.size(); i++) {
            final int cursorIndex = i;
            final ChangeFeedCursor startCursor = initial.get(i);
            Thread consumer = new Thread(() -> {
                ChangeFeedCursor cursor = startCursor;
                long deadline = System.currentTimeMillis() + 30_000L;
                int emptyPollsAfterWriter = 0;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        ChangeFeedPage page = client.readChanges(address, cursor);
                        for (ChangeEvent ev : page.events()) {
                            System.out.printf("  [cursor-%d] %-6s %s @ %s%n",
                                    cursorIndex, ev.type(), ev.key(), ev.commitTimestamp());
                            total.incrementAndGet();
                            emptyPollsAfterWriter = 0; // reset on activity
                        }
                        cursor = page.nextCursor();
                        if (writerDone.get() && !page.hasMore()) {
                            // Grace period: keep polling for a few seconds after
                            // writer finishes to allow Cosmos change feed propagation
                            emptyPollsAfterWriter++;
                            if (emptyPollsAfterWriter > 20) { // ~5s grace
                                break;
                            }
                        }
                        if (!page.hasMore()) {
                            Thread.sleep(250);
                        }
                    }
                    finalTokens[cursorIndex] = cursor.toToken();
                } catch (CursorExpiredException ce) {
                    synchronized (threadException) {
                        if (threadException[0] == null) {
                            threadException[0] = ce;
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

        // Re-throw CursorExpiredException if any thread encountered it
        if (threadException[0] != null) {
            throw threadException[0];
        }

        List<String> tokens = new ArrayList<>();
        for (String t : finalTokens) {
            tokens.add(t != null ? t : "");
        }
        return new DrainResult(total.get(), tokens);
    }
}
