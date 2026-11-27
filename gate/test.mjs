import worker from './worker.js';
const store = new Map();
const KV = {
  async get(k) { return store.has(k) ? store.get(k) : null; },
  async put(k, v) { store.set(k, v); },
  async list({ prefix }) { return { keys: [...store.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })) }; },
};
const env = { GATE: KV, ADMIN_TOKEN: 'secret-admin-token-123', NTFY_TOPIC: 'my-private-topic' };
const pushes = [];
globalThis.fetch = async (u, o) => { pushes.push({ u, o }); return new Response('ok'); };
const call = (m, p, body) => worker.fetch(new Request('https://gate.example' + p, { method: m, body: body ? JSON.stringify(body) : undefined, headers: { 'cf-connecting-ip': '1.2.3.4' } }), env).then(async r => [r.status, await r.json()]);
const id = 'a'.repeat(32);
const ok = (name, cond) => { console.log((cond ? 'PASS ' : 'FAIL ') + name); if (!cond) process.exitCode = 1; };

let [s, j] = await call('POST', '/request', { id, device: 'a14x', model: 'SM-A146B', branch: 'beta', build: 44 });
ok('request beta -> pending', s === 200 && j.state === 'pending');
ok('developer got one push with Approve/Deny', pushes.length === 1 && pushes[0].o.headers.Actions.includes('Approve') && pushes[0].o.headers.Actions.includes('/approve?id=' + id));
[s, j] = await call('POST', '/request', { id, device: 'a14x', model: 'SM-A146B', branch: 'beta', build: 44 });
ok('asking again does not send another push', j.state === 'pending' && pushes.length === 1);
[s, j] = await call('POST', '/request', { id, device: 'a14x', branch: 'release' });
ok('release needs no approval (400)', s === 400);
[s, j] = await call('POST', '/request', { id: 'zz', branch: 'beta' });
ok('bad id rejected', s === 400);
[s, j] = await call('GET', '/status?id=' + id);
ok('status shows pending', j.pending.includes('beta') && j.approved.length === 0);
[s, j] = await call('POST', `/approve?id=${id}&branch=beta&token=wrong`);
ok('approve with wrong token -> 401', s === 401);
[s, j] = await call('POST', `/approve?id=${id}&branch=beta&token=secret-admin-token-123`);
ok('approve with the right token', s === 200 && j.state === 'approved');
[s, j] = await call('GET', '/status?id=' + id);
ok('status shows approved', j.approved.includes('beta') && j.pending.length === 0);
[s, j] = await call('POST', '/request', { id, device: 'a14x', branch: 'beta' });
ok('request after approval just returns approved', j.state === 'approved' && pushes.length === 1);
[s, j] = await call('POST', `/revoke?id=${id}&branch=beta&token=secret-admin-token-123`);
[s, j] = await call('GET', '/status?id=' + id);
ok('revoke removes access', j.approved.length === 0);
[s, j] = await call('POST', '/request', { id, device: 'a14x', model: 'SM-A146B', branch: 'test' });
[s, j] = await call('POST', `/deny?id=${id}&branch=test&token=secret-admin-token-123`);
[s, j] = await call('GET', '/status?id=' + id);
ok('deny is recorded', j.denied.includes('test'));
const id2 = 'b'.repeat(32); let last = 0;
for (let i = 0; i < 7; i++) { [last] = await call('POST', '/request', { id: id2, device: 'x', branch: i % 2 ? 'beta' : 'test' }); }
ok('rate limit stops spamming (429)', last === 429);
[s, j] = await call('GET', '/list?token=secret-admin-token-123');
ok('admin list works', s === 200 && Array.isArray(j) && j.length >= 2);
