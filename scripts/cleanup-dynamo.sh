#!/usr/bin/env bash
# cleanup-dynamo.sh -- Delete DynamoDB tables created by the samples
#
# Usage (run from the repo root):
#   chmod +x scripts/cleanup-dynamo.sh
#   ./scripts/cleanup-dynamo.sh

set -euo pipefail

echo "=== DynamoDB Cleanup Script ==="
echo "Working directory : $(pwd)"
echo ""

# -- Region -------------------------------------------------------------------
REGION="${AWS_REGION:-}"
if [[ -z "$REGION" ]]; then REGION=$(aws configure get region 2>/dev/null || true); fi
if [[ -z "$REGION" ]]; then read -rp "Enter your AWS region (e.g. us-east-1): " REGION; fi
if [[ -z "$REGION" ]]; then echo "ERROR: No AWS region specified. Aborting." >&2; exit 1; fi
echo "Region : $REGION"
echo ""

# -- Known table groups -------------------------------------------------------
TODO_TABLES=(multiclouddb-sdk-for-java-todo-app__todos)
RISK_TABLES=(
  multiclouddb-sdk-for-java-risk-admin__tenants
  multiclouddb-sdk-for-java-risk-acme-capital__portfolios
  multiclouddb-sdk-for-java-risk-acme-capital__positions
  multiclouddb-sdk-for-java-risk-acme-capital__risk_metrics
  multiclouddb-sdk-for-java-risk-acme-capital__alerts
  multiclouddb-sdk-for-java-risk-vanguard-partners__portfolios
  multiclouddb-sdk-for-java-risk-vanguard-partners__positions
  multiclouddb-sdk-for-java-risk-vanguard-partners__risk_metrics
  multiclouddb-sdk-for-java-risk-vanguard-partners__alerts
  multiclouddb-sdk-for-java-risk-summit-wealth__portfolios
  multiclouddb-sdk-for-java-risk-summit-wealth__positions
  multiclouddb-sdk-for-java-risk-summit-wealth__risk_metrics
  multiclouddb-sdk-for-java-risk-summit-wealth__alerts
  multiclouddb-sdk-for-java-risk-shared__market_data
)

# -- Scope selection ----------------------------------------------------------
echo "Which tables do you want to clean up?"
PS3="Enter choice [1-3]: "
select SCOPE in \
  "Todo App only  (multiclouddb-sdk-for-java-todo-*)" \
  "Risk Platform only  (multiclouddb-sdk-for-java-risk-*)" \
  "All (both apps)"; do
  case $SCOPE in
    "Todo App only"*)      CANDIDATES=("${TODO_TABLES[@]}"); break ;;
    "Risk Platform only"*) CANDIDATES=("${RISK_TABLES[@]}"); break ;;
    "All (both apps)")     CANDIDATES=("${TODO_TABLES[@]}" "${RISK_TABLES[@]}"); break ;;
    *) echo "Invalid choice — enter 1, 2, or 3." ;;
  esac
done
echo ""

# -- Query existing tables ----------------------------------------------------
echo "Querying existing tables in region '$REGION'..."
EXISTING=$(aws dynamodb list-tables \
  --region "$REGION" \
  --query "TableNames[]" \
  --output text 2>/dev/null | tr '\t' '\n' || true)

TABLES=()
for TABLE in "${CANDIDATES[@]}"; do
  if echo "$EXISTING" | grep -qxF "$TABLE"; then
    TABLES+=("$TABLE")
  fi
done

if [[ ${#TABLES[@]} -eq 0 ]]; then
  echo "  No matching tables found — nothing to delete."
  exit 0
fi

# -- Preview ------------------------------------------------------------------
echo ""
echo "Found ${#TABLES[@]} table(s) to delete:"
for TABLE in "${TABLES[@]}"; do echo "  - $TABLE"; done
echo ""
read -rp "Proceed? [y/N] " CONFIRM
if [[ "$(echo "$CONFIRM" | tr '[:upper:]' '[:lower:]')" != "y" ]]; then echo "Aborted."; exit 0; fi

# -- Delete -------------------------------------------------------------------
echo ""
ERRORS=0
for TABLE in "${TABLES[@]}"; do
  echo "  Deleting $TABLE ..."
  ERROR_OUTPUT=$(aws dynamodb delete-table \
    --table-name "$TABLE" --region "$REGION" --output text 2>&1) && RC=0 || RC=$?
  if [[ $RC -eq 0 ]]; then
    echo "  ✓ Deleted : $TABLE"
  else
    echo "  ✗ Failed  : $TABLE"
    echo "    Error   : $ERROR_OUTPUT"
    ERRORS=$((ERRORS + 1))
  fi
done

echo ""
if [[ $ERRORS -eq 0 ]]; then echo "Done."; else echo "Done with $ERRORS failure(s) — see errors above."; fi
