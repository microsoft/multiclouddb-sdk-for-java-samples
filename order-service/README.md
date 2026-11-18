# Order Service sample

This standalone sample models a small customer order service for Cosmos DB,
DynamoDB, and Spanner. It demonstrates application-owned POJO-to-`Map<String,
Object>` mapping, a customer partition key and order sort key, paged queries,
capability checks, and typed error handling. Money is stored as integer cents,
timestamps as ISO-8601 strings, and status values as strings.

The sample targets the Map API introduced by SDK PR
[microsoft/multiclouddb-sdk-for-java#122](https://github.com/microsoft/multiclouddb-sdk-for-java/pull/122).
The PR is merged as `40b2e2ba5075e17e51b74399f074de28f3762f69`. The Map API is
not yet in a published package, so build this immutable SDK revision and install
its SNAPSHOTs locally. The SDK versions are pinned in
`pom.xml`: API/Cosmos/Dynamo `0.1.0-beta.2-SNAPSHOT` and Spanner
`0.1.0-beta.1-SNAPSHOT`. Change the SNAPSHOT properties to the released
versions after the SDK release. The root samples project remains on the
existing JsonNode API.

## Build and test

Run these commands in one shell, starting in this directory. They build the SDK
at the recorded PR revision and install its SNAPSHOTs into a separate Maven
repository, then use that same repository for the sample. This avoids
overwriting other local SNAPSHOTs.

```bash
cd /path/to/multiclouddb-sdk-for-java-samples/order-service
SAMPLES_DIR="$PWD"
SDK_WORK="$(mktemp -d)"
MAVEN_REPO="$HOME/.m2/multiclouddb-order-service-pr122"

mvn -version # Use JDK 17+; set JAVA_HOME to this Maven runtime for Cosmos TLS setup.
git clone https://github.com/microsoft/multiclouddb-sdk-for-java.git "$SDK_WORK/sdk"
git -C "$SDK_WORK/sdk" fetch origin main
git -C "$SDK_WORK/sdk" checkout --detach 40b2e2ba5075e17e51b74399f074de28f3762f69
mvn -f "$SDK_WORK/sdk/pom.xml" -Dmaven.repo.local="$MAVEN_REPO" \
  -pl multiclouddb-api,multiclouddb-provider-cosmos,multiclouddb-provider-dynamo,multiclouddb-provider-spanner \
  -am -DskipTests install

cd "$SAMPLES_DIR"
mvn -Dmaven.repo.local="$MAVEN_REPO" test
```

Keep using `-Dmaven.repo.local="$MAVEN_REPO"` on every Maven command below so
the sample resolves the exact locally installed SDK SNAPSHOTs.

## Run locally

Run provider commands from `order-service/`. Start only the emulator you intend
to use, and ensure its ports are available first.

### Cosmos DB emulator

The Cosmos configuration uses the HTTPS endpoint on port `8081`. The vNext
emulator also exposes its data explorer on port `1234`.

```bash
docker run -d --name order-service-cosmos-emulator \
  -p 8081:8081 -p 1234:1234 \
  mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-latest --protocol https
```

Import the emulator certificate into a mode-600 copy of the JDK truststore used
by Maven. Starting with the JDK `cacerts` file preserves public CAs required
for Maven downloads.

```bash
mkdir -p "$HOME/.cosmos-emulator"
install -m 600 "$JAVA_HOME/lib/security/cacerts" "$HOME/.cosmos-emulator/cacerts"
openssl s_client -connect localhost:8081 -showcerts </dev/null 2>/dev/null \
  | awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' > "$HOME/.cosmos-emulator/emulator.pem"
keytool -importcert -noprompt -alias order-service-cosmos-emulator \
  -file "$HOME/.cosmos-emulator/emulator.pem" \
  -keystore "$HOME/.cosmos-emulator/cacerts" -storepass changeit

mvn -Dmaven.repo.local="$MAVEN_REPO" compile exec:java \
  -Dmulticlouddb.config=orders-cosmos.properties \
  -Djavax.net.ssl.trustStore="$HOME/.cosmos-emulator/cacerts" \
  -Djavax.net.ssl.trustStorePassword=changeit
```

Set `JAVA_HOME` to the Java home shown by `mvn -version` before copying
`cacerts`. Do not replace the default truststore with the emulator certificate
alone.

### DynamoDB Local

```bash
docker run -d --name order-service-dynamodb-local -p 8000:8000 amazon/dynamodb-local
mvn -Dmaven.repo.local="$MAVEN_REPO" compile exec:java \
  -Dmulticlouddb.config=orders-dynamo.properties
```

### Spanner emulator

```bash
docker run -d --name order-service-spanner-emulator \
  -p 9010:9010 -p 9020:9020 gcr.io/cloud-spanner-emulator/emulator
```

Create the emulator instance, database, and table before running the sample:

```bash
curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"instanceId":"test-instance","instance":{"config":"emulator-config","displayName":"Order Service","nodeCount":1}}' \
  http://localhost:9020/v1/projects/test-project/instances

curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"createStatement":"CREATE DATABASE `multiclouddb-orders`","extraStatements":["CREATE TABLE orders (partitionKey STRING(MAX) NOT NULL, sortKey STRING(MAX) NOT NULL, data STRING(MAX), customerId STRING(MAX), orderId STRING(MAX), idempotencyKey STRING(MAX), lines STRING(MAX), totalCents INT64, placedAt STRING(MAX), status STRING(MAX), note STRING(MAX)) PRIMARY KEY (partitionKey, sortKey)"]}' \
  http://localhost:9020/v1/projects/test-project/instances/test-instance/databases

mvn -Dmaven.repo.local="$MAVEN_REPO" compile exec:java \
  -Dmulticlouddb.config=orders-spanner.properties
```

Spanner has a fixed schema. The SDK creates only its key and metadata columns;
the `orders` table must also contain all order fields before the first run. For
a cloud database, create the table once with this DDL:

```sql
CREATE TABLE orders (
  partitionKey STRING(MAX) NOT NULL,
  sortKey STRING(MAX) NOT NULL,
  data STRING(MAX),
  customerId STRING(MAX),
  orderId STRING(MAX),
  idempotencyKey STRING(MAX),
  lines STRING(MAX),
  totalCents INT64,
  placedAt STRING(MAX),
  status STRING(MAX),
  note STRING(MAX)
) PRIMARY KEY (partitionKey, sortKey)
```

`data` is reserved SDK metadata. The Spanner provider encodes nested `lines`
values as marked JSON strings; top-level nullable fields are omitted.

Each invocation generates unique order keys and idempotency keys. Only orders
created by that invocation are cleaned up. Customer queries use a page size of
two and continuation tokens. The status query runs only when cross-partition
queries are supported; customer ordering uses `ORDER BY` when available and
otherwise sorts the fetched result in the application.

## Run against cloud databases

Copy the matching `*-cloud.properties.template` in `src/main/resources` to
`orders-<provider>-cloud.properties`, fill in the cloud identifiers, and run
from this directory. The filled cloud config files are git-ignored.

```bash
mvn -Dmaven.repo.local="$MAVEN_REPO" compile exec:java \
  -Dmulticlouddb.config=orders-cosmos-cloud.properties
```

DynamoDB uses the default AWS credential chain, and Spanner uses Application
Default Credentials. Cosmos DB uses `DefaultAzureCredential` when no account
key is configured; `multiclouddb.connection.tenantId` is an optional tenant
hint. With data-plane-only Entra permissions, create the database and container
(partition key path `/partitionKey`) beforehand.

## Update semantics

`updateStatus` demonstrates read-modify-write with a fresh read after a write
conflict. The portable SDK `update` operation does not expose a conditional-write
token, so this is not optimistic concurrency control: concurrent writers are
last-write-wins. `place` uses a per-request idempotency key to distinguish an
ambiguous successful create from a preexisting order after a retry conflict.
