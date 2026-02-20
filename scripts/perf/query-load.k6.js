import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const username = __ENV.BADGERMOLE_USER || 'admin';
const password = __ENV.BADGERMOLE_PASSWORD || 'Admin1234!';

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '60s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
  },
};

function login() {
  const csrfRes = http.get(`${baseUrl}/api/auth/csrf`);
  check(csrfRes, { 'csrf ok': (r) => r.status === 200 });
  const csrf = csrfRes.json();

  const loginRes = http.post(
    `${baseUrl}/api/auth/login`,
    JSON.stringify({ username, password }),
    {
      headers: {
        'Content-Type': 'application/json',
        [csrf.headerName]: csrf.token,
      },
    }
  );
  check(loginRes, { 'login ok': (r) => r.status === 200 });
}

function runQuery() {
  const submitRes = http.post(
    `${baseUrl}/api/queries`,
    JSON.stringify({
      datasourceId: __ENV.DATASOURCE_ID || 'postgresql-core',
      sql: __ENV.SQL || 'SELECT 1 AS value;',
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (!check(submitRes, { 'submit query': (r) => r.status === 200 })) {
    return null;
  }
  return submitRes.json('executionId');
}

function waitForCompletion(executionId) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const statusRes = http.get(`${baseUrl}/api/queries/${executionId}`);
    if (!check(statusRes, { 'status available': (r) => r.status === 200 })) {
      return null;
    }

    const status = statusRes.json('status');
    if (status === 'SUCCEEDED') {
      return true;
    }
    if (status === 'FAILED' || status === 'CANCELED') {
      return false;
    }
    sleep(0.2);
  }
  return false;
}

export default function () {
  login();
  const executionId = runQuery();
  if (!executionId) {
    sleep(0.5);
    return;
  }

  const completed = waitForCompletion(executionId);
  if (completed) {
    const resultsRes = http.get(`${baseUrl}/api/queries/${executionId}/results?pageSize=100`);
    check(resultsRes, { 'results ok': (r) => r.status === 200 });

    const exportRes = http.get(`${baseUrl}/api/queries/${executionId}/export.csv?headers=true`);
    check(exportRes, { 'export ok or forbidden': (r) => r.status === 200 || r.status === 403 });
  }

  sleep(0.2);
}
