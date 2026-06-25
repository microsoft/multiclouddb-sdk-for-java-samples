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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;

/**
 * Helpers shared by the change-feed samples ({@link ChangeFeedSample},
 * {@link ChangeFeedWatcherSample}, and {@link ChangeFeedExtendedRetentionSample}).
 * <p>
 * Currently only Azure Cosmos DB is supported. The helper pre-provisions an
 * AVAD container with an explicit {@link ChangeFeedPolicy} on the local
 * emulator (which does not support Continuous Backup).
 */
final class ChangeFeedSampleSupport {

    private static final Logger log = LoggerFactory.getLogger(ChangeFeedSampleSupport.class);

    private ChangeFeedSampleSupport() {
    }

    /**
     * Returns {@code true} when the config carries no Cosmos master key. The
     * samples treat a missing/blank key as <em>Entra ID mode</em> and let the
     * SDK authenticate with Microsoft Entra ID via {@code DefaultAzureCredential}
     * (driven by the {@code subscriptionId} / {@code resourceGroupName} /
     * {@code tenantId} connection properties).
     * <p>
     * In Entra ID mode the identity is typically granted only a data-plane RBAC
     * role (e.g. <i>Cosmos DB Built-in Data Contributor</i>), so control-plane
     * operations such as {@code ensureDatabase()} / {@code ensureContainer()}
     * (which call {@code createDatabaseIfNotExists} under the hood) are not
     * permitted. The samples use this to skip provisioning and rely on a
     * pre-created database and container instead (see README-change-feed.md).
     */
    static boolean usesEntraId(ConfigLoader.AppConfig appConfig) {
        String key = appConfig.sdk().connection().get("key");
        return key == null || key.isBlank();
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
                log.info("  [provision] AVAD container '{}/{}' ready (emulator retention=10min, throughput={} RU/s). Note: existing containers are not retrofitted.",
                        database, collection, throughputRU);
            } else {
                cosmos.getDatabase(database).createContainerIfNotExists(props);
                log.info("  [provision] AVAD container '{}/{}' ready (emulator retention=10min). Note: existing containers are not retrofitted.",
                        database, collection);
            }
        }
    }
}
