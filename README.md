# Multicloud DB SDK — Sample Applications

Sample applications demonstrating the [Multicloud DB SDK for Java](https://github.com/microsoft/multiclouddb-sdk-for-java) provider-portable API.  
Each sample runs against **Azure Cosmos DB**, **Amazon DynamoDB**, or **Google Cloud Spanner** — switch providers by changing a single `.properties` file.

| Sample | Description | Port | Guide |
|--------|-------------|------|-------|
| **Portable CRUD + Query** | Minimal end-to-end CRUD and query sample | — | [below](#portable-crud--query-sample) |
| **Risk Analysis Platform** | Multi-tenant portfolio risk analytics with executive dashboard | `8090` | [README-risk-platform.md](README-risk-platform.md) |
| **TODO App** | Simple CRUD web app with browser UI | `8080` | [README-todo-app.md](README-todo-app.md) |

---

## Prerequisites

### 1 — Java 17+

```bash
java -version   # must be 17 or later
```

### 2 — Build the Multicloud DB SDK locally

The SDK artifacts (`multiclouddb-api`, `multiclouddb-provider-*`) are not yet published to Maven Central. You need to install them into your local `~/.m2` repository first.

```bash
# Clone the main SDK repo
git clone https://github.com/microsoft/multiclouddb-sdk-for-java.git
cd multiclouddb-sdk-for-java

# Install all modules into ~/.m2 (skipping tests for speed)
mvn clean install -DskipTests
```

Once that succeeds, the artifacts are available at:

```
~/.m2/repository/com/microsoft/multiclouddb/
  multiclouddb-api/0.1.0-beta.2/
  multiclouddb-provider-cosmos/0.1.0-beta.2/
  multiclouddb-provider-dynamo/0.1.0-beta.2/
  multiclouddb-provider-spanner/0.1.0-beta.2/
```

### 3 — Build this project

```bash
# In this repo root
mvn compile
```

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

This project depends on **Multicloud DB SDK `0.1.0-beta.2`**.  
To upgrade, change the `multiclouddb-*.version` properties in `pom.xml` and re-run `mvn clean install -DskipTests` in the main SDK repo.

---

## License

MIT — see [LICENSE](LICENSE).
