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

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Helpers shared by the change-feed samples ({@link ChangeFeedSample},
 * {@link ChangeFeedWatcherSample}, and {@link ChangeFeedExtendedRetentionSample}).
 * <p>
 * For Azure Cosmos DB the helper pre-provisions an AVAD container with an
 * explicit {@link ChangeFeedPolicy} on the local emulator (which does not
 * support Continuous Backup). For Amazon DynamoDB the helper enables a
 * DynamoDB Stream (NEW_AND_OLD_IMAGES) on the table, which the SDK's
 * {@code ensureContainer} does not turn on by default.
 */
final class ChangeFeedSampleSupport {

    private static final Logger log = LoggerFactory.getLogger(ChangeFeedSampleSupport.class);

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
                log.info("  [provision] AVAD container '{}/{}' ready (emulator retention=10min, throughput={} RU/s). Note: existing containers are not retrofitted.",
                        database, collection, throughputRU);
            } else {
                cosmos.getDatabase(database).createContainerIfNotExists(props);
                log.info("  [provision] AVAD container '{}/{}' ready (emulator retention=10min). Note: existing containers are not retrofitted.",
                        database, collection);
            }
        }
    }

    /** DynamoDB composes the table name as {@code database__collection}. */
    private static final String DYNAMO_TABLE_NAME_SEPARATOR = "__";

    /**
     * Enable a DynamoDB Stream on the table backing {@code database/collection}
     * so the change feed has a source to read from.
     * <p>
     * The SDK's portable {@code ensureContainer} creates the table but does
     * <b>not</b> enable streams, so {@code listCursors}/{@code readChanges}
     * would otherwise fail with {@code UNSUPPORTED_CAPABILITY(stream_not_enabled)}.
     * This helper turns on a {@link StreamViewType#NEW_AND_OLD_IMAGES} stream
     * (required so DELETE events carry the old image) and waits up to 60
     * seconds for the table to become ACTIVE with a stream ARN (logging a
     * warning if it does not within that window). It is idempotent: if the
     * stream is already enabled with the right view type, it skips the update
     * and only waits for the stream ARN to appear.
     * <p>
     * Call this <b>after</b> {@code ensureContainer} (the table must exist).
     * Only events committed <b>after</b> the stream is enabled are surfaced.
     */
    static void enableDynamoStream(ConfigLoader.AppConfig appConfig,
                                   String database,
                                   String collection) {
        String tableName = database + DYNAMO_TABLE_NAME_SEPARATOR + collection;
        try (DynamoDbClient ddb = buildDynamoClient(appConfig)) {
            TableDescription table = ddb.describeTable(b -> b.tableName(tableName)).table();
            StreamSpecification spec = table.streamSpecification();
            boolean streamConfigured = spec != null
                    && Boolean.TRUE.equals(spec.streamEnabled())
                    && spec.streamViewType() == StreamViewType.NEW_AND_OLD_IMAGES;

            if (streamConfigured && table.latestStreamArn() != null) {
                log.info("  [provision] DynamoDB Stream already enabled on '{}' (NEW_AND_OLD_IMAGES).",
                        tableName);
                return;
            }

            if (streamConfigured) {
                // The stream is already requested with the right view type but the ARN
                // hasn't populated yet (enablement still in progress). Skip updateTable
                // to avoid ResourceInUseException and just wait for it to become active.
                log.info("  [provision] DynamoDB Stream on '{}' is still enabling (NEW_AND_OLD_IMAGES); "
                        + "waiting for it to become active.", tableName);
            } else {
                ddb.updateTable(b -> b.tableName(tableName)
                        .streamSpecification(s -> s.streamEnabled(true)
                                .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)));
            }

            if (waitForTableStreamActive(ddb, tableName)) {
                log.info("  [provision] Enabled DynamoDB Stream on '{}' (NEW_AND_OLD_IMAGES). "
                        + "Only changes committed after this point are surfaced.", tableName);
            } else {
                log.warn("  [provision] Requested DynamoDB Stream on '{}' (NEW_AND_OLD_IMAGES) but it "
                        + "is not ACTIVE yet; subsequent change-feed reads may fail until it is.", tableName);
            }
        }
    }

    /**
     * Poll until the table is {@link TableStatus#ACTIVE} with a stream ARN.
     *
     * @return {@code true} if the stream became active before the timeout,
     *         {@code false} if it timed out or the thread was interrupted.
     */
    private static boolean waitForTableStreamActive(DynamoDbClient ddb, String tableName) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            TableDescription table = ddb.describeTable(b -> b.tableName(tableName)).table();
            if (table.tableStatus() == TableStatus.ACTIVE && table.latestStreamArn() != null) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static DynamoDbClient buildDynamoClient(ConfigLoader.AppConfig appConfig) {
        Map<String, String> connection = appConfig.sdk().connection();
        Map<String, String> auth = appConfig.sdk().auth();

        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(resolveRegion(connection)));

        String endpoint = connection.get("endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        String accessKeyId = auth.get("accessKeyId");
        String secretAccessKey = auth.get("secretAccessKey");
        if (accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        // Otherwise fall back to the default AWS credential provider chain
        // (env vars, system properties, ~/.aws/credentials, IAM roles, SSO).

        return builder.build();
    }

    private static String resolveRegion(Map<String, String> connection) {
        String region = connection.getOrDefault("region", "us-east-1");
        region = region == null ? "" : region.trim();
        if (region.isEmpty() || region.startsWith("<")) {
            throw new IllegalStateException(
                    "Invalid AWS region '" + region + "'. Set 'multiclouddb.connection.region' in "
                    + "your properties file to a valid region (e.g., us-east-1) before running "
                    + "against AWS DynamoDB.");
        }
        return region;
    }
}
