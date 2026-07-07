import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const username = __ENV.DWARVENPICK_USER || 'admin';
const password = __ENV.DWARVENPICK_PASSWORD || 'Admin1234!';
const authMode = (__ENV.DWARVENPICK_AUTH || 'local').toLowerCase();
const datasourceId = __ENV.DATASOURCE_ID || 'postgresql-core';
const credentialProfile = __ENV.CREDENTIAL_PROFILE || '';
const justification = __ENV.JUSTIFICATION || '';
const pageSize = Number(__ENV.PAGE_SIZE || 100);
const exportCsv = (__ENV.EXPORT_CSV || 'true').toLowerCase() !== 'false';
const queryMix = (__ENV.SQL_MIX || __ENV.SQL || 'SELECT 1 AS value;')
  .split(/\n---+\n/)
  .map((sql) => sql.trim())
  .filter(Boolean);

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '60s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
  },
};

function fetchCsrf() {
  const csrfRes = http.get(`${baseUrl}/api/auth/csrf`);
  check(csrfRes, { 'csrf ok': (r) => r.status === 200 });
  return csrfRes.json();
}

function login() {
  const csrf = fetchCsrf();
  const loginPath = authMode === 'ldap' ? '/api/auth/ldap/login' : '/api/auth/login';

  const loginRes = http.post(
    `${baseUrl}${loginPath}`,
    JSON.stringify({ username, password }),
    {
      headers: {
        'Content-Type': 'application/json',
        [csrf.headerName]: csrf.token,
      },
    }
  );
  check(loginRes, { 'login ok': (r) => r.status === 200 });
  return fetchCsrf();
}

function runQuery(csrf) {
  const sql = queryMix[(__ITER + __VU - 1) % queryMix.length];
  const body = {
    datasourceId,
    sql,
  };
  if (credentialProfile) {
    body.credentialProfile = credentialProfile;
  }
  if (justification) {
    body.justification = justification;
  }

  const submitRes = http.post(
    `${baseUrl}/api/queries`,
    JSON.stringify(body),
    {
      headers: {
        'Content-Type': 'application/json',
        [csrf.headerName]: csrf.token,
      },
    }
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
  const csrf = login();
  const executionId = runQuery(csrf);
  if (!executionId) {
    sleep(0.5);
    return;
  }

  const completed = waitForCompletion(executionId);
  if (completed) {
    let nextPageToken = null;
    for (let page = 0; page < Number(__ENV.MAX_RESULT_PAGES || 3); page += 1) {
      const params = { pageSize: String(pageSize) };
      if (nextPageToken) {
        params.pageToken = nextPageToken;
      }
      const resultsRes = http.get(`${baseUrl}/api/queries/${executionId}/results`, { params });
      if (!check(resultsRes, { 'results ok': (r) => r.status === 200 })) {
        break;
      }
      nextPageToken = resultsRes.json('nextPageToken');
      if (!nextPageToken) {
        break;
      }
    }

    if (exportCsv) {
      const exportRes = http.get(`${baseUrl}/api/queries/${executionId}/export.csv?headers=true`);
      check(exportRes, { 'export ok or forbidden': (r) => r.status === 200 || r.status === 403 });
    }
  }

  sleep(0.2);
}
