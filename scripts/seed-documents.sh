#!/usr/bin/env bash
# Uploads the sample documents through the public API (nginx → gateway → ingestion).
#   scripts/seed-documents.sh [BASE_URL]        default BASE_URL=http://localhost
# Works in Git Bash on Windows, Linux and macOS (needs curl; python only for JSON parsing).
set -euo pipefail

BASE="${1:-http://localhost}"
PASSWORD="${SEED_DEFAULT_PASSWORD:-EduPulse#2026}"
DIR="$(cd "$(dirname "$0")/../sample-docs" && pwd)"

json_field() { python -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

login() {
  curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}" | json_field accessToken
}

upload() { # token file category [docType] [clearance]
  local token="$1" file="$2" category="$3" doctype="${4:-}" clearance="${5:-1}"
  local url="$BASE/api/documents?category=$category&clearanceLevel=$clearance"
  [ -n "$doctype" ] && url="$url&docType=$(python -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$doctype")"
  local resp code body status
  resp=$(curl -sS -w '\n%{http_code}' -X POST "$url" -H "Authorization: Bearer $token" -F "file=@$file")
  code="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  status=$(printf '%s' "$body" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('document',{}).get('status') or d.get('detail'))" 2>/dev/null || echo "?")
  printf '  %-36s %-16s HTTP %s  %s\n' "$(basename "$file")" "$category" "$code" "$status"
}

echo "Logging in as officer@acme.test (Acme / Information Security)…"
T=$(login officer@acme.test)
for f in authentication_datahandling compliance_responsibilities policy_incidentmanagement scope_roles secure_development updatecycle_checklist; do
  upload "$T" "$DIR/$f.pdf" INTERNAL_POLICY "security handbook"
done
upload "$T" "$DIR/remote-work-policy.docx" INTERNAL_POLICY "remote work"
upload "$T" "$DIR/backup-recovery-procedure.txt" INTERNAL_POLICY "procedure"
upload "$T" "$DIR/regulation-test.pdf" REGULATION "external regulation"

echo "Logging in as officer.fin@acme.test (Acme / Finance) – used for the tenant-isolation demo…"
T2=$(login officer.fin@acme.test)
upload "$T2" "$DIR/finance-expense-policy.txt" INTERNAL_POLICY "finance" 2

echo "Done. Ask e.g.: \"How should I handle MFA?\" as employee@acme.test"
