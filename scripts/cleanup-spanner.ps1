# cleanup-spanner.ps1 -- Delete Spanner databases created by the samples
#
# Usage (run from the repo root):
#   .\scripts\cleanup-spanner.ps1
#   .\scripts\cleanup-spanner.ps1 -GcpProject my-project -SpannerInstance my-instance

param([string]$GcpProject = "", [string]$SpannerInstance = "")

Write-Host "=== Spanner Cleanup Script ==="
Write-Host "Working directory : $(Get-Location)"; Write-Host ""

$Props = "src\main\resources\todo-app-spanner-cloud.properties"
function Get-Prop([string]$Key) {
    if (Test-Path $Props) {
        $line = Get-Content $Props | Where-Object { $_ -match "^${Key}=" }
        if ($line) { return ($line -split "=", 2)[1].Trim() }
    }; return ""
}

if (-not $GcpProject)      { $GcpProject      = $env:GCP_PROJECT }
if (-not $GcpProject)      { $GcpProject      = Get-Prop "multiclouddb.connection.projectId" }
if (-not $GcpProject)      { $GcpProject      = (gcloud config get-value project 2>$null) }
if (-not $GcpProject)      { $GcpProject      = Read-Host "Enter your GCP project ID" }
if (-not $SpannerInstance) { $SpannerInstance = $env:SPANNER_INSTANCE }
if (-not $SpannerInstance) { $SpannerInstance = Get-Prop "multiclouddb.connection.instanceId" }
if (-not $SpannerInstance) { $SpannerInstance = Read-Host "Enter your Spanner instance ID" }
if (-not $GcpProject -or -not $SpannerInstance) { Write-Error "Project and instance required. Aborting."; exit 1 }

Write-Host "Project  : $GcpProject"; Write-Host "Instance : $SpannerInstance"; Write-Host ""

$TodoDbs = @("multiclouddb-sdk-for-java-todo-app")
$RiskDbs = @("multiclouddb-sdk-for-java-risk-admin","multiclouddb-sdk-for-java-risk-acme-capital",
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

Write-Host "Querying existing databases in instance '$SpannerInstance'..."
$Existing = (gcloud spanner databases list --instance=$SpannerInstance --project=$GcpProject --format="value(name)" 2>$null) -split "`n" | ForEach-Object { $_ -replace ".*/", "" } | Where-Object { $_ }

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
    $out = gcloud spanner databases delete $Db --instance=$SpannerInstance --project=$GcpProject --quiet 2>&1
    if ($LASTEXITCODE -eq 0) { Write-Host "  v Deleted : $Db" }
    else { Write-Host "  x Failed  : $Db"; Write-Host "    Error   : $out"; $Errors++ }
}
Write-Host ""
if ($Errors -eq 0) { Write-Host "Done." } else { Write-Host "Done with $Errors failure(s)." }
