#!/usr/bin/env bash
# Uploads the sample documents through the public API (nginx → gateway → ingestion service).
#   bash scripts/seed-documents.sh [BASE_URL]        default: http://localhost
set -euo pipefail

BASE="${1:-http://localhost}"
PASSWORD="${SEED_DEFAULT_PASSWORD:-EduPulse#2026}"
DIR="$(cd "$(dirname "$0")/../sample-docs" && pwd)"

login() {
  curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}" | python -c "import sys,json; print(json.load(sys.stdin)['accessToken'])"
}

upload() { # token file category docType clearance
  local code
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "$BASE/api/documents?category=$3&docType=$4&clearanceLevel=$5" \
    -H "Authorization: Bearer $1" -F "file=@$2")
  printf '  %-42s %-16s HTTP %s\n' "$(basename "$2")" "$3" "$code"
}

echo "Waiting until the gateway can reach the ingestion service…"
T=$(login officer@helix.test)
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/documents" -H "Authorization: Bearer $T")" = "200" ]; do sleep 3; done

echo "Clinical Operations (officer@helix.test):"
for f in authentication_datahandling compliance_responsibilities policy_incidentmanagement scope_roles secure_development updatecycle_checklist; do
  upload "$T" "$DIR/$f.pdf" INTERNAL_POLICY handbook 1
done
upload "$T" "$DIR/patient-data-protection-policy.pdf" INTERNAL_POLICY policy 1
upload "$T" "$DIR/clinical-incident-reporting.docx" INTERNAL_POLICY procedure 1
upload "$T" "$DIR/medical-device-security.txt" INTERNAL_POLICY procedure 1
upload "$T" "$DIR/regulation-health-data-security.pdf" REGULATION regulation 1

echo "Billing & Insurance (officer.billing@helix.test) – used for the data isolation demo:"
T2=$(login officer.billing@helix.test)
upload "$T2" "$DIR/billing-insurance-claims-policy.txt" INTERNAL_POLICY policy 2
