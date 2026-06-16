// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.changefeed;

import com.multiclouddb.samples.ConfigLoader;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.models.ChangeFeedPolicy;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.ThroughputProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Helpers shared by {@link ChangeFeedSample} and {@link ChangeFeedWatcherSample}.
 * <p>
 * The change-feed samples reach into the Azure Cosmos SDK directly for one
 * purpose: pre-provisioning an All-Versions-and-Deletes (AVAD) container on
 * the Cosmos emulator, which does not support Continuous Backup. On a live
 * Continuous-Backup account this provisioning is implicit (the AVAD change
 * feed is available automatically on every container) and this helper is not
 * invoked.
 */
final class ChangeFeedSampleSupport {

    private ChangeFeedSampleSupport() {
    }

    /**
     * Returns {@code true} iff {@code endpoint} resolves to {@code localhost}
     * or {@code 127.0.0.1} after URI parsing. Substring matching is
     * intentionally avoided because it false-positives on URLs that merely
     * contain the substring (e.g. {@code https://prod-localhost-backup.contoso.com/}
     * or {@code https://127.0.0.10:8081}).
     */
    static boolean isLocalEndpoint(String endpoint) {
        if (endpoint == null) return false;
        String host;
        try {
            host = URI.create(endpoint).getHost();
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    /**
     * Pre-provision a Cosmos AVAD container directly via the Cosmos SDK.
     * <p>
     * Only invoke this on the local emulator: the emulator does not support
     * Continuous Backup, so AVAD must be configured explicitly via
     * {@link ChangeFeedPolicy#createAllVersionsAndDeletesPolicy(Duration)}.
     * The emulator caps AVAD retention at 10 minutes; that ceiling is the
     * value used here. On a live CB-enabled account AVAD is available
     * automatically and a plain {@code ensureContainer(...)} suffices.
     *
     * @param throughputRU provisioned throughput in RU/s. Use ≥ 30,000 to
     *                     guarantee 3+ physical partitions (each partition
     *                     tops out at ~10,000 RU/s). Pass 0 or negative to
     *                     let Cosmos pick the default (typically 400 RU/s →
     *                     1 partition).
     */
    static void provisionCosmosAvadContainer(ConfigLoader.AppConfig appConfig,
                                             String database,
                                             String collection,
                                             int throughputRU) {
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
            CosmosContainerProperties props =
                    new CosmosContainerProperties(collection, "/partitionKey");
            props.setChangeFeedPolicy(
                    ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(Duration.ofMinutes(10)));
            if (throughputRU > 0) {
                cosmos.getDatabase(database).createContainerIfNotExists(
                        props, ThroughputProperties.createManualThroughput(throughputRU));
                System.out.println("  [provision] AVAD container '" + database + "/" + collection
                        + "' ready (emulator retention=10min, throughput=" + throughputRU + " RU/s)");
            } else {
                cosmos.getDatabase(database).createContainerIfNotExists(props);
                System.out.println("  [provision] AVAD container '" + database + "/" + collection
                        + "' ready (emulator retention=10min)");
            }
        }
    }
}
