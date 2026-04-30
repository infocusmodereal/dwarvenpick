import assert from 'node:assert/strict';
import test from 'node:test';
import { DwarvenpickClient, splitSetCookieHeader } from '../src/client.js';

test('splitSetCookieHeader keeps expires commas inside one cookie', () => {
  const cookies = splitSetCookieHeader('a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/, b=2; Path=/');

  assert.deepEqual(cookies, ['a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/', 'b=2; Path=/']);
});

test('login posts local credentials with CSRF cookie and header', async () => {
  const requests = [];
  const client = new DwarvenpickClient({
    baseUrl: 'http://dwarvenpick.local',
    fetchImpl: async (url, init) => {
      requests.push({ url, init });
      if (url.endsWith('/api/auth/csrf')) {
        return jsonResponse({
          body: { token: 'csrf-token', headerName: 'X-XSRF-TOKEN', parameterName: '_csrf' },
          setCookies: ['XSRF-TOKEN=csrf-token; Path=/'],
        });
      }
      return jsonResponse({
        body: { username: 'admin', displayName: 'Admin', email: null, provider: 'LOCAL' },
        setCookies: ['JSESSIONID=session-id; Path=/'],
      });
    },
  });

  const user = await client.login({ authMode: 'local', username: 'admin', password: 'secret' });

  assert.equal(user.username, 'admin');
  assert.equal(requests[1].url, 'http://dwarvenpick.local/api/auth/login');
  assert.equal(requests[1].init.headers['X-XSRF-TOKEN'], 'csrf-token');
  assert.match(requests[1].init.headers.Cookie, /XSRF-TOKEN=csrf-token/);
  assert.deepEqual(JSON.parse(requests[1].init.body), { username: 'admin', password: 'secret' });
});

test('login posts LDAP credentials to the LDAP endpoint', async () => {
  const paths = [];
  const client = new DwarvenpickClient({
    baseUrl: 'http://dwarvenpick.local',
    fetchImpl: async (url) => {
      paths.push(new URL(url).pathname);
      if (url.endsWith('/api/auth/csrf')) {
        return jsonResponse({
          body: { token: 'csrf-token', headerName: 'X-XSRF-TOKEN', parameterName: '_csrf' },
          setCookies: ['XSRF-TOKEN=csrf-token; Path=/'],
        });
      }
      return jsonResponse({
        body: { username: 'ivan.torres', displayName: 'Ivan Torres', email: null, provider: 'LDAP' },
      });
    },
  });

  await client.login({ authMode: 'ldap', username: 'ivan.torres', password: 'secret' });

  assert.deepEqual(paths, ['/api/auth/csrf', '/api/auth/ldap/login']);
});

function jsonResponse({ body, setCookies = [] }) {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: {
      get(name) {
        return name.toLowerCase() === 'content-type' ? 'application/json' : null;
      },
      getSetCookie() {
        return setCookies;
      },
    },
    async text() {
      return JSON.stringify(body);
    },
  };
}
