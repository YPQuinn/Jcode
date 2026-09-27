const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'takeover.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'browser example must contain its inline script');

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function element() {
  const listeners = new Map();
  return {
    value: '', textContent: '', children: [],
    addEventListener(name, callback) { listeners.set(name, callback); },
    click() { return listeners.get('click')?.(); },
    replaceChildren() { this.children = []; },
    append(child) { this.children.push(child); }
  };
}

function page(fetch) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, element());
      return elements.get(id);
    },
    createElement: element
  };
  const context = vm.createContext({
    document, fetch, location: { search: '' }, URLSearchParams,
    AbortController, TextDecoder, setTimeout, clearTimeout
  });
  vm.runInContext(script, context);
  const field = id => document.getElementById(id);
  field('endpoint').value = 'http://127.0.0.1:8787';
  field('token').value = 'test-token';
  field('session').value = 'session-a';
  return { field, connect: () => field('connect').click(),
    disconnect: () => field('disconnect').click() };
}

function snapshot(sessionId = 'session-a', seq = 0) {
  return {
    schemaVersion: 1, sessionId, cursor: { epoch: 'epoch-1', seq },
    leafId: null, runs: [{ runId: 'run-1', status: 'RUNNING' }],
    inputs: [], approvals: [], messages: [], tools: []
  };
}

function jsonResponse(body, status = 200) {
  return {
    ok: status < 400, status,
    json: async () => body,
    text: async () => JSON.stringify(body)
  };
}

function streamResponse(text) {
  return {
    ok: true, status: 200,
    body: new ReadableStream({
      start(controller) {
        if (text) controller.enqueue(new TextEncoder().encode(text));
        controller.close();
      }
    })
  };
}

