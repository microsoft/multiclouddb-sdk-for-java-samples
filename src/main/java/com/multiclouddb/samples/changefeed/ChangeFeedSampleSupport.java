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

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

import java.net.URI;
import java.time.Duration;

/**
 * Helpers shared by the change-feed samples ({@link ChangeFeedSample},
 * {@link ChangeFeedWatcherSample}, and {@link ChangeFeedExtendedRetentionSample}).
 * <p>
 * The change-feed samples reach into provider-native SDKs directly for
 * provisioning workarounds:
 * <ul>
 *   <li><b>Cosmos emulator</b> — pre-provisions an AVAD container with an
 *       explicit {@link ChangeFeedPolicy} (the emulator does not support
 *       Continuous Backup).</li>
 *   <li><b>DynamoDB (Local or cloud)</b> — enables DynamoDB Streams with
 *       {@code NEW_AND_OLD_IMAGES} on the table if not already enabled
 *       (the SDK's {@code ensureContainer} does not enable streams).</li>
 * </ul>
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
                        + "' ready (emulator retention=10min, throughput=" + throughputRU
                        + " RU/s). Note: existing containers are not retrofitted.");
            } else {
                cosmos.getDatabase(database).createContainerIfNotExists(props);
                System.out.println("  [provision] AVAD container '" + database + "/" + collection
                        + "' ready (emulator retention=10min). Note: existing containers are not retrofitted.");
            }
        }
    }

    /**
     * Ensure DynamoDB Streams is enabled on the table with
     * {@code NEW_AND_OLD_IMAGES} stream view type. The SDK's
     * {@code ensureContainer()} creates the table but does not enable
     * streams, so the change-feed samples must do it out-of-band.
     * <p>
     * If streams are already enabled this is a no-op.
     *
     * @param tableName the DynamoDB table name (e.g. {@code local__change-feed-demo})
     */
    static void enableDynamoStreams(ConfigLoader.AppConfig appConfig, String tableName) {
        String endpoint = appConfig.sdk().connection().get("endpoint");
        String region = appConfig.sdk().connection().getOrDefault("region", "us-east-1");
        String accessKey = appConfig.property("multiclouddb.auth.accessKeyId", null);
        String secretKey = appConfig.property("multiclouddb.auth.secretAccessKey", null);

        var builder = DynamoDbClient.builder()
                .region(Region.of(region));

        // Use explicit credentials only if provided (DynamoDB Local);
        // otherwise fall back to the default AWS credential chain (cloud).
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        try (DynamoDbClient dynamo = builder.build()) {
            // Check if streams are already enabled
            DescribeTableResponse desc = dynamo.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            StreamSpecification existing = desc.table().streamSpecification();
            if (existing != null && Boolean.TRUE.equals(existing.streamEnabled())
                    && StreamViewType.NEW_AND_OLD_IMAGES.equals(existing.streamViewType())) {
                System.out.println("  [provision] DynamoDB Streams already enabled on '"
                        + tableName + "' (NEW_AND_OLD_IMAGES)");
                return;
            }

            // Enable streams
            dynamo.updateTable(UpdateTableRequest.builder()
                    .tableName(tableName)
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build())
                    .build());
            System.out.println("  [provision] Enabled DynamoDB Streams on '"
                    + tableName + "' (NEW_AND_OLD_IMAGES)");
        }
    }
}
