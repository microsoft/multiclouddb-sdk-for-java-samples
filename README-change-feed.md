# Multicloud DB SDK — Change Feed Samples

Three command-line samples that demonstrate the Multicloud DB SDK's **pull-mode
change feed** across Azure Cosmos DB, Google Cloud Spanner, and Amazon DynamoDB.

## Provider Support Matrix

| Sample | Azure Cosmos DB | Google Cloud Spanner | Amazon DynamoDB |
|--------|:---------------:|:--------------------:|:---------------:|
| **`ChangeFeedSample`** (one-shot) | ✅ Supported | ✅ Supported | ✅ Supported |
| **`ChangeFeedWatcherSample`** (continuous) | ✅ Supported | ✅ Supported | ✅ Supported |
| **`ChangeFeedExtendedRetentionSample`** (build-time gate) | ✅ Gate passes | ✅ Gate passes | ❌ Gate refuses (expected) |

> **Provider-specific prerequisites.** All three providers declare
> `Capability.CHANGE_FEED`, so the samples work on any of them. However,
> each provider requires out-of-band setup before the change feed is
> functional:
>
> | Provider | Prerequisite |
> |----------|-------------|
> | **Cosmos DB (live)** | Account must have **Continuous Backup** enabled (AVAD is then automatic on every container). |
> | **Cosmos DB (emulator)** | The sample auto-provisions an AVAD container with a 10-min retention policy (no manual setup). |
> | **DynamoDB** | Table must have `StreamSpecification(NEW_AND_OLD_IMAGES)` enabled. |
> | **Spanner** | A change stream must be created: `CREATE CHANGE STREAM <name> FOR <collection> OPTIONS(value_capture_type='NEW_ROW')`. |
>
> Without the prerequisite, `listCursors` / `readChanges` will surface a
> portable `UNSUPPORTED_CAPABILITY(stream_not_enabled)` error.

### Sample descriptions

| Sample | Behavior | Use it for |
|--------|----------|------------|
| **`ChangeFeedSample`** | One-shot demo: writer thread produces a fixed `CREATE` / `UPDATE` / `DELETE` sequence; consumer drains the feed; both exit. | Validating that change feed is wired up end-to-end. |
| **`ChangeFeedWatcherSample`** | Long-running consumer with **no writes of its own**. Polls the change feed and prints each event as it arrives. `Ctrl+C` → final tally. | Observing changes you make manually in the Azure Portal Data Explorer (or any other writer). |
| **`ChangeFeedExtendedRetentionSample`** | Opts into `ChangeFeedConfig.extendedRetention(Duration.ofDays(7))` and attempts to build a client. Succeeds on Cosmos and Spanner (which declare `Capability.EXTENDED_CHANGE_FEED_HISTORY`); fails fast on DynamoDB with `UNSUPPORTED_CAPABILITY`. | Verifying which providers can be asked for longer-than-24-hour change-feed history before you write any cursor-persistence code. |

The first two samples target the dedicated database/container
`multiclouddb-sdk-for-java-changefeed/change-feed-demo` (configurable via
`multiclouddb.database` / `multiclouddb.collection`) so they don't collide with
the Todo App or Risk Platform samples.

> **Package layout.** All three samples live under
> `src/main/java/com/multiclouddb/samples/changefeed/` and are in the
> `com.multiclouddb.samples.changefeed` Java package (mirroring the
> per-sample layout used by `todo/` and `riskplatform/`). Configuration
> templates stay at the resources root (`src/main/resources/change-feed-*.properties[.template]`)
> because `ConfigLoader` reads them by classpath name.

### Configuration files per provider

| Provider | Emulator / Local config (shipped) | Cloud config (template → copy + fill in) |
|----------|-----------------------------------|------------------------------------------|
| **Cosmos DB** | `change-feed-cosmos.properties` | `change-feed-cosmos-cloud.properties.template` |
| **Spanner** | `change-feed-spanner.properties` | `change-feed-spanner-cloud.properties.template` |
| **DynamoDB** | `change-feed-dynamo.properties` | `change-feed-dynamo-cloud.properties.template` |

