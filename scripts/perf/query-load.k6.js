import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:3000';
const username = __ENV.DWARVENPICK_USER || 'admin';
const password = __ENV.DWARVENPICK_PASSWORD || 'Admin1234!';
const authMode = (__ENV.DWARVENPICK_AUTH || 'local').toLowerCase();
const datasourceId = __ENV.DATASOURCE_ID || 'postgresql-core';
const credentialProfile = __ENV.CREDENTIAL_PROFILE || '';
const justification = __ENV.JUSTIFICATION || '';
const pageSize = Number(__ENV.PAGE_SIZE || 100);
const exportCsv = (__ENV.EXPORT_CSV || 'true').toLowerCase() !== 'false';
const captureMetrics = (__ENV.CAPTURE_APP_METRICS || 'true').toLowerCase() !== 'false';
const metricsPath = __ENV.METRICS_PATH || '/actuator/prometheus';
const perfProfile = (__ENV.PERF_PROFILE || 'smoke').toLowerCase();
const queryMix = (__ENV.SQL_MIX || __ENV.SQL || 'SELECT 1 AS value;')
  .split(/\n---+\n/)
  .map((sql) => sql.trim())
  .filter(Boolean);
const backendMetricNames = [
  'dwarvenpick_query_active',
  'dwarvenpick_query_buffered_bytes',
  'dwarvenpick_query_buffered_budget_bytes',
  'dwarvenpick_query_execution_total',
  'dwarvenpick_query_duration_seconds',
  'dwarvenpick_query_export_attempts_total',
  'dwarvenpick_pool_active',
  'dwarvenpick_pool_total',
  'dwarvenpick_pool',
];

const profiles = {
  smoke: {
    vus: 2,
    duration: '30s',
    thresholds: {
      checks: ['rate>0.98'],
      http_req_failed: ['rate<0.02'],
      http_req_duration: ['p(95)<2000', 'p(99)<5000'],
      dwarvenpick_k6_query_completed: ['rate>0.95'],
      dwarvenpick_k6_query_submit_duration: ['p(95)<1500'],
      dwarvenpick_k6_query_completion_duration: ['p(95)<5000', 'p(99)<8000'],
      dwarvenpick_k6_result_page_duration: ['p(95)<1500'],
      dwarvenpick_k6_csv_export_duration: ['p(95)<5000'],
    },
  },
  regression: {
    vus: 10,
    duration: '2m',
    thresholds: {
      checks: ['rate>0.97'],
      http_req_failed: ['rate<0.03'],
      http_req_duration: ['p(95)<3000', 'p(99)<8000'],
      dwarvenpick_k6_query_completed: ['rate>0.95'],
      dwarvenpick_k6_query_submit_duration: ['p(95)<2000'],
      dwarvenpick_k6_query_completion_duration: ['p(95)<8000', 'p(99)<15000'],
      dwarvenpick_k6_result_page_duration: ['p(95)<2000'],
      dwarvenpick_k6_csv_export_duration: ['p(95)<8000'],
    },
  },
};
const selectedProfile = profiles[perfProfile] || profiles.smoke;
const selectedThresholds = { ...selectedProfile.thresholds };
if (!exportCsv) {
  delete selectedThresholds.dwarvenpick_k6_csv_export_duration;
}

const queryCompleted = new Rate('dwarvenpick_k6_query_completed');
const querySubmitDuration = new Trend('dwarvenpick_k6_query_submit_duration');
const queryCompletionDuration = new Trend('dwarvenpick_k6_query_completion_duration');
const resultPageDuration = new Trend('dwarvenpick_k6_result_page_duration');
const csvExportDuration = new Trend('dwarvenpick_k6_csv_export_duration');
const resultPagesFetched = new Counter('dwarvenpick_k6_result_pages_fetched');
const csvExportsAttempted = new Counter('dwarvenpick_k6_csv_exports_attempted');

export const options = {
  vus: Number(__ENV.VUS || selectedProfile.vus),
  duration: __ENV.DURATION || selectedProfile.duration,
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: selectedThresholds,
};

