# cleanup-dynamo.ps1 -- Delete DynamoDB tables created by the samples
#
# Usage (run from the repo root):
#   .\scripts\cleanup-dynamo.ps1
#   .\scripts\cleanup-dynamo.ps1 -Region eu-west-1

param([string]$Region = "")

Write-Host "=== DynamoDB Cleanup Script ==="
Write-Host "Working directory : $(Get-Location)"; Write-Host ""

if (-not $Region) { $Region = $env:AWS_REGION }
if (-not $Region) { $Region = (aws configure get region 2>$null) }
if (-not $Region) { $Region = Read-Host "Enter your AWS region (e.g. us-east-1)" }
if (-not $Region) { Write-Error "No AWS region specified. Aborting."; exit 1 }
Write-Host "Region : $Region"; Write-Host ""

$TodoTables = @("multiclouddb-sdk-for-java-todo-app__todos")
$RiskTables = @(
    "multiclouddb-sdk-for-java-risk-admin__tenants",
    "multiclouddb-sdk-for-java-risk-acme-capital__portfolios","multiclouddb-sdk-for-java-risk-acme-capital__positions",
    "multiclouddb-sdk-for-java-risk-acme-capital__risk_metrics","multiclouddb-sdk-for-java-risk-acme-capital__alerts",
    "multiclouddb-sdk-for-java-risk-vanguard-partners__portfolios","multiclouddb-sdk-for-java-risk-vanguard-partners__positions",
    "multiclouddb-sdk-for-java-risk-vanguard-partners__risk_metrics","multiclouddb-sdk-for-java-risk-vanguard-partners__alerts",
    "multiclouddb-sdk-for-java-risk-summit-wealth__portfolios","multiclouddb-sdk-for-java-risk-summit-wealth__positions",
    "multiclouddb-sdk-for-java-risk-summit-wealth__risk_metrics","multiclouddb-sdk-for-java-risk-summit-wealth__alerts",
    "multiclouddb-sdk-for-java-risk-shared__market_data"
)

Write-Host "Which tables do you want to clean up?"
Write-Host "  1) Todo App only  (multiclouddb-sdk-for-java-todo-*)"
Write-Host "  2) Risk Platform only  (multiclouddb-sdk-for-java-risk-*)"
Write-Host "  3) All (both apps)"
$Choice = Read-Host "Enter choice [1-3]"
switch ($Choice) {
    "1" { $Candidates = $TodoTables }
    "2" { $Candidates = $RiskTables }
    "3" { $Candidates = $TodoTables + $RiskTables }
    default { Write-Error "Invalid choice. Aborting."; exit 1 }
}
Write-Host ""

Write-Host "Querying existing tables in region '$Region'..."
$Existing = (aws dynamodb list-tables --region $Region --query "TableNames[]" --output text 2>$null) -split "\s+" | Where-Object { $_ }

$Tables = $Candidates | Where-Object { $Existing -contains $_ }
if (-not $Tables) { Write-Host "  No matching tables found — nothing to delete."; exit 0 }

Write-Host ""; Write-Host "Found $($Tables.Count) table(s) to delete:"
foreach ($T in $Tables) { Write-Host "  - $T" }
Write-Host ""
$Confirm = Read-Host "Proceed? [y/N]"
if ($Confirm -ne "y") { Write-Host "Aborted."; exit 0 }

Write-Host ""; $Errors = 0
foreach ($T in $Tables) {
    Write-Host "  Deleting $T ..."
    $out = aws dynamodb delete-table --table-name $T --region $Region --output text 2>&1
    if ($LASTEXITCODE -eq 0) { Write-Host "  v Deleted : $T" }
    else { Write-Host "  x Failed  : $T"; Write-Host "    Error   : $out"; $Errors++ }
}
Write-Host ""
if ($Errors -eq 0) { Write-Host "Done." } else { Write-Host "Done with $Errors failure(s)." }
