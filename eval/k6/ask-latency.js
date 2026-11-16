// k6 run eval/k6/ask-latency.js -e BASE=http://localhost
// NFR: semantic lookup + start of streaming < 2 s. Measures time-to-first-byte of the SSE stream.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost';
const ttfb = new Trend('stream_ttfb', true);

export const options = {
  vus: 5,
  duration: '1m',
  thresholds: { stream_ttfb: ['p(95)<2000'] },
};

const questions = [
  'How should I handle MFA?',
  'What is the minimum password length?',
  'How do I report a security incident?',
  'Which threat modeling methodology is used?',
  'How often are the security policies reviewed?',
];

export function setup() {
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: 'employee@acme.test', password: __ENV.PASSWORD || 'EduPulse#2026' }),
    { headers: { 'Content-Type': 'application/json' } });
  return { token: r.json('accessToken') };
}

export default function (data) {
  const q = questions[Math.floor(Math.random() * questions.length)];
  const res = http.post(`${BASE}/api/compliance/ask/stream`, JSON.stringify({ question: q, mode: 'FAST' }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}`, Accept: 'text/event-stream' },
    timeout: '120s',
  });
  ttfb.add(res.timings.waiting);
  check(res, { 'status 200': r => r.status === 200, 'has tokens': r => r.body.includes('event:token') });
}
