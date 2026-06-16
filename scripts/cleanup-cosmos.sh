#!/usr/bin/env bash
# cleanup-cosmos.sh -- Delete Cosmos DB databases created by the samples
#
# Usage (run from the repo root):
#   chmod +x scripts/cleanup-cosmos.sh
#   ./scripts/cleanup-cosmos.sh

set -euo pipefail

echo "=== Cosmos DB Cleanup Script ==="
echo "Working directory : $(pwd)"
echo ""

PROPS_TODO="src/main/resources/todo-app-cosmos-cloud.properties"
PROPS_RISK="src/main/resources/risk-platform-cosmos-cloud.properties"
PROPS_CF="src/main/resources/change-feed-cosmos-cloud.properties"

echo "Looking for properties files..."
[[ -f "$PROPS_TODO" ]] && echo "  Found : $PROPS_TODO" || echo "  Missing: $PROPS_TODO"
[[ -f "$PROPS_RISK" ]] && echo "  Found : $PROPS_RISK" || echo "  Missing: $PROPS_RISK"
[[ -f "$PROPS_CF" ]]   && echo "  Found : $PROPS_CF"   || echo "  Missing: $PROPS_CF"
echo ""

# -- Helper -------------------------------------------------------------------
resolve_from_props() {
  local key="$1" file="$2"
  if [[ -f "$file" ]]; then
    grep -E "^${key}=" "$file" | sed 's/^[^=]*=//' | tr -d '[:space:]' || true
  fi
}

# -- Cosmos account -----------------------------------------------------------
if [[ -z "${COSMOS_ACCOUNT:-}" ]]; then
  echo "Resolving Cosmos account from properties files..."
  for PROPS in "$PROPS_TODO" "$PROPS_RISK" "$PROPS_CF"; do
    ENDPOINT=$(resolve_from_props "multiclouddb.connection.endpoint" "$PROPS")
    if [[ -n "$ENDPOINT" ]]; then
      COSMOS_ACCOUNT=$(echo "$ENDPOINT" | sed -E 's|https://([^.]+)\.documents\.azure\.com.*|\1|')
      echo "  Resolved from $PROPS → $COSMOS_ACCOUNT"
      break
    fi
  done
fi
if [[ -z "${COSMOS_ACCOUNT:-}" ]]; then
  read -rp "Enter your Cosmos DB account name: " COSMOS_ACCOUNT
fi

# -- Resource group -----------------------------------------------------------
if [[ -z "${RESOURCE_GROUP:-}" ]]; then
  echo "Resolving resource group from properties files..."
  for PROPS in "$PROPS_TODO" "$PROPS_RISK" "$PROPS_CF"; do
    RESOURCE_GROUP=$(resolve_from_props "multiclouddb.connection.resourceGroupName" "$PROPS")
    if [[ -n "$RESOURCE_GROUP" ]]; then
      echo "  Resolved from $PROPS → $RESOURCE_GROUP"; break
    fi
  done
fi
if [[ -z "${RESOURCE_GROUP:-}" && -n "${COSMOS_ACCOUNT:-}" ]]; then
  echo "Resource group not in properties file — looking up from account name..."
  RESOURCE_GROUP=$(az cosmosdb list --query "[?name=='${COSMOS_ACCOUNT}'].resourceGroup" -o tsv 2>/dev/null | tr -d '[:space:]' || true)
  if [[ -n "$RESOURCE_GROUP" ]]; then echo "  Found: $RESOURCE_GROUP"
  else echo "  Could not auto-discover resource group."; fi
fi
if [[ -z "${RESOURCE_GROUP:-}" ]]; then
  read -rp "Enter your resource group name: " RESOURCE_GROUP
fi
if [[ -z "$COSMOS_ACCOUNT" || -z "$RESOURCE_GROUP" ]]; then
  echo "ERROR: Cosmos DB account name and resource group are required. Aborting." >&2; exit 1
fi

echo ""
echo "Account        : $COSMOS_ACCOUNT"
echo "Resource Group : $RESOURCE_GROUP"
echo ""

# -- Known DB groups ----------------------------------------------------------
TODO_DBS=(multiclouddb-sdk-for-java-todo-app)
RISK_DBS=(
  multiclouddb-sdk-for-java-risk-admin
  multiclouddb-sdk-for-java-risk-acme-capital
  multiclouddb-sdk-for-java-risk-vanguard-partners
  multiclouddb-sdk-for-java-risk-summit-wealth
  multiclouddb-sdk-for-java-risk-shared
)
CHANGEFEED_DBS=(multiclouddb-sdk-for-java-changefeed)

# -- Scope selection ----------------------------------------------------------
echo "Which databases do you want to clean up?"
PS3="Enter choice [1-4]: "
select SCOPE in \
  "Todo App only  (multiclouddb-sdk-for-java-todo-*)" \
  "Risk Platform only  (multiclouddb-sdk-for-java-risk-*)" \
  "Change Feed only  (multiclouddb-sdk-for-java-changefeed)" \
  "All (all apps)"; do
  case $SCOPE in
    "Todo App only"*)      CANDIDATES=("${TODO_DBS[@]}"); break ;;
    "Risk Platform only"*) CANDIDATES=("${RISK_DBS[@]}"); break ;;
    "Change Feed only"*)   CANDIDATES=("${CHANGEFEED_DBS[@]}"); break ;;
    "All (all apps)")      CANDIDATES=("${TODO_DBS[@]}" "${RISK_DBS[@]}" "${CHANGEFEED_DBS[@]}"); break ;;
    *) echo "Invalid choice — enter 1, 2, 3, or 4." ;;
  esac
done
echo ""

# -- Query existing DBs -------------------------------------------------------
echo "Querying existing databases in account '$COSMOS_ACCOUNT'..."
EXISTING=$(az cosmosdb sql database list \
  --account-name "$COSMOS_ACCOUNT" \
  --resource-group "$RESOURCE_GROUP" \
  --query "[].name" -o tsv 2>/dev/null | tr '\t' '\n' || true)

DATABASES=()
for DB in "${CANDIDATES[@]}"; do
  if echo "$EXISTING" | grep -qxF "$DB"; then
    DATABASES+=("$DB")
  fi
done

if [[ ${#DATABASES[@]} -eq 0 ]]; then
  echo "  No matching databases found — nothing to delete."
  exit 0
fi

# -- Preview ------------------------------------------------------------------
echo ""
echo "Found ${#DATABASES[@]} database(s) to delete:"
for DB in "${DATABASES[@]}"; do echo "  - $DB"; done
echo ""
read -rp "Proceed? [y/N] " CONFIRM
if [[ "$(echo "$CONFIRM" | tr '[:upper:]' '[:lower:]')" != "y" ]]; then echo "Aborted."; exit 0; fi

# -- Delete -------------------------------------------------------------------
echo ""
ERRORS=0
for DB in "${DATABASES[@]}"; do
  echo "  Deleting $DB ..."
  ERROR_OUTPUT=$(az cosmosdb sql database delete \
    --account-name "$COSMOS_ACCOUNT" \
    --resource-group "$RESOURCE_GROUP" \
    --name "$DB" --yes --output none 2>&1) && RC=0 || RC=$?
  if [[ $RC -eq 0 ]]; then
    echo "  ✓ Deleted : $DB"
  else
    echo "  ✗ Failed  : $DB"
    echo "    Error   : $ERROR_OUTPUT"
    ERRORS=$((ERRORS + 1))
  fi
done

echo ""
if [[ $ERRORS -eq 0 ]]; then echo "Done."; else echo "Done with $ERRORS failure(s) — see errors above."; fi