async function until(predicate) {
  const deadline = Date.now() + 3000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('timed out waiting for browser example');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

test('a rejected SSE fetch reconnects with the last applied cursor', async () => {
  const cursors = [];
  const browser = page(async (url, options) => {
    if (url.endsWith('/snapshot')) return jsonResponse(snapshot());
    cursors.push(options.headers['Last-Event-ID']);
    if (cursors.length === 1) throw new TypeError('offline');
    return streamResponse('');
  });
  await browser.connect();
  await until(() => cursors.length === 2);
  assert.deepEqual(cursors, ['epoch-1:0', 'epoch-1:0']);
  browser.disconnect();
  await new Promise(resolve => setTimeout(resolve, 350));
  assert.equal(cursors.length, 2);
});

test('a failed SSE reader reconnects', async () => {
  let attempts = 0;
  const browser = page(async url => {
    if (url.endsWith('/snapshot')) return jsonResponse(snapshot());
    attempts++;
    if (attempts === 1) {
      return {
        ok: true, body: new ReadableStream({
          start(controller) { controller.error(new Error('read failed')); }
        })
      };
    }
    return streamResponse('');
  });
  await browser.connect();
  await until(() => attempts === 2);
  browser.disconnect();
});

for (const code of ['CURSOR_EXPIRED', 'EPOCH_CHANGED']) {
  test(`${code} fetches a new snapshot before resubscribing`, async () => {
    let snapshots = 0;
    const cursors = [];
    const browser = page(async (url, options) => {
      if (url.endsWith('/snapshot')) return jsonResponse(snapshot('session-a', ++snapshots));
      cursors.push(options.headers['Last-Event-ID']);
      if (cursors.length === 1) return jsonResponse({ code }, 409);
      return streamResponse('');
    });
    await browser.connect();
    await until(() => cursors.length === 2);
    assert.equal(snapshots, 2);
    assert.deepEqual(cursors, ['epoch-1:1', 'epoch-1:2']);
    browser.disconnect();
  });
}

test('a terminal event remains visible when the session closes before another snapshot', async () => {
  let snapshots = 0;
  const completed = {
    schemaVersion: 1, sessionId: 'session-a', runId: 'run-1',
    cursor: { epoch: 'epoch-1', seq: 1 }, type: 'RUN_CHANGED',
    data: { runId: 'run-1', status: 'COMPLETED' }
  };
  const frames = `id: epoch-1:1\nevent: session.event\ndata: ${JSON.stringify(completed)}\n\n`
    + 'event: stream.control\ndata: {"code":"SESSION_CLOSED","action":"stop"}\n\n';
  const browser = page(async url => {
    if (url.endsWith('/snapshot')) {
      snapshots++;
      return snapshots === 1 ? jsonResponse(snapshot()) : jsonResponse({ code: 'NOT_FOUND' }, 404);
    }
    return streamResponse(frames);
  });
  await browser.connect();
  await until(() => browser.field('status').textContent === '会话事件流已关闭');
  assert.equal(snapshots, 1);
  assert.equal(JSON.parse(browser.field('snapshot').textContent).runs[0].status, 'COMPLETED');
});

test('an approval result updates locally without another snapshot', async () => {
  let snapshots = 0;
  const approval = {
    approvalId: 'approval-1', toolCallId: 'tool-1', requestDigest: 'digest', status: 'ALLOWED'
  };
  const changed = {
    schemaVersion: 1, sessionId: 'session-a', runId: 'run-1',
    cursor: { epoch: 'epoch-1', seq: 1 }, type: 'APPROVAL_CHANGED', data: approval
  };
  const frames = `id: epoch-1:1\nevent: session.event\ndata: ${JSON.stringify(changed)}\n\n`
    + 'event: stream.control\ndata: {"code":"SESSION_CLOSED","action":"stop"}\n\n';
  const browser = page(async url => {
    if (url.endsWith('/snapshot')) {
      snapshots++;
      const initial = snapshot();
      initial.approvals = [{ ...approval, status: 'PENDING' }];
      return jsonResponse(initial);
    }
    return streamResponse(frames);
  });
  await browser.connect();
  await until(() => browser.field('status').textContent === '会话事件流已关闭');
  assert.equal(snapshots, 1);
  assert.equal(browser.field('approvals').children.length, 0);
  assert.equal(JSON.parse(browser.field('snapshot').textContent).approvals[0].status, 'ALLOWED');
});

test('a replaced connection cannot publish an older snapshot or cursor', async () => {
  const oldSnapshot = deferred();
  const cursors = [];
  const browser = page(async (url, options) => {
    if (url.endsWith('/session-a/snapshot')) return oldSnapshot.promise;
    if (url.endsWith('/session-b/snapshot')) return jsonResponse(snapshot('session-b', 7));
    cursors.push(options.headers['Last-Event-ID']);
    return streamResponse('');
  });
  const first = browser.connect();
  browser.field('session').value = 'session-b';
  await browser.connect();
  oldSnapshot.resolve(jsonResponse(snapshot('session-a', 99)));
  await first;
  await until(() => cursors.length === 1);
  const displayed = JSON.parse(browser.field('snapshot').textContent);
  assert.equal(displayed.sessionId, 'session-b');
  assert.equal(displayed.cursor.seq, 7);
  assert.deepEqual(cursors, ['epoch-1:7']);
  browser.disconnect();
});

test('disconnect during a failed request stops retrying', async () => {
  let attempts = 0;
  const browser = page(async url => {
    if (url.endsWith('/snapshot')) return jsonResponse(snapshot());
    attempts++;
    throw new TypeError('offline');
  });
  await browser.connect();
  await until(() => attempts === 1);
  browser.disconnect();
  await new Promise(resolve => setTimeout(resolve, 350));
  assert.equal(attempts, 1);
});

for (const status of [401, 403]) {
  test(`SSE HTTP ${status} stops instead of retrying`, async () => {
    let attempts = 0;
    const browser = page(async url => {
      if (url.endsWith('/snapshot')) return jsonResponse(snapshot());
      attempts++;
      return jsonResponse({ code: 'UNAUTHORIZED' }, status);
    });
    await browser.connect();
    await until(() => browser.field('status').textContent.includes(`SSE HTTP ${status}`));
    await new Promise(resolve => setTimeout(resolve, 350));
    assert.equal(attempts, 1);
    browser.disconnect();
  });
}
