# Multicloud DB SDK — Sample Applications

Sample applications demonstrating the [Multicloud DB SDK for Java](https://github.com/microsoft/multiclouddb-sdk-for-java) provider-portable API.  
Each sample runs against **Azure Cosmos DB**, **Amazon DynamoDB**, or **Google Cloud Spanner** — switch providers by changing a single `.properties` file.

| Sample | Description | Port | Guide |
|--------|-------------|------|-------|
| **Portable CRUD + Query** | Minimal end-to-end CRUD and query sample | — | [below](#portable-crud--query-sample) |
| **Change Feed (one-shot)** | Writer + consumer demo showing CREATE / UPDATE / DELETE events | — | [below](#change-feed-samples) |
| **Change Feed Watcher** | Long-running consumer that prints events as you add/edit/delete items in the portal | — | [below](#change-feed-samples) |
| **Risk Analysis Platform** | Multi-tenant portfolio risk analytics with executive dashboard | `8090` | [README-risk-platform.md](README-risk-platform.md) |
| **TODO App** | Simple CRUD web app with browser UI | `8080` | [README-todo-app.md](README-todo-app.md) |

---

## Prerequisites

### 1 — Java 17+

```bash
java -version   # must be 17 or later
```

### 2 — Build this project

SDK dependencies are pulled from Maven Central by default — no extra setup needed.

```bash
# In this repo root
mvn compile
```

> **Developing the SDK itself?** See [SDK Version](#sdk-version) below for how to point the build at a locally-built SDK via a git-ignored override file.

---

## Portable CRUD + Query Sample

Runs the same CRUD and query code against any provider. Switch by pointing `multiclouddb.config` at a different `.properties` file.

### Against Cosmos DB (local emulator)

1. Start the [Azure Cosmos DB Emulator](https://learn.microsoft.com/azure/cosmos-db/local-emulator)
2. Run:

```bash
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.PortableCrudQuerySample
```

### Against DynamoDB (local)

1. Start [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html)
2. Run:

```bash
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.PortableCrudQuerySample \
    -Dmulticlouddb.config=todo-app-dynamo.properties
```

### Against Spanner (emulator)

1. Start the [Cloud Spanner Emulator](https://cloud.google.com/spanner/docs/emulator)
2. Run:

```bash
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.PortableCrudQuerySample \
    -Dmulticlouddb.config=todo-app-spanner.properties
```

---

## Running the Samples

> **Important:** Always run `mvn` from the **repo root** (where `pom.xml` lives). Do **not** use `-pl` — this is a standalone single-module project.

### Risk Analysis Platform

```bash
# Against Cosmos DB (local emulator)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-cosmos.properties

# Against Cosmos DB (cloud)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-cosmos-cloud.properties

# Against DynamoDB (local)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-dynamo.properties

# Against DynamoDB (cloud)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
    -Drisk.config=risk-platform-dynamo-cloud.properties
```

### TODO App

```bash
# Against Cosmos DB (local emulator)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.todo.TodoApp

# Against DynamoDB (local)
mvn exec:java -Dexec.mainClass=com.multiclouddb.samples.todo.TodoApp \
    -Dmulticlouddb.config=todo-app-dynamo.properties
```

### Change Feed Samples

Two samples demonstrate the SDK's pull-mode change feed against an Azure Cosmos
DB account. Both use the dedicated database
`multiclouddb-sdk-for-java-changefeed` and container `change-feed-demo` (see
`src/main/resources/change-feed-cosmos*.properties`).

**Provisioning notes:**

- **Live Cosmos** — the account must have **Continuous Backup** enabled. When CB
  is on, the All-Versions-and-Deletes (AVAD) change feed (the source of
  CREATE/UPDATE/DELETE events) is available automatically on every container, so
  the samples just call `ensureContainer` for a plain container. Verify CB with
  `az cosmosdb show --query backupPolicy.type -o tsv` (expect `Continuous`).
- **Cosmos emulator** — the emulator has no CB, so the samples pre-provision the
  container with an AVAD `ChangeFeedPolicy` and a 10-minute retention (the
  emulator's hard ceiling) on first run.

#### 1. One-shot change feed demo (`ChangeFeedSample`)

Runs a writer thread that produces a fixed CREATE / UPDATE / DELETE sequence,
drains the change feed, then exits. Useful for validating that change feed is
wired up end-to-end.

```bash
# Live Cosmos
mvn package -DskipTests
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.ChangeFeedSample

# Cosmos emulator (default)
java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.ChangeFeedSample
```

#### 2. Continuous watcher (`ChangeFeedWatcherSample`)

Long-running consumer with no built-in writes. Start it, then add, edit, or
delete items in the Azure Portal **Data Explorer** for the
`multiclouddb-sdk-for-java-changefeed/change-feed-demo` container — each
operation prints a `CREATE` / `UPDATE` / `DELETE` line on the console within
the poll interval (default 1 second). Press **Ctrl+C** to stop; the watcher
prints a final event tally.

```bash
# Live Cosmos
mvn package -DskipTests
java -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.ChangeFeedWatcherSample

# Cosmos emulator (default config)
java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.ChangeFeedWatcherSample

# Override the poll interval (milliseconds; default 1000)
java -Dchangefeed.poll.intervalMs=500 \
     -Dmulticlouddb.config=change-feed-cosmos-cloud.properties \
     -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.ChangeFeedWatcherSample
```

Example output (after creating, then deleting an item in the portal):

```
=== Multicloud DB Change Feed Watcher ===
Provider     : Azure Cosmos DB
Mode         : LIVE
Container    : multiclouddb-sdk-for-java-changefeed/change-feed-demo
Poll interval: 1000 ms

Discovered 1 partition cursor(s) at the live tip.

Watching multiclouddb-sdk-for-java-changefeed/change-feed-demo — go add/update/delete items (e.g., in the Azure Portal Data Explorer).
Press Ctrl+C to stop.

[2026-06-12T19:40:55Z] CREATE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {"title":"hello","id":"portal-1", ...}
[2026-06-12T19:40:58Z] DELETE MulticloudDbKey{partitionKey=portal-1, sortKey=portal-1}  {}
```


---

## Configuration Files

| File | Provider |
|------|----------|
| `todo-app-cosmos.properties` | Azure Cosmos DB (local emulator) |
| `todo-app-dynamo.properties` | Amazon DynamoDB Local |
| `todo-app-spanner.properties` | Google Cloud Spanner emulator |
| `risk-platform-cosmos.properties` | Cosmos DB (local) |
| `risk-platform-dynamo.properties` | DynamoDB Local |
| `risk-platform-cosmos-cloud.properties.template` | Cosmos DB (cloud) |
| `risk-platform-dynamo-cloud.properties.template` | DynamoDB (cloud) |
| `change-feed-cosmos.properties` | Cosmos DB emulator (used by both change-feed samples) |
| `change-feed-cosmos-cloud.properties` | Cosmos DB (cloud) — fill in endpoint + key |

---

## Build an Executable JAR

```bash
mvn package

java -cp target/multiclouddb-samples-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     com.multiclouddb.samples.PortableCrudQuerySample
```

---

## Project Structure

```
multiclouddb-samples/
├── pom.xml                             # Standalone Maven build (resolves SDK from ~/.m2)
├── README.md                           # This file
├── README-risk-platform.md             # Risk Analysis Platform guide
├── README-todo-app.md                  # TODO App guide
├── scripts/
│   ├── cleanup-cosmos.sh / .ps1        # Delete Cosmos DB test resources
│   └── cleanup-dynamo.sh / .ps1        # Delete DynamoDB test resources
└── src/main/
    ├── java/com/multiclouddb/samples/
    │   ├── ConfigLoader.java           # Reads .properties config files
    │   ├── PortableCrudQuerySample.java # Minimal CRUD + query demo
    │   ├── riskplatform/               # Risk Analysis Platform sample
    │   └── todo/                       # TODO App sample
    └── resources/
        ├── *.properties                # Provider config files
        └── static/                     # Web UI assets
```

---

## SDK Version

This project depends on **Multicloud DB SDK `0.1.0-beta.1`** ([Maven Central](https://search.maven.org/artifact/com.microsoft.multiclouddb/multiclouddb-api)).

### Upgrading

To pick up a newer released version, bump the `multiclouddb-*.version` properties in `pom.xml`.

### Testing against a locally-built SDK

When developing the SDK itself, you can point this project at a locally-installed build without modifying `pom.xml`:

```bash
# 1. Build & install the SDK locally
cd <multiclouddb-sdk-for-java>
mvn clean install -DskipTests

# 2. Activate the override in this repo
cp .mvn/maven.config.example .mvn/maven.config

# 3. Edit .mvn/maven.config to match the version you just installed
#    (defaults to 0.2.0-SNAPSHOT in the template)

# 4. Build as usual — Maven picks up .mvn/maven.config automatically
mvn compile
```

`.mvn/maven.config` is git-ignored, so per-developer overrides never leak into commits.

---

## License

MIT — see [LICENSE](LICENSE).
