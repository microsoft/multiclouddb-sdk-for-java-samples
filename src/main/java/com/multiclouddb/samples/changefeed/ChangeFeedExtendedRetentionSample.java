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
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.changefeed.ChangeFeedConfig;

import java.time.Duration;
import java.util.Map;

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
 *     <td>SUCCESS</td>
 *     <td>Default 24h; configurable up to 7d natively via {@code CREATE CHANGE STREAM ... OPTIONS(retention_period=...)}.</td>
 *   </tr>
 *   <tr>
 *     <td>AWS DynamoDB</td>
 *     <td>FAIL FAST — {@code UNSUPPORTED_CAPABILITY} thrown by {@code create(...)}</td>
 *     <td>DynamoDB Streams is fixed at 24h server-side. SDK-managed archive-on-read is on the v1.x roadmap.</td>
 *   </tr>
 * </table>
 *
 * <h3>Why this sample does not perform data-plane reads</h3>
 * The build-time capability gate is the portable contract; the sample stops
 * there on purpose. Actually reading change events beyond the 24-hour baseline
 * requires provider-specific substrate setup that is out of scope for a
 * portable demo:
 * <ul>
 *   <li><b>Cosmos:</b> needs an AVAD container on a non-CB account (or a CB
 *       account with the SDK's CB-aware code path, which currently mis-maps
 *       the service's "retention conflicts with CB" 400 — see
 *       {@code README-change-feed.md} for the caveat).</li>
 *   <li><b>Spanner:</b> needs a {@code CREATE CHANGE STREAM ... OPTIONS(
 *       retention_period = '7d', value_capture_type = 'NEW_ROW')} created
 *       out-of-band.</li>
 * </ul>
 * The plain {@link ChangeFeedSample} and {@link ChangeFeedWatcherSample}
 * cover the data-plane round-trip at the portable 24-hour baseline.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 *   # Default: Cosmos (live, Continuous-Backup account)
 *   java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
 *
 *   # Spanner (should succeed)
 *   java -Dmulticlouddb.config=change-feed-spanner-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
 *
 *   # DynamoDB (should fail fast with UNSUPPORTED_CAPABILITY)
 *   java -Dmulticlouddb.config=change-feed-dynamo-cloud.properties \
 *        -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
 *        com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
 * </pre>
 */
public class ChangeFeedExtendedRetentionSample {

    private static final String DEFAULT_CONFIG = "change-feed-cosmos-cloud.properties";

    /**
     * 7 days — strictly greater than the 24-hour portable baseline (so the
     * extended-retention opt-in is actually triggered) and at-or-below every
     * supported provider's native ceiling: Cosmos honours up to 30d via the
     * Continuous Backup 30d tier, and Spanner caps at 7d natively. This is
     * the largest window that succeeds on both providers without provider-
     * specific overrides.
     */
    private static final Duration REQUESTED_RETENTION = Duration.ofDays(7);

    public static void main(String[] args) throws Exception {
        ConfigLoader.AppConfig appConfig = ConfigLoader.load(DEFAULT_CONFIG);
        ProviderId provider = appConfig.sdk().provider();

        System.out.println("=== Multicloud DB Change Feed — Extended Retention Sample ===");
        System.out.println("Provider          : " + provider.displayName());
        System.out.println("Requested window  : " + REQUESTED_RETENTION + " (baseline is 24h)");
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

        // Compose the opted-in config on top of whatever ConfigLoader produced.
        // ConfigLoader is provider-neutral and does not wire changeFeed(...),
        // so the sample is responsible for adding the opt-in.
        MulticloudDbClientConfig sdkConfig = withExtendedRetention(
                appConfig.sdk(), REQUESTED_RETENTION);

        System.out.println("--- Building client with extendedRetention("
                + REQUESTED_RETENTION + ") ---");
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
            System.out.println("Extended retention is honoured by the SDK on this provider. "
                    + "Cursor tokens issued under this client carry the opt-in stamp and "
                    + "can be resumed beyond 24h, up to the requested window.");
            System.out.println();
            System.out.println("Data-plane round-trip (listCursors / readChanges) requires "
                    + "provider-specific substrate setup and is out of scope for this "
                    + "sample — see ChangeFeedSample / ChangeFeedWatcherSample for the "
                    + "24h-baseline data-plane demo.");
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
}
