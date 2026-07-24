export class DwarvenpickClient {
  constructor({ baseUrl, fetchImpl = fetch }) {
    if (!baseUrl) {
      throw new Error('A dwarvenpick URL is required.');
    }
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.fetchImpl = fetchImpl;
    this.cookieJar = new CookieJar();
    this.csrfToken = null;
  }

  async resolveAuthMode(preferredMode) {
    const normalizedMode = (preferredMode || 'auto').toLowerCase();
    if (normalizedMode !== 'auto') {
      if (!['local', 'ldap'].includes(normalizedMode)) {
        throw new Error('The CLI supports password login with local or ldap auth. OIDC is browser-based.');
      }
      return normalizedMode;
    }

    const methods = await this.request('/api/auth/methods');
    if (methods.methods?.includes('ldap')) {
      return 'ldap';
    }
    if (methods.methods?.includes('local')) {
      return 'local';
    }
    throw new Error('No CLI-compatible auth method is enabled. Enable local or LDAP auth.');
  }

  async login({ authMode, username, password }) {
    await this.ensureCsrfToken();
    const endpoint = authMode === 'ldap' ? '/api/auth/ldap/login' : '/api/auth/login';
    return this.request(endpoint, {
      method: 'POST',
      csrf: true,
      body: { username, password },
    });
  }

  async currentUser() {
    return this.request('/api/auth/me');
  }

  async listConnections() {
    return this.request('/api/datasources');
  }

  async submitQuery(request) {
    await this.ensureCsrfToken();
    return this.request('/api/queries', {
      method: 'POST',
      csrf: true,
      body: request,
    });
  }

  async queryStatus(executionId) {
    return this.request(`/api/queries/${encodeURIComponent(executionId)}`);
  }

  async cancelQuery(executionId) {
    await this.ensureCsrfToken();
    return this.request(`/api/queries/${encodeURIComponent(executionId)}/cancel`, {
      method: 'POST',
      csrf: true,
    });
  }

  async queryResults(executionId, { pageSize, pageToken } = {}) {
    const params = new URLSearchParams();
    if (pageSize) {
      params.set('pageSize', String(pageSize));
    }
    if (pageToken) {
      params.set('pageToken', pageToken);
    }
    const query = params.toString();
    return this.request(`/api/queries/${encodeURIComponent(executionId)}/results${query ? `?${query}` : ''}`);
  }

  async exportCsv(executionId, { headers = true } = {}) {
    const params = new URLSearchParams();
    params.set('headers', headers ? 'true' : 'false');
    const requestHeaders = {
      Accept: 'text/csv',
    };
    const cookieHeader = this.cookieJar.header();
    if (cookieHeader) {
      requestHeaders.Cookie = cookieHeader;
    }

    const response = await this.fetchImpl(
      `${this.baseUrl}/api/queries/${encodeURIComponent(executionId)}/export.csv?${params.toString()}`,
      {
        method: 'GET',
        headers: requestHeaders,
      },
    );
    this.cookieJar.storeFrom(response.headers);

    if (!response.ok) {
      const responseBody = await readResponseBody(response);
      const message = responseErrorMessage(responseBody, response.statusText, response.status);
      throw new HttpError(message, response.status, responseBody);
    }
    return response;
  }

  async ensureCsrfToken() {
    if (!this.csrfToken) {
      this.csrfToken = await this.request('/api/auth/csrf');
    }
    return this.csrfToken;
  }

  async request(path, { method = 'GET', body, csrf = false } = {}) {
    const headers = {
      Accept: 'application/json',
    };

    const cookieHeader = this.cookieJar.header();
    if (cookieHeader) {
      headers.Cookie = cookieHeader;
    }

    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    if (csrf) {
      const token = await this.ensureCsrfToken();
      headers[token.headerName] = token.token;
    }

    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    this.cookieJar.storeFrom(response.headers);

    const responseBody = await readResponseBody(response);

    if (!response.ok) {
      const message = responseErrorMessage(responseBody, response.statusText, response.status);
      throw new HttpError(message, response.status, responseBody);
    }

    return responseBody;
  }
}

async function readResponseBody(response) {
  const responseText = await response.text();
  const contentType = response.headers.get('content-type') || '';
  return contentType.includes('application/json') && responseText ? JSON.parse(responseText) : responseText;
}

function responseErrorMessage(responseBody, statusText, status) {
  if (responseBody && typeof responseBody === 'object') {
    return responseBody.error || responseBody.message || statusText || `HTTP ${status}`;
  }
  if (typeof responseBody === 'string' && responseBody.trim()) {
    const lines = responseBody.trim().split(/\r?\n/);
    if (lines[0]?.trim().toLowerCase() === 'error' && lines[1]) {
      return lines
        .slice(1)
        .join('\n')
        .replace(/^"|"$/g, '')
        .replaceAll('""', '"');
    }
    return responseBody.trim();
  }
  return statusText || `HTTP ${status}`;
}

export class HttpError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.body = body;
  }
}

export class CookieJar {
  constructor() {
    this.cookies = new Map();
  }

  storeFrom(headers) {
    const setCookies =
      typeof headers.getSetCookie === 'function'
        ? headers.getSetCookie()
        : splitSetCookieHeader(headers.get('set-cookie') || '');

    for (const setCookie of setCookies) {
      const firstSegment = setCookie.split(';', 1)[0];
      const separatorIndex = firstSegment.indexOf('=');
      if (separatorIndex <= 0) {
        continue;
      }
      const name = firstSegment.slice(0, separatorIndex).trim();
      const value = firstSegment.slice(separatorIndex + 1).trim();
      if (!value) {
        this.cookies.delete(name);
      } else {
        this.cookies.set(name, value);
      }
    }
  }

  header() {
    return [...this.cookies.entries()].map(([name, value]) => `${name}=${value}`).join('; ');
  }
}

export function splitSetCookieHeader(value) {
  if (!value) {
    return [];
  }

  const cookies = [];
  let startIndex = 0;
  let insideExpires = false;
  const lowerValue = value.toLowerCase();

  for (let index = 0; index < value.length; index += 1) {
    if (lowerValue.slice(index, index + 8) === 'expires=') {
      insideExpires = true;
    }
    if (insideExpires && value[index] === ';') {
      insideExpires = false;
    }
    if (!insideExpires && value[index] === ',') {
      cookies.push(value.slice(startIndex, index).trim());
      startIndex = index + 1;
    }
  }

  cookies.push(value.slice(startIndex).trim());
  return cookies.filter(Boolean);
}
