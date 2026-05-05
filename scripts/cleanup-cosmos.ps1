# cleanup-cosmos.ps1 -- Delete Cosmos DB databases created by the samples
#
# Usage (run from the repo root):
#   .\scripts\cleanup-cosmos.ps1
#   .\scripts\cleanup-cosmos.ps1 -CosmosAccount my-account -ResourceGroup my-rg

param([string]$CosmosAccount = "", [string]$ResourceGroup = "")

Write-Host "=== Cosmos DB Cleanup Script ==="
Write-Host "Working directory : $(Get-Location)"
Write-Host ""

$PropsTodo = "src\main\resources\todo-app-cosmos-cloud.properties"
$PropsRisk = "src\main\resources\risk-platform-cosmos-cloud.properties"

Write-Host "Looking for properties files..."
if (Test-Path $PropsTodo) { Write-Host "  Found : $PropsTodo" } else { Write-Host "  Missing: $PropsTodo" }
if (Test-Path $PropsRisk) { Write-Host "  Found : $PropsRisk" } else { Write-Host "  Missing: $PropsRisk" }
Write-Host ""

function Get-Prop([string]$Key, [string]$File) {
    if (Test-Path $File) {
        $line = Get-Content $File | Where-Object { $_ -match "^${Key}=" }
        if ($line) { return ($line -split "=", 2)[1].Trim() }
    }; return ""
}

if (-not $CosmosAccount) { $CosmosAccount = $env:COSMOS_ACCOUNT }
if (-not $CosmosAccount) {
    Write-Host "Resolving Cosmos account from properties files..."
    foreach ($Props in @($PropsTodo, $PropsRisk)) {
        $ep = Get-Prop "multiclouddb.connection.endpoint" $Props
        if ($ep -match "https://([^.]+)\.documents\.azure\.com") {
            $CosmosAccount = $Matches[1]; Write-Host "  Resolved from $Props -> $CosmosAccount"; break
        }
    }
}
if (-not $CosmosAccount) { $CosmosAccount = Read-Host "Enter your Cosmos DB account name" }

if (-not $ResourceGroup) { $ResourceGroup = $env:RESOURCE_GROUP }
if (-not $ResourceGroup) {
    Write-Host "Resolving resource group from properties files..."
    foreach ($Props in @($PropsTodo, $PropsRisk)) {
        $ResourceGroup = Get-Prop "multiclouddb.connection.resourceGroupName" $Props
        if ($ResourceGroup) { Write-Host "  Resolved from $Props -> $ResourceGroup"; break }
    }
}
if (-not $ResourceGroup -and $CosmosAccount) {
    Write-Host "Resource group not in properties file — looking up from account name..."
    $ResourceGroup = (az cosmosdb list --query "[?name=='$CosmosAccount'].resourceGroup" -o tsv 2>$null) -replace '\s',''
    if ($ResourceGroup) { Write-Host "  Found: $ResourceGroup" } else { Write-Host "  Could not auto-discover." }
}
if (-not $ResourceGroup) { $ResourceGroup = Read-Host "Enter your resource group name" }
if (-not $CosmosAccount -or -not $ResourceGroup) { Write-Error "Account and resource group required. Aborting."; exit 1 }

Write-Host ""; Write-Host "Account        : $CosmosAccount"; Write-Host "Resource Group : $ResourceGroup"; Write-Host ""

$TodoDbs = @("multiclouddb-sdk-for-java-todo-app")
$RiskDbs  = @("multiclouddb-sdk-for-java-risk-admin","multiclouddb-sdk-for-java-risk-acme-capital",
              "multiclouddb-sdk-for-java-risk-vanguard-partners","multiclouddb-sdk-for-java-risk-summit-wealth",
              "multiclouddb-sdk-for-java-risk-shared")

Write-Host "Which databases do you want to clean up?"
Write-Host "  1) Todo App only  (multiclouddb-sdk-for-java-todo-*)"
Write-Host "  2) Risk Platform only  (multiclouddb-sdk-for-java-risk-*)"
Write-Host "  3) All (both apps)"
$Choice = Read-Host "Enter choice [1-3]"
switch ($Choice) {
    "1" { $Candidates = $TodoDbs }
    "2" { $Candidates = $RiskDbs }
    "3" { $Candidates = $TodoDbs + $RiskDbs }
    default { Write-Error "Invalid choice. Aborting."; exit 1 }
}
Write-Host ""

Write-Host "Querying existing databases in account '$CosmosAccount'..."
$Existing = (az cosmosdb sql database list --account-name $CosmosAccount --resource-group $ResourceGroup --query "[].name" -o tsv 2>$null) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }

$Databases = $Candidates | Where-Object { $Existing -contains $_ }
if (-not $Databases) { Write-Host "  No matching databases found — nothing to delete."; exit 0 }

Write-Host ""; Write-Host "Found $($Databases.Count) database(s) to delete:"
foreach ($Db in $Databases) { Write-Host "  - $Db" }
Write-Host ""
$Confirm = Read-Host "Proceed? [y/N]"
if ($Confirm -ne "y") { Write-Host "Aborted."; exit 0 }

Write-Host ""; $Errors = 0
foreach ($Db in $Databases) {
    Write-Host "  Deleting $Db ..."
    $out = az cosmosdb sql database delete --account-name $CosmosAccount --resource-group $ResourceGroup --name $Db --yes --output none 2>&1
    if ($LASTEXITCODE -eq 0) { Write-Host "  v Deleted : $Db" }
    else { Write-Host "  x Failed  : $Db"; Write-Host "    Error   : $out"; $Errors++ }
}
Write-Host ""
if ($Errors -eq 0) { Write-Host "Done." } else { Write-Host "Done with $Errors failure(s)." }
