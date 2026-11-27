// ReCore OTA gate: opt-in requests for the test and beta branches, approved by the developer.
// The release branch needs no approval, so it never touches this worker.
//
// Bindings (Cloudflare dashboard > Workers > Settings):
//   KV namespace  GATE          stores one record per phone
//   Secret        ADMIN_TOKEN   long random string, only you know it
//   Secret        NTFY_TOPIC    private topic name for push notifications (install the ntfy app and subscribe)
//   Variable      NTFY_SERVER   optional, default https://ntfy.sh

const BRANCHES = ['test', 'beta'];
const ID_RE = /^[a-f0-9]{16,64}$/;
const NAME_RE = /^[A-Za-z0-9_.-]{1,40}$/;

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

function ascii(s, max = 80) {
  return String(s ?? '').replace(/[^\x20-\x7e]/g, '?').slice(0, max);
}

function sameToken(a, b) {
  a = String(a ?? ''); b = String(b ?? '');
  if (a.length !== b.length || a.length === 0) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function load(env, id) {
  const raw = await env.GATE.get('dev:' + id);
  return raw ? JSON.parse(raw) : { id, branches: {} };
}

async function save(env, rec) {
  await env.GATE.put('dev:' + rec.id, JSON.stringify(rec));
}

async function limited(env, key, max) {
  const day = new Date().toISOString().slice(0, 10);
  const k = 'rl:' + key + ':' + day;
  const n = parseInt((await env.GATE.get(k)) || '0', 10);
  if (n >= max) return true;
  await env.GATE.put(k, String(n + 1), { expirationTtl: 172800 });
  return false;
}

async function notifyDev(env, origin, rec, branch, note) {
  if (!env.NTFY_TOPIC) return;
  const base = (env.NTFY_SERVER || 'https://ntfy.sh').replace(/\/$/, '');
  const link = (action) =>
    `${origin}/${action}?id=${rec.id}&branch=${branch}&token=${encodeURIComponent(env.ADMIN_TOKEN)}`;
  const body = `${ascii(rec.device)} (${ascii(rec.model)}) build ${ascii(rec.build)}\nid ${rec.id.slice(0, 8)}${note ? '\nnote: ' + ascii(note, 120) : ''}`;
  await fetch(`${base}/${env.NTFY_TOPIC}`, {
    method: 'POST',
    headers: {
      Title: `ReCore ${branch} access request`,
      Priority: 'high',
      Tags: 'bell',
      Actions: `http, Approve, ${link('approve')}, method=POST, clear=true; http, Deny, ${link('deny')}, method=POST, clear=true`,
    },
    body,
  });
}

async function request(req, env, url) {
  let b;
  try { b = await req.json(); } catch { return json({ error: 'bad json' }, 400); }
  if (!ID_RE.test(b.id || '')) return json({ error: 'bad id' }, 400);
  if (!BRANCHES.includes(b.branch)) return json({ error: 'this branch needs no approval' }, 400);
  const ip = req.headers.get('cf-connecting-ip') || 'x';
  if (await limited(env, 'id:' + b.id, 5) || await limited(env, 'ip:' + ip, 30)) return json({ error: 'too many requests' }, 429);

  const rec = await load(env, b.id);
  rec.device = NAME_RE.test(b.device || '') ? b.device : 'unknown';
  rec.model = NAME_RE.test(b.model || '') ? b.model : 'unknown';
  rec.build = Number.isInteger(b.build) ? b.build : 0;
  const cur = rec.branches[b.branch];
  if (cur && (cur.state === 'approved' || cur.state === 'denied' || cur.state === 'pending')) {
    return json({ state: cur.state });
  }
  rec.branches[b.branch] = { state: 'pending', requested: new Date().toISOString() };
  await save(env, rec);
  await notifyDev(env, url.origin, rec, b.branch, b.note);
  return json({ state: 'pending' });
}

async function status(url, env) {
  const id = url.searchParams.get('id') || '';
  if (!ID_RE.test(id)) return json({ error: 'bad id' }, 400);
  const rec = await load(env, id);
  const out = { approved: [], pending: [], denied: [] };
  for (const [name, v] of Object.entries(rec.branches)) {
    if (out[v.state]) out[v.state].push(name);
    else if (v.state === 'approved') out.approved.push(name);
  }
  return json(out);
}

async function admin(req, env, url, action) {
  if (!sameToken(url.searchParams.get('token'), env.ADMIN_TOKEN)) return json({ error: 'unauthorized' }, 401);
  const id = url.searchParams.get('id') || '';
  const branch = url.searchParams.get('branch') || '';
  if (!ID_RE.test(id) || !BRANCHES.includes(branch)) return json({ error: 'bad request' }, 400);
  const rec = await load(env, id);
  if (!rec.branches[branch]) return json({ error: 'no such request' }, 404);
  const state = action === 'approve' ? 'approved' : action === 'deny' ? 'denied' : 'revoked';
  if (state === 'revoked') delete rec.branches[branch];
  else rec.branches[branch] = { ...rec.branches[branch], state, updated: new Date().toISOString() };
  await save(env, rec);
  return json({ ok: true, id, branch, state });
}

async function list(url, env) {
  if (!sameToken(url.searchParams.get('token'), env.ADMIN_TOKEN)) return json({ error: 'unauthorized' }, 401);
  const out = [];
  const keys = await env.GATE.list({ prefix: 'dev:' });
  for (const k of keys.keys) out.push(JSON.parse(await env.GATE.get(k.name)));
  return json(out);
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      if (req.method === 'POST' && url.pathname === '/request') return await request(req, env, url);
      if (req.method === 'GET' && url.pathname === '/status') return await status(url, env);
      if (['/approve', '/deny', '/revoke'].includes(url.pathname) && ['GET', 'POST'].includes(req.method)) {
        return await admin(req, env, url, url.pathname.slice(1));
      }
      if (req.method === 'GET' && url.pathname === '/list') return await list(url, env);
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: 'server error' }, 500);
    }
  },
};