> Emulator/local configs work out of the box for the extended-retention
> sample (no credentials needed — the gate runs before any wire I/O).
> Cloud configs (gitignored) require real credentials; copy the
> `.template` file, fill in your endpoint/keys, then rebuild the fat jar.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Provisioning Model — Why Continuous Backup Matters](#provisioning-model--why-continuous-backup-matters)
3. [Multiple Partitions — Seeing 3+ Cursors](#multiple-partitions--seeing-3-cursors)
4. [Emulator Setup](#emulator-setup)
   - [Cosmos DB Emulator](#cosmos-db-emulator)
   - [Spanner Emulator](#spanner-emulator)
   - [DynamoDB Local](#dynamodb-local)
5. [Running the Samples](#running-the-samples)
   - [Build the fat jar](#build-the-fat-jar)
   - [Against the Cosmos DB Emulator](#run-against-the-cosmos-db-emulator)
   - [Against Cosmos DB (Azure Cloud)](#run-against-cosmos-db-azure-cloud)
   - [Tuning the watcher poll interval](#tuning-the-watcher-poll-interval)
6. [Example Output](#example-output)
   - [`ChangeFeedSample` (one-shot)](#changefeedsample-one-shot)
   - [`ChangeFeedWatcherSample` (continuous)](#changefeedwatchersample-continuous)
7. [Extended Retention Escape Hatch](#extended-retention-escape-hatch)
   - [Per-provider behaviour](#per-provider-behaviour)
   - [Running `ChangeFeedExtendedRetentionSample`](#running-changefeedextendedretentionsample)
   - [Example output](#example-output-changefeedextendedretentionsample)
8. [Configuration Reference](#configuration-reference)
9. [Cloud Setup](#cloud-setup)
   - [Step 1 — Create a Continuous-Backup Cosmos account](#step-1--create-a-continuous-backup-cosmos-account)
   - [Step 2 — Create the properties file](#step-2--create-the-properties-file)
   - [Step 3 — Build and run](#step-3--build-and-run)
   - [Step 4 — Clean up Cosmos DB resources](#step-4--clean-up-cosmos-db-resources-optional)
10. [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 17 LTS  | Required — e.g. [Eclipse Adoptium](https://adoptium.net/) |
| Maven | 3.9+   | Build tool |
| Azure Cosmos DB Emulator **or** an Azure Cosmos DB account | latest | Either works; live accounts must have Continuous Backup enabled (see below) |
| Azure CLI | optional | Only needed if you provision the live account from the command line |

Make sure `JAVA_HOME` points to JDK 17 and is on your `PATH`:

```powershell
# PowerShell example
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # should say 17.x
```

> **SDK dependencies** are pulled from Maven Central by default — no separate
> SDK build step is required. To test against a locally-built SDK, see
> [`../README.md#sdk-version`](README.md#sdk-version) for the
> `.mvn/maven.config` override workflow.

---

## Provisioning Model — Why Continuous Backup Matters

Pull-mode change feed in AVAD mode needs different provisioning depending on the
target. The samples auto-select the correct path based on the configured
endpoint:

| Environment | Provisioning | Why |
|-------------|--------------|-----|
| **Live Cosmos** (CB enabled) | Plain `ensureContainer()` — no `ChangeFeedPolicy` | When Continuous Backup is on, the AVAD change feed is **available automatically on every container**. Setting an explicit `ChangeFeedPolicy.retentionDuration` is rejected by the service. |
| **Cosmos emulator** | Pre-provisions container with `ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(Duration.ofMinutes(10))` via a one-shot Cosmos SDK client | The emulator does not support Continuous Backup, so AVAD must be opted into per-container. 10 minutes is the emulator's hard ceiling for AVAD retention. |

Verify a live account has CB enabled:

```bash
az cosmosdb show --name <account> --resource-group <rg> \
  --query backupPolicy.type -o tsv
# Expected output: Continuous
```

> **Why not `ChangeFeedConfig.extendedRetention(Duration.ofDays(7))` against
> live Cosmos?** On a CB-enabled account the service rejects it with
> *"The retention duration in the Change Feed policy should not be set when
> continuous backup mode is enabled for the database account."* — and the SDK
> currently mis-maps that 400 to `UNSUPPORTED_CAPABILITY(continuous_backup_required)`,
> which is the opposite of the real cause. Per the
> [Cosmos docs](https://learn.microsoft.com/en-us/azure/cosmos-db/change-feed-modes?tabs=all-versions-and-deletes),
> *"Turning on continuous backups creates the all versions and deletes change
> feed"* — i.e. CB makes AVAD automatic, so the opt-in is unnecessary and
> harmful on CB accounts.

---

## Multiple Partitions — Seeing 3+ Cursors

Each physical partition in Cosmos DB maps to one change-feed cursor returned
by `listCursors(...)`. A small container with default throughput
(~400 RU/s) typically has **1 physical partition → 1 cursor**. To see events
flowing through multiple cursors in the sample output, you need to force
multiple physical partitions.

### How Cosmos DB assigns physical partitions

| Provisioned throughput | Physical partitions | Cursors |
|------------------------|---------------------|---------|
| ≤ 10,000 RU/s         | 1                   | 1       |
| 10,001–20,000 RU/s    | 2                   | 2       |
| 20,001–30,000 RU/s    | 3                   | 3       |
| …                      | …                   | …       |

Cosmos allocates roughly **1 physical partition per 10,000 RU/s**.
Partitions never merge back, so you can scale throughput up to create
partitions and then scale it back down to save cost — the partitions
(and cursors) persist.

### Quick setup: 3 partitions on the emulator

Uncomment and set `multiclouddb.throughput=30000` in
`change-feed-cosmos.properties`:

```properties
multiclouddb.throughput=30000
```

Then run `ChangeFeedSample` or `ChangeFeedWatcherSample` — the provisioning
step will create the container with 30,000 RU/s (3 physical partitions).
The sample output will show:

```
Discovered 3 partition cursor(s) at the live tip.
  cursor-0: eyJ0eXBlIj…
  cursor-1: eyJ0eXBlIj…
  cursor-2: eyJ0eXBlIj…
```

> **Note:** `createContainerIfNotExists` is a no-op if the container already
> exists. If you previously ran with the default throughput, **delete the
> container first** (via the emulator UI or the Azure Portal) so it gets
> recreated with the higher throughput:
>
> ```
> # Emulator UI: https://localhost:8081/_explorer/index.html
> # Delete the database 'multiclouddb-sdk-for-java-changefeed', then re-run.
> ```

### Quick setup: 3 partitions on a live Cosmos account

#### 1. Raise the account throughput limit (if needed)

The default limit on many accounts is 4,000 RU/s. In the Azure Portal:

**Azure Portal** → Cosmos DB account → **Settings** → **Account Throughput**
→ raise the limit to ≥ 50,000 → **Save**.

#### 2. Scale the container to 30,000 RU/s

```powershell
az cosmosdb sql container throughput update `
    --account-name <account> -g <rg> `
    --database-name multiclouddb-sdk-for-java-changefeed `
    --name change-feed-demo `
    --throughput 30000
```

#### 3. Wait for the partition split to complete

The split is asynchronous and typically takes 4–10 minutes. Monitor with:

```powershell
az cosmosdb sql container throughput show `
    --account-name <account> -g <rg> `
    --database-name multiclouddb-sdk-for-java-changefeed `
    --name change-feed-demo `
    --query "resource.instantMaximumThroughput"
```

- `"10000"` → 1 partition (split not started)
- `"20000"` → 2 partitions (in progress)
- `"30000"` → 3 partitions (**done** ✓)

#### 4. Scale throughput back down to save cost

Once the split is done, physical partitions **never merge back**, so you
can scale down immediately and keep the 3 cursors:

```powershell
az cosmosdb sql container throughput update `
    --account-name <account> -g <rg> `
    --database-name multiclouddb-sdk-for-java-changefeed `
    --name change-feed-demo `
    --throughput 400
```

> **Cost note:** 30,000 RU/s costs ~$1.75/hr. Scale down as soon as the
> split completes — you only need the high throughput long enough for Cosmos
> to create the physical partitions.

#### 5. Run the watcher and add items with different partition keys

```powershell
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

Then in the **Azure Portal** → **Data Explorer** → `change-feed-demo` →
**New Item**, add items with varied `partitionKey` values so they hash to
different physical partitions:

```json
{"id": "a1", "partitionKey": "1",     "title": "test"}
{"id": "a2", "partitionKey": "100",   "title": "test"}
{"id": "a3", "partitionKey": "999",   "title": "test"}
{"id": "a4", "partitionKey": "abc",   "title": "test"}
{"id": "a5", "partitionKey": "zzz",   "title": "test"}
{"id": "a6", "partitionKey": "hello", "title": "test"}
```

The watcher will print each event with its cursor index. Example output
showing events distributed across all 3 cursors:

```
Discovered 3 partition cursor(s) at the live tip.
  cursor-0: eyJ2IjoxLCJwIjoiY29zbW9z…
  cursor-1: eyJ2IjoxLCJwIjoiY29zbW9z…
  cursor-2: eyJ2IjoxLCJwIjoiY29zbW9z…

Watching multiclouddb-sdk-for-java-changefeed/change-feed-demo …
Press Ctrl+C to stop.

[2026-06-16T18:26:39Z] cursor-0  CREATE  MulticloudDbKey{partitionKey=1, sortKey=a1}      {"id":"a1","partitionKey":"1","title":"test", …}
[2026-06-16T18:26:47Z] cursor-1  CREATE  MulticloudDbKey{partitionKey=100, sortKey=a2}    {"id":"a2","partitionKey":"100","title":"test", …}
[2026-06-16T18:26:55Z] cursor-1  CREATE  MulticloudDbKey{partitionKey=999, sortKey=a3}    {"id":"a3","partitionKey":"999","title":"test", …}
[2026-06-16T18:27:02Z] cursor-2  CREATE  MulticloudDbKey{partitionKey=abc, sortKey=a4}    {"id":"a4","partitionKey":"abc","title":"test", …}
[2026-06-16T18:27:10Z] cursor-0  CREATE  MulticloudDbKey{partitionKey=zzz, sortKey=a5}    {"id":"a5","partitionKey":"zzz","title":"test", …}
[2026-06-16T18:27:15Z] cursor-0  CREATE  MulticloudDbKey{partitionKey=hello, sortKey=a6}  {"id":"a6","partitionKey":"hello","title":"test", …}
```

> **Why do some keys land on the same cursor?** Cosmos hashes the partition
> key and maps it to a hash range owned by a physical partition. With only 3
> partitions, some keys inevitably collide. Items with the **same**
> `partitionKey` always appear on the same cursor.

### Reading cursor identifiers in the sample output

Both `ChangeFeedSample` and `ChangeFeedWatcherSample` now prefix each
change event with the cursor index (e.g. `cursor-0`, `cursor-1`, …) so
you can see which physical partition each event came from:

```
[2025-06-16T09:00:01Z] cursor-0  CREATE  cf-1  {"title":"Event 1","iteration":1}
[2025-06-16T09:00:01Z] cursor-2  CREATE  cf-2  {"title":"Event 2","iteration":2}
[2025-06-16T09:00:02Z] cursor-1  UPDATE  cf-1  {"title":"Event 1 (updated)","iteration":99}
```

Items with different `/partitionKey` values will land in different physical
partitions, so you'll see events spread across cursors. Items with the
same partition key always appear on the same cursor.

---

## Emulator Setup

All three samples work against emulators / local endpoints. The data-plane
samples (`ChangeFeedSample`, `ChangeFeedWatcherSample`) require the
provider-specific change-feed prerequisite to be met (see the Provider
Support Matrix above). `ChangeFeedExtendedRetentionSample` exercises the
build-time capability gate and works before any wire I/O — so it works
even if the emulator isn't running.

### Cosmos DB Emulator

The Cosmos DB emulator provides a free local instance of Azure Cosmos DB for
development and testing.

#### 1. Install

Download and install from:\
<https://learn.microsoft.com/en-us/azure/cosmos-db/emulator#install-the-emulator>

> **Windows:** Run the MSI installer. The emulator is added to Start Menu.\
> **Docker** (Linux / macOS):
> ```bash
> docker pull mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
> docker run -p 8081:8081 -p 10250-10255:10250-10255 \
>   mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
> ```

#### 2. Start the emulator

On Windows, launch **Azure Cosmos DB Emulator** from the Start Menu (or system
tray). It starts on **<https://localhost:8081>** by default.

Open the Data Explorer in your browser:\
<https://localhost:8081/_explorer/index.html>

#### 3. No manual database / container needed

Unlike the Todo App sample, you do **not** need to pre-create the database or
container in Data Explorer. The samples call `ensureDatabase()` /
`ensureContainer()` (with an explicit AVAD `ChangeFeedPolicy` for the emulator)
on startup, so a fresh emulator works out of the box.

#### 4. Emulator connection details (already in `change-feed-cosmos.properties`)

| Property | Value |
|----------|-------|
| Endpoint | `https://localhost:8081` |
| Key      | `C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==` (well-known emulator key) |
| Connection mode | `gateway` (required for emulator) |
| Database | `multiclouddb-sdk-for-java-changefeed` |
| Container | `change-feed-demo` |

#### 5. SSL certificate trust

The emulator uses a self-signed TLS certificate. If you see SSL errors, import
the emulator certificate into your JDK truststore (see the
[Todo App README](README-todo-app.md#5-ssl-certificate-trust) for a detailed
walk-through that applies here too).

### Spanner Emulator

The Cloud Spanner emulator provides a free local instance of Spanner for
development and testing. `ChangeFeedExtendedRetentionSample` connects to it
through the SDK's standard `emulatorHost` knob.

#### 1. Start the emulator

```bash
docker run --rm -p 9010:9010 -p 9020:9020 \
  gcr.io/cloud-spanner-emulator/emulator
```

#### 2. No instance / database creation required

`ChangeFeedExtendedRetentionSample` stops at the build-time gate; it does not
issue DDL or DML. The shipped config (`change-feed-spanner.properties`) uses
placeholder values (`projectId=test-project`, `instanceId=test-instance`,
`databaseId=multiclouddb-sdk-for-java-changefeed`) that work as-is for the
build-time gate demo. If you extend the sample to issue real reads / writes,
follow the [Spanner emulator
docs](https://cloud.google.com/spanner/docs/emulator#create-instance) to
create an instance and database first.

#### 3. Emulator connection details (already in `change-feed-spanner.properties`)

| Property | Value |
|----------|-------|
| Project ID | `test-project` |
| Instance ID | `test-instance` |
| Database ID | `multiclouddb-sdk-for-java-changefeed` |
| Emulator host | `localhost:9010` |

### DynamoDB Local

`ChangeFeedExtendedRetentionSample` against DynamoDB **refuses to build the
client at all** — the build-time capability gate fails with
`UNSUPPORTED_CAPABILITY(extended_retention_unavailable)` before any wire I/O
is issued. The endpoint is never contacted, so **you do not need to start
DynamoDB Local** (or have Docker installed at all) for the build-time-gate
demo. Just run the sample against the shipped `change-feed-dynamo.properties`
config and you will see the expected `exit 1` refusal.

If you do want a running DynamoDB Local endpoint (for example, to extend the
sample later to issue real reads / writes):

#### 1. Start DynamoDB Local (optional)

```bash
docker run --rm -p 8000:8000 amazon/dynamodb-local
```

> **No Docker?** Either install [Docker Desktop for
> Windows](https://docs.docker.com/desktop/install/windows-install/) or
> [Podman](https://podman.io/), or download the standalone DynamoDB Local
> tarball from the [AWS docs](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.DownloadingAndRunning.html)
> and run `java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -sharedDb`.
> Again, **none of this is required for the build-time gate demo**.

#### 2. No table creation required

The sample never issues any DynamoDB API call — it stops at the SDK's
build-time capability gate. So even if you do start DynamoDB Local, you
don't need to create a table for the gate demo.

#### 3. Emulator connection details (already in `change-feed-dynamo.properties`)

| Property | Value |
|----------|-------|
| Endpoint | `http://localhost:8000` |
| Region | `us-east-1` |
| Access key | `fakeMyKeyId` (DynamoDB Local accepts anything) |
| Secret key | `fakeSecretAccessKey` |

---

## Running the Samples

### Build the fat jar

The samples are launched as plain `java -cp <jar> <MainClass>` invocations, so
build the assembled jar once:

```bash
mvn package -DskipTests
```

This produces `target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar`,
which contains the samples plus every runtime dependency.

> **Why not `mvn exec:java`?** The `exec-maven-plugin` in `pom.xml` is pinned to
> `mainClass=com.multiclouddb.samples.PortableCrudQuerySample` and overriding
> it via `-Dexec.mainClass=...` does not always take effect on PowerShell. The
> fat-jar path is uniformly reliable across shells.

### Run against the Cosmos DB Emulator

The default config (`change-feed-cosmos.properties`) targets the emulator and
is loaded automatically when no `-Dmulticlouddb.config` is supplied.

**One-shot demo:**

```bash
java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample
```

**Continuous watcher:**

```bash
java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

Then open <https://localhost:8081/_explorer/index.html>, navigate to the
`multiclouddb-sdk-for-java-changefeed → change-feed-demo` container, and
add / edit / delete items — each operation prints a line in the watcher
terminal.

### Run against Cosmos DB (Azure Cloud)

> **First time?** Complete the [Cloud Setup](#cloud-setup) below to create your
> properties file and provision a CB-enabled Cosmos DB account.

`ConfigLoader` reads configs from the fat-jar classpath, so the runtime file
must live under `src/main/resources/` **before** you run `mvn package`. After
the one-time properties-file copy you can re-use the resulting fat jar for both
samples.

**macOS / Linux:**

```bash
mvn package -DskipTests

# One-shot demo
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Continuous watcher
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

**Windows (PowerShell):**

```powershell
mvn package -DskipTests

# One-shot demo
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Continuous watcher
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

### Tuning the watcher poll interval

`ChangeFeedWatcherSample` polls each partition cursor on a fixed cadence. The
default is **1000 ms**; override via the `changefeed.poll.intervalMs` system
property (minimum 1 ms):

**macOS / Linux:**

```bash
java -Dchangefeed.poll.intervalMs=250 \
     -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

**Windows (PowerShell):**

```powershell
java "-Dchangefeed.poll.intervalMs=250" `
     "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

> Invalid values (non-numeric, ≤ 0) print a warning to stderr and fall back to
> the 1000 ms default — the watcher does not exit on bad input.

---

## Example Output

### `ChangeFeedSample` (one-shot)

```
=== Multicloud DB Change Feed Sample ===
Provider: Azure Cosmos DB
Mode    : LIVE

--- Provisioning 'multiclouddb-sdk-for-java-changefeed/change-feed-demo' ---

--- listCursors (live tip) ---
  Discovered 1 partition cursor(s)
  cursor-0: eyJ0eXBlIjoiQ29zbW9zIiwiY29udGlu…

--- readChanges (consuming events) ---
  [writer] upsert cf-1
  [writer] upsert cf-2
  [writer] upsert cf-3
  [writer] upsert cf-4
  [writer] upsert cf-5
  [writer] upsert cf-6
  [writer] update cf-1
  [writer] delete cf-1
  [consumer] cursor-0  CREATE MulticloudDbKey{partitionKey=cf-1, sortKey=cf-1} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  CREATE MulticloudDbKey{partitionKey=cf-2, sortKey=cf-2} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  CREATE MulticloudDbKey{partitionKey=cf-3, sortKey=cf-3} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  CREATE MulticloudDbKey{partitionKey=cf-4, sortKey=cf-4} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  CREATE MulticloudDbKey{partitionKey=cf-5, sortKey=cf-5} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  CREATE MulticloudDbKey{partitionKey=cf-6, sortKey=cf-6} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  UPDATE MulticloudDbKey{partitionKey=cf-1, sortKey=cf-1} @ 2026-06-12T19:40:55Z
  [consumer] cursor-0  DELETE MulticloudDbKey{partitionKey=cf-1, sortKey=cf-1} @ 2026-06-12T19:40:55Z

  Total events observed: 8

--- Cursor token round-trip ---
  Persisted token (truncated): {"continuation":"\"...\"","partitionKey":...
  Resumed cursor read 0 new events (expected — no further writes)

=== Sample complete ===
```

### `ChangeFeedWatcherSample` (continuous)

Start the watcher, then in another window / the Azure Portal Data Explorer
create a document, edit it, and delete it. Events appear within
`changefeed.poll.intervalMs` (default 1 second):

```
=== Multicloud DB Change Feed Watcher ===
Provider     : Azure Cosmos DB
Mode         : LIVE
Container    : multiclouddb-sdk-for-java-changefeed/change-feed-demo
Poll interval: 1000 ms

Discovered 1 partition cursor(s) at the live tip.
  cursor-0: eyJ0eXBlIjoiQ29zbW9zIiwiY29udGlu…

Watching multiclouddb-sdk-for-java-changefeed/change-feed-demo — go add/update/delete items (e.g., in the Azure Portal Data Explorer).
Press Ctrl+C to stop.

[2026-06-12T19:40:55Z] cursor-0  CREATE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {"id":"portal-1","title":"hello", ...}
[2026-06-12T19:40:57Z] cursor-0  UPDATE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {"id":"portal-1","title":"hello (edited)", ...}
[2026-06-12T19:40:58Z] cursor-0  DELETE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {}

^C
--- Stopping watcher ---
Total events observed: 3
```

---

## Extended Retention Escape Hatch

The portable change-feed baseline is **24 hours**: the SDK treats every cursor
token older than that as expired, regardless of what the underlying provider
stores server-side. Many real workloads want longer history — disaster
recovery, late-arriving subscribers, weekend backfills. The SDK exposes an
opt-in for this via `ChangeFeedConfig.extendedRetention(Duration)`, wired into
the client via `MulticloudDbClientConfig.Builder#changeFeed(...)`.

Opting in is a **portable contract, not a guarantee**: providers that cannot
extend retention beyond 24h refuse to be instantiated at all. The refusal is
synchronous and happens in `MulticloudDbClientFactory.create(...)` —
**before any network I/O** — surfaced as
`MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY` with
`providerDetails.reason = "extended_retention_unavailable"`. Misconfiguration
cannot lurk until the first `listCursors` / `readChanges` call.

`ChangeFeedExtendedRetentionSample` demonstrates this build-time gate
end-to-end on all three providers.

### Per-provider behaviour

| Provider | `Capability.EXTENDED_CHANGE_FEED_HISTORY` | What this sample prints |
|----------|---------------------------------------------|--------------------------|
| **Azure Cosmos DB** | Supported | `Client built successfully — capability gate passed.` Notes: *"Up to 30 days via Continuous Backup 30d tier; 7d minimum (AVAD requires Continuous Backup)."* |
| **Google Cloud Spanner** | Supported | `Client built successfully — capability gate passed.` Notes: *"Default 24h; configurable up to 7d natively via `CREATE CHANGE STREAM ... OPTIONS(retention_period=...)`."* |
| **AWS DynamoDB** | **Not supported** | Sample exits with code `1` and prints `Build-time gate REFUSED extended retention`. DynamoDB Streams is fixed at 24h server-side; an SDK-managed archive-on-read mechanism is on the v1.x roadmap. |

> **Scope of this sample.** The sample stops at the build-time gate on purpose.
> Actually reading change events beyond the 24-hour baseline requires
> provider-specific substrate setup that is out of scope for a portable demo
> — see the [`Why not extendedRetention against live Cosmos?`](#provisioning-model--why-continuous-backup-matters)
> note above for the Cosmos caveat and the
> [SDK guide](https://learn.microsoft.com/) for Spanner change-stream DDL.
> The plain `ChangeFeedSample` and `ChangeFeedWatcherSample` cover the
> data-plane round-trip at the portable 24-hour baseline.

### Running `ChangeFeedExtendedRetentionSample`

After building the fat jar (`mvn clean package -DskipTests`):

> **Live vs. emulator/local — they behave the same.** The build-time gate is
> capability-based and runs before any wire I/O, so a Spanner emulator config
> succeeds the same way as a live Spanner config, and a DynamoDB Local config
> fails fast the same way as a live AWS config. The cloud variants below
> need credentials and a real `.properties` file; the emulator/local variants
> use the configs that ship with the repo and work out of the box.

**macOS / Linux:**

```bash
# --- Cosmos ----------------------------------------------------------------
# Cosmos cloud — should succeed.
# Requires a LIVE Continuous-Backup Cosmos account (the emulator does not
# support CB; the sample detects localhost endpoints and bails out early).
# Copy change-feed-cosmos-cloud.properties.template to *.properties and fill
# in endpoint + key first.
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# --- Spanner ---------------------------------------------------------------
# Spanner emulator — should succeed (uses shipped change-feed-spanner.properties).
# Start the emulator first:
#   docker run --rm -p 9010:9010 -p 9020:9020 gcr.io/cloud-spanner-emulator/emulator
java -Dmulticlouddb.config=change-feed-spanner.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# Spanner cloud — should succeed.
# Copy change-feed-spanner-cloud.properties.template to *.properties and fill
# in project + instance + database first.
java -Dmulticlouddb.config=change-feed-spanner-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# --- DynamoDB --------------------------------------------------------------
# DynamoDB Local — should fail fast (expected exit code: 1).
# The build-time gate refuses before any wire I/O, so this works even if you
# don't start DynamoDB Local. Uses shipped change-feed-dynamo.properties.
java -Dmulticlouddb.config=change-feed-dynamo.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# DynamoDB cloud — should fail fast (expected exit code: 1).
# Copy change-feed-dynamo-cloud.properties.template to *.properties and fill
# in region first.
java -Dmulticlouddb.config=change-feed-dynamo-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
```

**Windows (PowerShell):**

> PowerShell mangles unquoted `-D...=...` system-property arguments and does
> not recognise bash-style `\` line continuation. Quote each `-D` arg and use
> the backtick (`` ` ``) for continuation, as shown below.

```powershell
# --- Cosmos ----------------------------------------------------------------
# Cosmos cloud — should succeed.
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# --- Spanner ---------------------------------------------------------------
# Spanner emulator — should succeed.
java "-Dmulticlouddb.config=change-feed-spanner.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# Spanner cloud — should succeed.
java "-Dmulticlouddb.config=change-feed-spanner-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# --- DynamoDB --------------------------------------------------------------
# DynamoDB Local — should fail fast (expected exit code: 1).
# No Docker / no DynamoDB Local container needed — the build-time gate
# refuses extended retention before any wire I/O. Uses shipped
# change-feed-dynamo.properties.
java "-Dmulticlouddb.config=change-feed-dynamo.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample

# DynamoDB cloud — should fail fast (expected exit code: 1).
java "-Dmulticlouddb.config=change-feed-dynamo-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedExtendedRetentionSample
```

<a id="example-output-changefeedextendedretentionsample"></a>

### Example output

**Cosmos (success):**

```
=== Multicloud DB Change Feed — Extended Retention Sample ===
Provider          : Azure Cosmos DB
Requested window  : PT168H (baseline is 24h)

--- Building client with extendedRetention(PT168H) ---
  Client built successfully — capability gate passed.

--- Capability detail ---
  Name      : extended_change_feed_history
  Supported : true
  Notes     : Up to 30 days via Continuous Backup 30d tier; 7d minimum (AVAD requires Continuous Backup)

Extended retention is honoured by the SDK on this provider. Cursor tokens
issued under this client carry the opt-in stamp and can be resumed beyond
24h, up to the requested window.

Data-plane round-trip (listCursors / readChanges) requires provider-specific
substrate setup and is out of scope for this sample — see ChangeFeedSample /
ChangeFeedWatcherSample for the 24h-baseline data-plane demo.

=== Sample complete ===
```

**DynamoDB (refused, exit code 1):**

```
=== Multicloud DB Change Feed — Extended Retention Sample ===
Provider          : AWS DynamoDB
Requested window  : PT168H (baseline is 24h)

--- Building client with extendedRetention(PT168H) ---

--- Build-time gate REFUSED extended retention ---
  Provider : AWS DynamoDB
  Category : UNSUPPORTED_CAPABILITY
  Message  : Provider dynamo does not support Capability.EXTENDED_CHANGE_FEED_HISTORY — extended change-feed retention (requested PT168H) is unavailable on this provider. ...
  Details  : {reason=extended_retention_unavailable, capability=extended_change_feed_history, requestedRetention=PT168H}

This is the expected outcome on providers that do not declare
Capability.EXTENDED_CHANGE_FEED_HISTORY (e.g. AWS DynamoDB, whose Streams
retention is fixed at 24h server-side).
```

---

## Configuration Reference

Both data-plane samples (`ChangeFeedSample`, `ChangeFeedWatcherSample`) read
the same set of keys from the properties file pointed to by
`-Dmulticlouddb.config` (defaults to `change-feed-cosmos.properties`):

| Key | Required? | Default | Notes |
|-----|-----------|---------|-------|
| `multiclouddb.provider` | yes | — | Must be `cosmos`. |
| `multiclouddb.connection.endpoint` | yes | — | Cosmos account endpoint (e.g., `https://localhost:8081` or `https://<account>.documents.azure.com:443/`). |
| `multiclouddb.connection.key` | yes | — | Cosmos primary master key. |
| `multiclouddb.connection.connectionMode` | no | `direct` | Set to `gateway` for the emulator; `direct` works for live accounts. |
| `multiclouddb.database` | no | `multiclouddb-sdk-for-java-changefeed` | Logical database name. |
| `multiclouddb.collection` | no | `change-feed-demo` | Container name. |

`ChangeFeedExtendedRetentionSample` accepts `multiclouddb.provider=cosmos`,
`spanner`, or `dynamo`. The Spanner config additionally requires
`multiclouddb.connection.projectId`, `instanceId`, and `databaseId`; the
DynamoDB config requires `multiclouddb.connection.region`. See each provider's
`change-feed-<provider>-cloud.properties.template` for the full key list.

System properties (passed via `-D` on the `java` command line):

| Property | Sample | Default | Notes |
|----------|--------|---------|-------|
| `multiclouddb.config` | all | `change-feed-cosmos.properties` (data-plane samples) / `change-feed-cosmos-cloud.properties` (`ChangeFeedExtendedRetentionSample`) | Classpath name of the properties file. |
| `changefeed.poll.intervalMs` | `ChangeFeedWatcherSample` | `1000` | Polling cadence in milliseconds (minimum `1`). |

Shipped properties files:

| File | Provider | Tracked in git? |
|------|----------|------------------|
| `src/main/resources/change-feed-cosmos.properties` | Cosmos emulator | yes |
| `src/main/resources/change-feed-cosmos-cloud.properties.template` | Cosmos cloud (template — copy and fill in) | yes |
| `src/main/resources/change-feed-cosmos-cloud.properties` | Cosmos cloud (your real endpoint + key) | **no** (gitignored — see [`.gitignore`](.gitignore)) |
| `src/main/resources/change-feed-spanner.properties` | Spanner emulator (build-time gate demo) | yes |
| `src/main/resources/change-feed-spanner-cloud.properties.template` | Spanner cloud (template — copy and fill in) | yes |
| `src/main/resources/change-feed-spanner-cloud.properties` | Spanner cloud (your real project + instance + database) | **no** (gitignored) |
| `src/main/resources/change-feed-dynamo.properties` | DynamoDB Local (build-time gate demo) | yes |
| `src/main/resources/change-feed-dynamo-cloud.properties.template` | DynamoDB cloud (template — copy and fill in) | yes |
| `src/main/resources/change-feed-dynamo-cloud.properties` | DynamoDB cloud (your real region) | **no** (gitignored) |

---

## Cloud Setup

> Run these steps **in order** in the same terminal. Variables set in one step
> carry forward to the next — do not close the terminal between steps.

### Step 1 — Create a Continuous-Backup Cosmos account

The change-feed samples require Continuous Backup to be enabled on the target
Cosmos account so the AVAD change feed is available without additional
per-container configuration.

**macOS / Linux:**

```bash
# Pick names
COSMOS_ACCOUNT=<your-account-name>
COSMOS_RG=<your-resource-group>
COSMOS_LOCATION=eastus

# Create a CB-enabled account (tier doesn't matter; standard suffices)
az cosmosdb create \
  --name "$COSMOS_ACCOUNT" \
  --resource-group "$COSMOS_RG" \
  --locations regionName="$COSMOS_LOCATION" \
  --backup-policy-type Continuous \
  --backup-tier Continuous7Days
```

**Windows (PowerShell):**

```powershell
$COSMOS_ACCOUNT = '<your-account-name>'
$COSMOS_RG      = '<your-resource-group>'
$COSMOS_LOCATION = 'eastus'

az cosmosdb create `
  --name $COSMOS_ACCOUNT `
  --resource-group $COSMOS_RG `
  --locations "regionName=$COSMOS_LOCATION" `
  --backup-policy-type Continuous `
  --backup-tier Continuous7Days
```

Verify CB is enabled:

```bash
az cosmosdb show --name "$COSMOS_ACCOUNT" -g "$COSMOS_RG" \
  --query backupPolicy.type -o tsv
# Expected output: Continuous
```

> **Already have a Cosmos account on Periodic backup?** You can switch an
> existing account to Continuous Backup with
> `az cosmosdb update --backup-policy-type Continuous`. Note that the switch
> is **one-way** — you cannot revert to Periodic.

> **Don't need to create the database / container ahead of time** — the
> samples call `ensureDatabase()` / `ensureContainer()` on startup. On a
> CB-enabled account a plain container is enough; AVAD is automatic.

### Step 2 — Create the properties file

The cloud properties file is **git-ignored** and must never be committed.

**macOS / Linux:**

```bash
COSMOS_ENDPOINT=$(az cosmosdb show \
  --name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --query documentEndpoint -o tsv)

COSMOS_KEY=$(az cosmosdb keys list \
  --name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --query primaryMasterKey -o tsv)

cat > src/main/resources/change-feed-cosmos-cloud.properties << EOF
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=$COSMOS_ENDPOINT
multiclouddb.connection.key=$COSMOS_KEY
multiclouddb.connection.connectionMode=gateway
multiclouddb.database=multiclouddb-sdk-for-java-changefeed
multiclouddb.collection=change-feed-demo
EOF
```

**Windows (PowerShell):**

```powershell
$COSMOS_ENDPOINT = (az cosmosdb show `
  --name $COSMOS_ACCOUNT --resource-group $COSMOS_RG `
  --query documentEndpoint -o tsv)

$COSMOS_KEY = (az cosmosdb keys list `
  --name $COSMOS_ACCOUNT --resource-group $COSMOS_RG `
  --query primaryMasterKey -o tsv)

@"
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=$COSMOS_ENDPOINT
multiclouddb.connection.key=$COSMOS_KEY
multiclouddb.connection.connectionMode=gateway
multiclouddb.database=multiclouddb-sdk-for-java-changefeed
multiclouddb.collection=change-feed-demo
"@ | Set-Content src\main\resources\change-feed-cosmos-cloud.properties
```

Verify:

```bash
cat src/main/resources/change-feed-cosmos-cloud.properties
```

> **Don't have the Azure CLI?** Get endpoint and key from the
> [Azure Portal](https://portal.azure.com) → your Cosmos DB account → **Keys**,
> then create the file manually by copying
> `src/main/resources/change-feed-cosmos-cloud.properties.template` and filling
> in the placeholders.

### Step 3 — Build and run

`ConfigLoader` reads configs from the **fat-jar classpath**, so the runtime
file must live under `src/main/resources/` **before** you run `mvn package`.

**macOS / Linux:**

```bash
mvn package -DskipTests

# One-shot demo
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Continuous watcher
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

**Windows (PowerShell):**

```powershell
mvn package -DskipTests

# One-shot demo
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedSample

# Continuous watcher
java "-Dmulticlouddb.config=change-feed-cosmos-cloud.properties" `
     -cp target\multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar `
     com.multiclouddb.samples.changefeed.ChangeFeedWatcherSample
```

### Step 4 — Clean up Cosmos DB resources (optional)

Drop the database (and its single container) when you're done:

```bash
az cosmosdb sql database delete \
  --account-name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --name multiclouddb-sdk-for-java-changefeed --yes
```

Or delete the entire Cosmos account:

```bash
az cosmosdb delete \
  --name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" --yes
```

---

## Troubleshooting

### `BadRequest: The retention duration in the Change Feed policy should not be set when continuous backup mode is enabled`

You hit the CB+AVAD interaction described in
[Provisioning Model](#provisioning-model--why-continuous-backup-matters). The
samples already avoid this on live accounts; if you see it, you likely modified
the sample to call `ChangeFeedConfig.extendedRetention(Duration)` against a
CB-enabled account. Remove the `extendedRetention` opt-in — CB makes AVAD
automatic.

### `UNSUPPORTED_CAPABILITY(continuous_backup_required)` against an account that already has CB enabled

Same root cause as above. The SDK currently mis-maps the underlying 400 from
Cosmos to this error code. Verify CB is on with the
`az cosmosdb show --query backupPolicy.type` command, then remove any explicit
`extendedRetention(...)` call from the sample's `ChangeFeedConfig`.

### `BadRequest: Retention duration is greater than the maximum allowed value` on the emulator

The Cosmos emulator caps AVAD retention at **10 minutes**. The sample already
uses `Duration.ofMinutes(10)` for the emulator path; if you forked the code,
keep that ceiling.

### Watcher prints `Discovered 0 partition cursor(s)`

The container doesn't exist yet, or you ran the watcher against a different
container than the one you're writing to. Confirm `multiclouddb.database` /
`multiclouddb.collection` in your config match what you're editing in Data
Explorer.

### No events appear after editing items in the portal

- Make sure you're editing the **correct** container
  (`multiclouddb-sdk-for-java-changefeed/change-feed-demo` by default).
- Wait at least `changefeed.poll.intervalMs` (default 1 s) after each edit.
- If you started the watcher long before the writes, partition splits or other
  service-side activity can momentarily move the live tip. Restart the watcher.

### `SSLHandshakeException` against the emulator

Import the emulator's self-signed certificate into your JDK truststore — see
the [Todo App README's SSL section](README-todo-app.md#5-ssl-certificate-trust)
for the full walk-through.

### `Could not find artifact com.microsoft.multiclouddb:multiclouddb-api:jar:<version>`

You likely have a stale `.mvn/maven.config` left over from local SDK testing.
Either delete `.mvn/maven.config` (defaults will resolve `0.1.0-beta.1` from
Maven Central) or update the override to a version actually installed in your
`~/.m2`. See [`../README.md#sdk-version`](README.md#sdk-version).
