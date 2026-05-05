# Multicloud DB SDK — TODO Sample Application

A simple CRUD **web application** that demonstrates the Multicloud DB SDK's
provider-portable API. The same Java code runs against **Azure Cosmos DB**,
**Amazon DynamoDB**, or **Google Cloud Spanner** — switch providers by changing
a single properties file.

The app starts an embedded HTTP server on `http://localhost:8080` with a
browser-based UI for creating, reading, updating, and deleting TODO items.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Emulator Setup](#emulator-setup)
   - [Azure Cosmos DB Emulator](#azure-cosmos-db-emulator)
   - [DynamoDB Local](#dynamodb-local)
   - [DynamoDB Admin (GUI)](#dynamodb-admin-gui)
   - [Google Cloud Spanner Emulator](#google-cloud-spanner-emulator)
3. [Running the Sample](#running-the-sample)
   - [Against Cosmos DB Emulator](#run-against-cosmos-db-emulator)
   - [Against DynamoDB Local](#run-against-dynamodb-local)
   - [Against Spanner Emulator](#run-against-spanner-emulator)
   - [Against Cosmos DB (Azure Cloud)](#run-against-cosmos-db-azure-cloud)
   - [Against DynamoDB (AWS Cloud)](#run-against-dynamodb-aws-cloud)
   - [Against Spanner (Google Cloud)](#run-against-spanner-google-cloud)
4. [Web UI Features](#web-ui-features)
5. [Cloud Setup](#cloud-setup)
   - [Cosmos DB Cloud Setup](#cosmos-db-cloud-setup)
   - [DynamoDB Cloud Setup](#dynamodb-cloud-setup)
   - [Spanner Cloud Setup](#spanner-cloud-setup)
6. [Cleanup](#cleanup)
   - [Clean up Cosmos DB resources](#clean-up-cosmos-db-resources)
   - [Clean up DynamoDB resources](#clean-up-dynamodb-resources)
   - [Clean up Spanner resources](#clean-up-spanner-resources)

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 17 LTS  | Required — e.g. [Eclipse Adoptium](https://adoptium.net/) |
| Maven | 3.9+   | Build tool |
| Docker | 20+   | Required for Spanner Emulator |
| Node.js + npm | 18+ | Only if you want `dynamodb-admin` GUI |

Make sure `JAVA_HOME` points to JDK 17 and is on your `PATH`:

```powershell
# PowerShell example
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # should say 17.x
```

---

## Emulator Setup

### Azure Cosmos DB Emulator

The Cosmos DB emulator provides a free local instance of Azure Cosmos DB for
development and testing.

#### 1. Install

Download and install from:\
<https://learn.microsoft.com/en-us/azure/cosmos-db/emulator#install-the-emulator>

> **Windows**: Run the MSI installer. The emulator is added to Start Menu.\
> **Docker** (Linux/macOS):
> ```bash
> docker pull mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
> docker run -p 8081:8081 -p 10250-10255:10250-10255 \
>   mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
> ```

#### 2. Start the emulator

On Windows, launch **Azure Cosmos DB Emulator** from the Start Menu (or
system tray). It starts on **https://localhost:8081** by default.

Open the Data Explorer in your browser:\
<https://localhost:8081/_explorer/index.html>

#### 3. Create the database and container

In Data Explorer → **New Container**:

| Field | Value |
|-------|-------|
| Database id | `todoapp` |
| Container id | `todos` |
| Partition key | `/id` |

Or via the Cosmos DB SDK / CLI:

```powershell
# Using Azure CLI (if installed)
az cosmosdb create --name todoapp --resource-group local --kind GlobalDocumentDB
```

#### 4. Emulator connection details (already in `todo-app.properties`)

| Property | Value |
|----------|-------|
| Endpoint | `https://localhost:8081` |
| Key      | `C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==` |
| Connection mode | `gateway` (required for emulator) |

#### 5. SSL certificate trust

The emulator uses a self-signed TLS certificate. If you see SSL errors, import
the certificate into a local truststore:

```powershell
# 1. Export the certificate (PowerShell)
$cert = (New-Object System.Net.Sockets.TcpClient('localhost',8081)).GetStream() |
        ForEach-Object {
            $ssl = New-Object System.Net.Security.SslStream($_, $false, {$true})
            $ssl.AuthenticateAsClient('localhost')
            $ssl.RemoteCertificate
        }
[System.IO.File]::WriteAllBytes('cosmos-emulator.cer', $cert.Export('Cert'))

# 2. Import into a local keystore
keytool -importcert -alias cosmosemulator -file cosmos-emulator.cer `
        -keystore .tools/cacerts-local -storepass changeit -noprompt

# 3. Tell Maven/Surefire to use it (already configured in conformance POM)
#    -Djavax.net.ssl.trustStore=.../.tools/cacerts-local
#    -Djavax.net.ssl.trustStorePassword=changeit
```

---

### DynamoDB Local

DynamoDB Local is a small Java application provided by AWS that emulates the
DynamoDB service on your machine.

#### 1. Download

```powershell
# Create a tools directory
New-Item -ItemType Directory -Force -Path .tools/dynamodb-local | Out-Null

# Download the latest release
Invoke-WebRequest `
  -Uri 'https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip' `
  -OutFile '.tools/dynamodb-local/dynamodb_local.zip'

# Extract
Expand-Archive -Path '.tools/dynamodb-local/dynamodb_local.zip' `
               -DestinationPath '.tools/dynamodb-local' -Force

# Clean up the zip
Remove-Item '.tools/dynamodb-local/dynamodb_local.zip'
```

> **macOS / Linux alternative** (using curl):
> ```bash
> mkdir -p .tools/dynamodb-local
> curl -L https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip \
>   -o .tools/dynamodb-local/dynamodb_local.zip
> cd .tools/dynamodb-local && unzip dynamodb_local.zip && rm dynamodb_local.zip
> ```

#### 2. Start DynamoDB Local

```powershell
# Make sure JAVA_HOME points to JDK 17
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

cd .tools/dynamodb-local
java "-Djava.library.path=./DynamoDBLocal_lib" -jar DynamoDBLocal.jar -sharedDb -port 8000
```

DynamoDB Local starts on **http://localhost:8000**. Keep this terminal open.

| Flag | Purpose |
|------|---------|
| `-sharedDb` | All clients share a single database file |
| `-port 8000` | Listening port (default 8000) |
| `-inMemory` | Optional — data is lost on restart |

#### 3. Verify it's running

```powershell
# A 400 Bad Request is expected (DynamoDB only speaks its own wire protocol)
Invoke-WebRequest -Uri 'http://localhost:8000' -UseBasicParsing
```

#### 4. Connection details (already in `todo-app-dynamo.properties`)

| Property | Value |
|----------|-------|
| Endpoint | `http://localhost:8000` |
| Region   | `us-east-1` (any value works locally) |
| Access Key ID | `fakeMyKeyId` (any non-empty string) |
| Secret Access Key | `fakeSecretAccessKey` (any non-empty string) |

> The sample app's `DynamoProviderClient` automatically creates the `todos`
> table when it runs the conformance tests. For the interactive sample, the
> table is also created on first write.

---

### DynamoDB Admin (GUI)

[dynamodb-admin](https://github.com/aaronshaf/dynamodb-admin) is a lightweight
Node.js web UI for browsing DynamoDB Local tables and data.

#### 1. Install

```bash
npm install -g dynamodb-admin
```

#### 2. Start

```powershell
# Point it at DynamoDB Local
$env:DYNAMO_ENDPOINT = 'http://localhost:8000'
dynamodb-admin
```

#### 3. Open the GUI

Navigate to **http://localhost:8001** in your browser.

From there you can:
- Browse all tables and their items
- Create / edit / delete individual items
- Run scans and queries
- View table schemas and indexes

> **Tip**: Keep DynamoDB Local running in one terminal and `dynamodb-admin`
> in another. Any data written by the sample app will be visible in the GUI
> immediately.

---

### Google Cloud Spanner Emulator

The Spanner emulator is a Docker container that provides a local Spanner
environment for development and testing.

#### 1. Start the emulator

```powershell
docker run -d --name spanner-emulator -p 9010:9010 -p 9020:9020 `
  gcr.io/cloud-spanner-emulator/emulator
```

| Port | Protocol | Purpose |
|------|----------|----------|
| 9010 | gRPC | Client library connections |
| 9020 | REST | Admin API (instance/database creation) |

#### 2. Create instance and database

Use the REST Admin API to bootstrap the emulator:

```powershell
# Create a Spanner instance
Invoke-RestMethod -Method POST `
  -Uri 'http://localhost:9020/v1/projects/test-project/instances' `
  -ContentType 'application/json' `
  -Body '{"instanceId":"test-instance","instance":{"config":"emulator-config","displayName":"Test","nodeCount":1}}'

# Create the todoapp database with a 'todos' table
Invoke-RestMethod -Method POST `
  -Uri 'http://localhost:9020/v1/projects/test-project/instances/test-instance/databases' `
  -ContentType 'application/json' `
  -Body '{"createStatement":"CREATE DATABASE todoapp","extraStatements":["CREATE TABLE todos (id STRING(MAX) NOT NULL, sortKey STRING(MAX) NOT NULL, title STRING(MAX), completed BOOL, partitionKey STRING(MAX)) PRIMARY KEY (id, sortKey)"]}'
```

#### 3. Connection details (already in `todo-app-spanner.properties`)

| Property | Value |
|----------|-------|
| Project ID | `test-project` |
| Instance ID | `test-instance` |
| Database ID | `todoapp` |
| Emulator Host | `localhost:9010` |

---

## Running the Sample

First, build the entire SDK from the repo root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
mvn clean install -DskipTests
```

### Run against Cosmos DB Emulator

Make sure the Cosmos DB emulator is running and the `todoapp` / `todos`
database/container exists.

**Step 1 — Compile:**

```bash
mvn compile
```

**Step 2 — Run:**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-cosmos.properties -Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local -Djavax.net.ssl.trustStorePassword=changeit com.multiclouddb.samples.todo.TodoApp"
```

Then open **http://localhost:8080** in your browser.

> **Note**: The `-Djavax.net.ssl.trustStore` flags are needed because the Cosmos
> emulator uses a self-signed certificate (see the SSL section above).

### Run against DynamoDB Local

Make sure DynamoDB Local is running on port 8000.

**Step 1 — Compile:**

```bash
mvn compile
```

**Step 2 — Run:**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-dynamo.properties com.multiclouddb.samples.todo.TodoApp"
```

Then open **http://localhost:8080** in your browser.

> **Tip**: Open **http://localhost:8001** (dynamodb-admin) side-by-side to see
> the raw data as you interact with the web UI.

### Run against Spanner Emulator

Make sure the Spanner emulator is running and the instance/database exists
(see [Spanner Emulator Setup](#google-cloud-spanner-emulator) below).

**Step 1 — Compile:**

```bash
mvn compile
```

**Step 2 — Run:**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-spanner.properties com.multiclouddb.samples.todo.TodoApp"
```

Then open **http://localhost:8080** in your browser.

---

### Run against Cosmos DB (Azure Cloud)

> **First time?** Complete [Cosmos DB Cloud Setup](#cosmos-db-cloud-setup) first to
> create your properties file and provision the required Cosmos DB resources.

**Step 1 — Compile:**

```bash
mvn compile
```

**Step 2 — Run:**

**macOS / Linux:**

```bash
mvn exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-cp %classpath -Dtodo.config=todo-app-cosmos-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

**Windows (PowerShell):**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-cosmos-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

Expected startup output:

```
  Loaded config: todo-app-cosmos-cloud.properties
  ...
  Provider:  Azure Cosmos DB
  UI:        http://localhost:8080
```

Then open **http://localhost:8080** in your browser.


---

### Run against DynamoDB (AWS Cloud)

> **First time?** Complete [DynamoDB Cloud Setup](#dynamodb-cloud-setup) first to
> create your properties file and provision the required DynamoDB table.

**Step 1 — Compile:**

```bash
mvn compile
```

**Step 2 — Run:**

**macOS / Linux:**

```bash
mvn exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-cp %classpath -Dtodo.config=todo-app-dynamo-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

**Windows (PowerShell):**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-dynamo-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

Expected startup output:

```
  Loaded config: todo-app-dynamo-cloud.properties
  ...
  Provider:  Amazon DynamoDB
  UI:        http://localhost:8080
```

Then open **http://localhost:8080** in your browser.


---

### Run against Spanner (Google Cloud)

> **First time?** Complete [Spanner Cloud Setup](#spanner-cloud-setup) first to
> create your properties file and provision the required Spanner resources.

**Step 1 — Compile:**

```bash
mvn compile
```

**Step 2 — Run:**

**macOS / Linux:**

```bash
mvn exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-cp %classpath -Dtodo.config=todo-app-spanner-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

**Windows (PowerShell):**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-spanner-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

Expected startup output:

```
  Loaded config: todo-app-spanner-cloud.properties
  ...
  Provider:  Google Cloud Spanner
  UI:        http://localhost:8080
```

Then open **http://localhost:8080** in your browser.


---

### Changing the port

By default the server listens on port 8080. Override with:

```powershell
-Dtodo.port=9090
```

---

## Web UI Features

The browser-based UI at `http://localhost:8080` allows you to:

| Action | How |
|--------|-----|
| **Create** a TODO | Enter an optional ID and a title, then click **Add** |
| **List** all TODOs | Displayed automatically on page load |
| **Toggle complete** | Click the circle checkbox next to any TODO |
| **Delete** a TODO | Hover over a TODO and click the trash icon |
| **View capabilities** | Click **Provider Capabilities** to see what the current provider supports |

### REST API

The web app also exposes a JSON REST API:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/todos` | List all TODOs |
| `GET`  | `/api/todos/{id}` | Get a single TODO |
| `POST` | `/api/todos` | Create a TODO (`{ "id": "...", "title": "..." }`) |
| `PUT`  | `/api/todos/{id}` | Update fields (`{ "completed": true }`) |
| `DELETE` | `/api/todos/{id}` | Delete a TODO |
| `GET`  | `/api/capabilities` | Provider name and supported capabilities |

---

## Cloud Setup

> Run the following steps **in order** in the same terminal. Variables set in one
> step carry forward to the next — do not close the terminal between steps.

---

### Cosmos DB Cloud Setup

#### Step 1 — Create the properties file

The cloud properties file is **git-ignored** and must never be committed.

---

**macOS / Linux**

**Step 1 — List your Cosmos DB accounts:**

```bash
az resource list --resource-type Microsoft.DocumentDB/databaseAccounts \
  --query "[].{Name:name, ResourceGroup:resourceGroup}" -o table
```

**Step 2 — Enter your account name:**

```bash
printf "Cosmos DB account name: "; read COSMOS_ACCOUNT
```

**Step 3 — Fetch the resource group, endpoint, and primary key:**

```bash
COSMOS_RG=$(az resource list \
  --resource-type Microsoft.DocumentDB/databaseAccounts \
  --query "[?name=='$COSMOS_ACCOUNT'].resourceGroup" -o tsv)
```

```bash
COSMOS_ENDPOINT=$(az cosmosdb show \
  --name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --query documentEndpoint -o tsv)
```

```bash
COSMOS_KEY=$(az cosmosdb keys list \
  --name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --query primaryMasterKey -o tsv)
```

**Step 4 — Write the properties file:**

```bash
cat > src/main/resources/todo-app-cosmos-cloud.properties << EOF
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=$COSMOS_ENDPOINT
multiclouddb.connection.key=$COSMOS_KEY
multiclouddb.connection.connectionMode=gateway
EOF
```

**Step 5 — Verify:**

```bash
cat src/main/resources/todo-app-cosmos-cloud.properties
```

---

**Windows (PowerShell)**

**Step 1 — List your Cosmos DB accounts:**

```powershell
az resource list --resource-type Microsoft.DocumentDB/databaseAccounts `
  --query "[].{Name:name, ResourceGroup:resourceGroup}" -o table
```

**Step 2 — Enter your account name:**

```powershell
$COSMOS_ACCOUNT = Read-Host "Cosmos DB account name"
```

**Step 3 — Fetch the resource group:**

```powershell
$COSMOS_RG = (az resource list `
  --resource-type Microsoft.DocumentDB/databaseAccounts `
  --query "[?name=='$COSMOS_ACCOUNT'].resourceGroup" -o tsv)
```

**Step 4 — Fetch the endpoint:**

```powershell
$COSMOS_ENDPOINT = (az cosmosdb show `
  --name $COSMOS_ACCOUNT --resource-group $COSMOS_RG `
  --query documentEndpoint -o tsv)
```

**Step 5 — Fetch the primary key:**

```powershell
$COSMOS_KEY = (az cosmosdb keys list `
  --name $COSMOS_ACCOUNT --resource-group $COSMOS_RG `
  --query primaryMasterKey -o tsv)
```

**Step 6 — Write the properties file:**

```powershell
@"
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=$COSMOS_ENDPOINT
multiclouddb.connection.key=$COSMOS_KEY
multiclouddb.connection.connectionMode=gateway
"@ | Set-Content src\main\resources\todo-app-cosmos-cloud.properties
```

**Step 7 — Verify:**

```powershell
Get-Content src\main\resources\todo-app-cosmos-cloud.properties
```

> **Don't have the Azure CLI?**
> Get your endpoint and key from the [Azure Portal](https://portal.azure.com)
> → your Cosmos DB account → **Keys**, then create the file manually:
> ```properties
> multiclouddb.provider=cosmos
> multiclouddb.connection.endpoint=https://<YOUR-ACCOUNT>.documents.azure.com:443/
> multiclouddb.connection.key=<YOUR-PRIMARY-KEY>
> multiclouddb.connection.connectionMode=gateway
> ```

> **Using Entra ID instead of a master key?** See
> `todo-app-cosmos-cloud.properties.template` for Option B (DefaultAzureCredential)
> instructions, which requires assigning the _Cosmos DB Built-in Data Contributor_ role.

---

#### Step 2 — Create the Cosmos DB database and container

The SDK expects database `todoapp` and container `todos` with partition key `/partitionKey`.

**macOS / Linux**

**Step 1 — Create the database:**

```bash
az cosmosdb sql database create \
  --account-name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --name todoapp
```

**Step 2 — Create the container:**

```bash
az cosmosdb sql container create \
  --account-name "$COSMOS_ACCOUNT" --resource-group "$COSMOS_RG" \
  --database-name todoapp --name todos \
  --partition-key-path /partitionKey
```

---

**Windows (PowerShell)**

**Step 1 — Create the database:**

```powershell
az cosmosdb sql database create `
  --account-name $COSMOS_ACCOUNT --resource-group $COSMOS_RG `
  --name todoapp
```

**Step 2 — Create the container:**

```powershell
az cosmosdb sql container create `
  --account-name $COSMOS_ACCOUNT --resource-group $COSMOS_RG `
  --database-name todoapp --name todos `
  --partition-key-path /partitionKey
```

> Or use the Azure Portal → your Cosmos DB account → **Data Explorer** →
> **New Container** with database id `todoapp`, container id `todos`,
> partition key `/partitionKey`.

---

#### Step 3 — Build and run

**Step 1 — Compile (picks up the new properties file):**

```bash
mvn compile
```

**Step 2 — Run:**

**macOS / Linux:**

```bash
mvn exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-cp %classpath -Dtodo.config=todo-app-cosmos-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

**Windows (PowerShell):**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-cosmos-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

---

#### Step 4 — Clean up Cosmos DB resources (optional)

> Run this when you no longer need the sample data and want to remove all
> provisioned resources.

**macOS / Linux:**

First time only — make the script executable:

```bash
chmod +x scripts/cleanup-cosmos.sh
```

Run the cleanup:

```bash
./scripts/cleanup-cosmos.sh
```

**Windows (PowerShell):**

```powershell
.\scripts\cleanup-cosmos.ps1
```

---

### DynamoDB Cloud Setup

#### Step 1 — Configure AWS credentials

**Step 1 — Run the interactive setup wizard:**

```bash
aws configure
```

> Prompts for: AWS Access Key ID, Secret Access Key, default region, output format.

---

#### Step 2 — Create the properties file

**macOS / Linux**

**Step 1 — Read the configured region:**

```bash
export AWS_REGION="$(aws configure get region)"
```

**Step 2 — Write the properties file:**

```bash
cat > src/main/resources/todo-app-dynamo-cloud.properties << EOF
multiclouddb.provider=dynamo
multiclouddb.connection.region=$AWS_REGION
EOF
```

**Step 3 — Verify:**

```bash
cat src/main/resources/todo-app-dynamo-cloud.properties
```

---

**Windows (PowerShell)**

**Step 1 — Read the configured region:**

```powershell
$AWS_REGION = (aws configure get region)
```

**Step 2 — Write the properties file:**

```powershell
@"
multiclouddb.provider=dynamo
multiclouddb.connection.region=$AWS_REGION
"@ | Set-Content src\main\resources\todo-app-dynamo-cloud.properties
```

**Step 3 — Verify:**

```powershell
Get-Content src\main\resources\todo-app-dynamo-cloud.properties
```

---

#### Step 3 — Create the DynamoDB table

The SDK maps `ResourceAddress("todoapp", "todos")` to a table named `todoapp__todos`
with hash key `partitionKey` (String) and range key `sortKey` (String).

**macOS / Linux:**

```bash
aws dynamodb create-table \
  --table-name todoapp__todos \
  --attribute-definitions \
    AttributeName=partitionKey,AttributeType=S \
    AttributeName=sortKey,AttributeType=S \
  --key-schema \
    AttributeName=partitionKey,KeyType=HASH \
    AttributeName=sortKey,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --region "$AWS_REGION"
```

**Windows (PowerShell):**

```powershell
aws dynamodb create-table `
  --table-name todoapp__todos `
  --attribute-definitions `
    AttributeName=partitionKey,AttributeType=S `
    AttributeName=sortKey,AttributeType=S `
  --key-schema `
    AttributeName=partitionKey,KeyType=HASH `
    AttributeName=sortKey,KeyType=RANGE `
  --billing-mode PAY_PER_REQUEST `
  --region $AWS_REGION
```

---

#### Step 4 — Build and run

**Step 1 — Compile (picks up the new properties file):**

```bash
mvn compile
```

**Step 2 — Run:**

**macOS / Linux:**

```bash
mvn exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-cp %classpath -Dtodo.config=todo-app-dynamo-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

**Windows (PowerShell):**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-dynamo-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

---

#### Step 5 — Clean up DynamoDB resources (optional)

> **Cost note:** DynamoDB charges for storage and on-demand capacity.
> Delete the table after testing to avoid ongoing charges.

**macOS / Linux:**

First time only — make the script executable:

```bash
chmod +x scripts/cleanup-dynamo.sh
```

Run the cleanup:

```bash
./scripts/cleanup-dynamo.sh
```

**Windows (PowerShell):**

```powershell
.\scripts\cleanup-dynamo.ps1
```

---

### Spanner Cloud Setup

#### Step 1 — Enable the Cloud Spanner API

```bash
gcloud services enable spanner.googleapis.com
```

---

#### Step 2 — Authenticate

```bash
gcloud auth application-default login
```

---

#### Step 3 — Create the properties file

**macOS / Linux**

**Step 1 — Enter your GCP project ID:**

```bash
printf "GCP project ID: "; read GCP_PROJECT
```

**Step 2 — Enter your Spanner instance ID:**

```bash
printf "Spanner instance ID: "; read SPANNER_INSTANCE
```

**Step 3 — Write the properties file:**

```bash
cat > src/main/resources/todo-app-spanner-cloud.properties << EOF
multiclouddb.provider=spanner
multiclouddb.connection.projectId=$GCP_PROJECT
multiclouddb.connection.instanceId=$SPANNER_INSTANCE
multiclouddb.connection.databaseId=todoapp
EOF
```

**Step 4 — Verify:**

```bash
cat src/main/resources/todo-app-spanner-cloud.properties
```

---

**Windows (PowerShell)**

**Step 1 — Enter your GCP project ID:**

```powershell
$GCP_PROJECT = Read-Host "GCP project ID"
```

**Step 2 — Enter your Spanner instance ID:**

```powershell
$SPANNER_INSTANCE = Read-Host "Spanner instance ID"
```

**Step 3 — Write the properties file:**

```powershell
@"
multiclouddb.provider=spanner
multiclouddb.connection.projectId=$GCP_PROJECT
multiclouddb.connection.instanceId=$SPANNER_INSTANCE
multiclouddb.connection.databaseId=todoapp
"@ | Set-Content src\main\resources\todo-app-spanner-cloud.properties
```

**Step 4 — Verify:**

```powershell
Get-Content src\main\resources\todo-app-spanner-cloud.properties
```

---

#### Step 4 — Create the Spanner instance

> Skip this step if you already have a Spanner instance to use.

**macOS / Linux:**

```bash
gcloud spanner instances create "$SPANNER_INSTANCE" \
  --config=regional-us-central1 \
  --description="Todo App Instance" \
  --processing-units=100 \
  --project="$GCP_PROJECT"
```

**Windows (PowerShell):**

```powershell
gcloud spanner instances create $SPANNER_INSTANCE `
  --config=regional-us-central1 `
  --description="Todo App Instance" `
  --processing-units=100 `
  --project=$GCP_PROJECT
```

> See [available instance configurations](https://cloud.google.com/spanner/docs/instance-configurations)
> for a region closest to you (e.g. `regional-us-east1`, `regional-europe-west1`).

---

#### Step 5 — Create the Spanner database and table

**macOS / Linux:**

```bash
gcloud spanner databases create todoapp \
  --instance="$SPANNER_INSTANCE" \
  --project="$GCP_PROJECT" \
  --ddl="CREATE TABLE todos (partitionKey STRING(MAX) NOT NULL, sortKey STRING(MAX) NOT NULL, data STRING(MAX)) PRIMARY KEY (partitionKey, sortKey)"
```

**Windows (PowerShell):**

```powershell
gcloud spanner databases create todoapp `
  --instance=$SPANNER_INSTANCE `
  --project=$GCP_PROJECT `
  --ddl="CREATE TABLE todos (partitionKey STRING(MAX) NOT NULL, sortKey STRING(MAX) NOT NULL, data STRING(MAX)) PRIMARY KEY (partitionKey, sortKey)"
```

> The `data` column stores the full TODO document as a JSON string.
> The app uses `partitionKey = todoId` and `sortKey = todoId`.

---

#### Step 6 — Build and run

**Step 1 — Compile (picks up the new properties file):**

```bash
mvn compile
```

**Step 2 — Run:**

**macOS / Linux:**

```bash
mvn exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-cp %classpath -Dtodo.config=todo-app-spanner-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

**Windows (PowerShell):**

```powershell
mvn exec:exec `
  -Dexec.executable="java" `
  "-Dexec.args=-cp %classpath -Dtodo.config=todo-app-spanner-cloud.properties com.multiclouddb.samples.todo.TodoApp"
```

---

#### Step 5 — Clean up Spanner resources (optional)

> **Cost note:** Spanner instances are billed by the hour. Delete the database
> after testing to avoid ongoing charges.

**macOS / Linux:**

First time only — make the script executable:

```bash
chmod +x scripts/cleanup-spanner.sh
```

Run the cleanup:

```bash
./scripts/cleanup-spanner.sh
```

**Windows (PowerShell):**

```powershell
.\scripts\cleanup-spanner.ps1
```

---

## Cleanup

Use these scripts to delete all cloud resources created by the sample runs.
Each script reads your credentials from the corresponding properties file
automatically, shows a preview of what will be deleted, and asks for
confirmation before proceeding.

### Clean up Cosmos DB resources

**macOS / Linux:**

First time only:

```bash
chmod +x scripts/cleanup-cosmos.sh
```

Run:

```bash
./scripts/cleanup-cosmos.sh
```

**Windows (PowerShell):**

```powershell
.\scripts\cleanup-cosmos.ps1
```

To override account or resource group without editing any file:

**macOS / Linux:**
```bash
COSMOS_ACCOUNT=my-account RESOURCE_GROUP=my-rg \
  ./scripts/cleanup-cosmos.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\cleanup-cosmos.ps1 -CosmosAccount my-account -ResourceGroup my-rg
```

---

### Clean up DynamoDB resources

> **Cost note:** DynamoDB charges for storage and on-demand capacity.
> Delete tables after testing to avoid ongoing charges.

**macOS / Linux:**

First time only:

```bash
chmod +x scripts/cleanup-dynamo.sh
```

Run:

```bash
./scripts/cleanup-dynamo.sh
```

**Windows (PowerShell):**

```powershell
.\scripts\cleanup-dynamo.ps1
```

To override the region:

**macOS / Linux:**
```bash
AWS_REGION=eu-west-1 ./scripts/cleanup-dynamo.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\cleanup-dynamo.ps1 -Region eu-west-1
```

---

### Clean up Spanner resources

> **Cost note:** Spanner instances are billed by the hour. Delete databases
> after testing to avoid ongoing charges.

**macOS / Linux:**

First time only:

```bash
chmod +x scripts/cleanup-spanner.sh
```

Run:

```bash
./scripts/cleanup-spanner.sh
```

**Windows (PowerShell):**

```powershell
.\scripts\cleanup-spanner.ps1
```

To override project or instance:

**macOS / Linux:**
```bash
GCP_PROJECT=my-project SPANNER_INSTANCE=my-instance \
  ./scripts/cleanup-spanner.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\cleanup-spanner.ps1 -GcpProject my-project -SpannerInstance my-instance
```
