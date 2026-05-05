#!/usr/bin/env bash
# cleanup-spanner.sh -- Delete Spanner databases created by the samples
#
# Usage (run from the repo root):
#   chmod +x scripts/cleanup-spanner.sh
#   ./scripts/cleanup-spanner.sh

set -euo pipefail

echo "=== Spanner Cleanup Script ==="
echo "Working directory : $(pwd)"
echo ""

PROPS="src/main/resources/todo-app-spanner-cloud.properties"

resolve_from_props() {
  local key="$1"
  if [[ -f "$PROPS" ]]; then
    grep -E "^${key}=" "$PROPS" | sed 's/^[^=]*=//' | tr -d '[:space:]' || true
  fi
}

# -- Project + Instance -------------------------------------------------------
if [[ -z "${GCP_PROJECT:-}" ]]; then GCP_PROJECT=$(resolve_from_props "multiclouddb.connection.projectId"); fi
if [[ -z "${GCP_PROJECT:-}" ]]; then GCP_PROJECT=$(gcloud config get-value project 2>/dev/null || true); fi
if [[ -z "${GCP_PROJECT:-}" ]]; then read -rp "Enter your GCP project ID: " GCP_PROJECT; fi

if [[ -z "${SPANNER_INSTANCE:-}" ]]; then SPANNER_INSTANCE=$(resolve_from_props "multiclouddb.connection.instanceId"); fi
if [[ -z "${SPANNER_INSTANCE:-}" ]]; then read -rp "Enter your Spanner instance ID: " SPANNER_INSTANCE; fi

if [[ -z "$GCP_PROJECT" || -z "$SPANNER_INSTANCE" ]]; then
  echo "ERROR: GCP project and Spanner instance are required. Aborting." >&2; exit 1
fi
echo "Project  : $GCP_PROJECT"
echo "Instance : $SPANNER_INSTANCE"
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

# -- Scope selection ----------------------------------------------------------
echo "Which databases do you want to clean up?"
PS3="Enter choice [1-3]: "
select SCOPE in \
  "Todo App only  (multiclouddb-sdk-for-java-todo-*)" \
  "Risk Platform only  (multiclouddb-sdk-for-java-risk-*)" \
  "All (both apps)"; do
  case $SCOPE in
    "Todo App only"*)      CANDIDATES=("${TODO_DBS[@]}"); break ;;
    "Risk Platform only"*) CANDIDATES=("${RISK_DBS[@]}"); break ;;
    "All (both apps)")     CANDIDATES=("${TODO_DBS[@]}" "${RISK_DBS[@]}"); break ;;
    *) echo "Invalid choice — enter 1, 2, or 3." ;;
  esac
done
echo ""

# -- Query existing databases -------------------------------------------------
echo "Querying existing databases in instance '$SPANNER_INSTANCE'..."
EXISTING=$(gcloud spanner databases list \
  --instance="$SPANNER_INSTANCE" \
  --project="$GCP_PROJECT" \
  --format="value(name)" 2>/dev/null | sed 's|.*/||' || true)

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
  ERROR_OUTPUT=$(gcloud spanner databases delete "$DB" \
    --instance="$SPANNER_INSTANCE" --project="$GCP_PROJECT" --quiet 2>&1) && RC=0 || RC=$?
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
