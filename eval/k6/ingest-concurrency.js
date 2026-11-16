// k6 run eval/k6/ingest-concurrency.js -e BASE=http://localhost
// NFR: 50 simultaneous multi-page ingestions without dropping requests.
// Each VU uploads a unique multi-page TXT document (unique content → no SHA-256 dedup) exactly once.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://localhost';

export const options = {
  scenarios: { burst: { executor: 'per-vu-iterations', vus: 50, iterations: 1, maxDuration: '15m' } },
  thresholds: { checks: ['rate==1.0'] },
};

export function setup() {
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: 'officer@acme.test', password: __ENV.PASSWORD || 'EduPulse#2026' }),
    { headers: { 'Content-Type': 'application/json' } });
  return { token: r.json('accessToken') };
}

function document(vu) {
  let text = `LOAD TEST POLICY ${vu} ${Date.now()}\n`;
  for (let p = 0; p < 6; p++) {
    for (let l = 0; l < 60; l++) {
      text += `Section ${p}.${l} of load document ${vu}: employees must protect confidential data and report incidents promptly.\n`;
    }
  }
  return text;
}

export default function (data) {
  const res = http.post(`${BASE}/api/documents?category=AUDIT_REPORT&docType=loadtest`, {
    file: http.file(document(__VU), `load-${__VU}.txt`, 'text/plain'),
  }, { headers: { Authorization: `Bearer ${data.token}` }, timeout: '600s' });
  check(res, { 'accepted (201/202)': r => r.status === 201 || r.status === 202 });
  const id = res.json('documentId');
  for (let i = 0; i < 120 && id; i++) {
    const s = http.get(`${BASE}/api/documents/${id}/status`, { headers: { Authorization: `Bearer ${data.token}` } });
    const st = s.json('status');
    if (st === 'INDEXED' || st === 'FAILED') {
      check(s, { 'indexed': () => st === 'INDEXED' });
      return;
    }
    sleep(5);
  }
}