function fetchAppMetrics(label) {
  if (!captureMetrics) {
    return;
  }

  const metricsRes = http.get(`${baseUrl}${metricsPath}`);
  check(metricsRes, {
    [`app metrics ${label} available or intentionally guarded`]: (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });

  if (metricsRes.status !== 200) {
    console.log(`[app-metrics:${label}] status=${metricsRes.status}; no metric snapshot captured`);
    return;
  }

  const presentMetrics = backendMetricNames.filter((metricName) =>
    new RegExp(`^${metricName}(?:\\{|\\s)`, 'm').test(metricsRes.body)
  );
  const eagerMetrics = [
    'dwarvenpick_query_active',
    'dwarvenpick_query_buffered_bytes',
    'dwarvenpick_query_buffered_budget_bytes',
  ];
  check(presentMetrics, {
    [`app metrics ${label} include expected signals`]: (names) => {
      if (label === 'before') {
        return eagerMetrics.every((metricName) => names.includes(metricName));
      }
      return (
        eagerMetrics.every((metricName) => names.includes(metricName)) &&
        names.includes('dwarvenpick_query_execution_total') &&
        (names.includes('dwarvenpick_pool_total') || names.includes('dwarvenpick_pool'))
      );
    },
  });
  console.log(`[app-metrics:${label}] ${presentMetrics.join(', ')}`);
}

export function setup() {
  fetchAppMetrics('before');
}

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

function queryString(params) {
  return Object.entries(params)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');
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
  querySubmitDuration.add(submitRes.timings.duration);

  if (!check(submitRes, { 'submit query': (r) => r.status === 200 })) {
    return null;
  }
  return submitRes.json('executionId');
}

function waitForCompletion(executionId) {
  const startedAt = Date.now();
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const statusRes = http.get(`${baseUrl}/api/queries/${executionId}`);
    if (!check(statusRes, { 'status available': (r) => r.status === 200 })) {
      return null;
    }

    const status = statusRes.json('status');
    if (status === 'SUCCEEDED') {
      queryCompletionDuration.add(Date.now() - startedAt);
      return true;
    }
    if (status === 'FAILED' || status === 'CANCELED') {
      queryCompletionDuration.add(Date.now() - startedAt);
      return false;
    }
    sleep(0.2);
  }
  queryCompletionDuration.add(Date.now() - startedAt);
  return false;
}

export default function () {
  const csrf = login();
  const executionId = runQuery(csrf);
  if (!executionId) {
    queryCompleted.add(false);
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
      const resultsRes = http.get(`${baseUrl}/api/queries/${executionId}/results?${queryString(params)}`);
      if (!check(resultsRes, { 'results ok': (r) => r.status === 200 })) {
        break;
      }
      resultPageDuration.add(resultsRes.timings.duration);
      resultPagesFetched.add(1);
      nextPageToken = resultsRes.json('nextPageToken');
      if (!nextPageToken) {
        break;
      }
    }

    if (exportCsv) {
      const exportRes = http.get(`${baseUrl}/api/queries/${executionId}/export.csv?headers=true`);
      csvExportDuration.add(exportRes.timings.duration);
      csvExportsAttempted.add(1);
      check(exportRes, { 'export ok or forbidden': (r) => r.status === 200 || r.status === 403 });
    }
  }

  queryCompleted.add(completed === true);
  sleep(0.2);
}

export function teardown() {
  fetchAppMetrics('after');
}

const summaryMetricNames = [
  'checks',
  'http_req_failed',
  'http_req_duration',
  'http_reqs',
  'iterations',
  'dwarvenpick_k6_query_completed',
  'dwarvenpick_k6_query_submit_duration',
  'dwarvenpick_k6_query_completion_duration',
  'dwarvenpick_k6_result_page_duration',
  'dwarvenpick_k6_csv_export_duration',
  'dwarvenpick_k6_result_pages_fetched',
  'dwarvenpick_k6_csv_exports_attempted',
];

function compactMetric(metric) {
  const allowedValueKeys = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'rate', 'count'];
  const values = {};
  for (const key of allowedValueKeys) {
    if (Number.isFinite(metric.values?.[key])) {
      values[key] = metric.values[key];
    }
  }
  const thresholds = {};
  for (const [name, result] of Object.entries(metric.thresholds || {})) {
    thresholds[name] = result.ok === true;
  }
  return { type: metric.type, contains: metric.contains, values, thresholds };
}

export function handleSummary(data) {
  const metrics = {};
  for (const name of summaryMetricNames) {
    if (data.metrics[name]) {
      metrics[name] = compactMetric(data.metrics[name]);
    }
  }
  const outputPath = __ENV.K6_SUMMARY_PATH || 'build/reports/perf/query-smoke-summary.json';
  return {
    [outputPath]: JSON.stringify(
      {
        schemaVersion: 1,
        run: {
          profile: perfProfile,
          vus: options.vus,
          duration: options.duration,
        },
        metrics,
      },
      null,
      2
    ),
  };
}
