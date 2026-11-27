# ReCore OTA gate (Cloudflare Worker)

Lets testers ask for access to the beta and test branches and lets you approve from a push notification.
The release branch never uses this. The code is public on purpose; the secrets live only in Cloudflare.

## Set up once (about 10 minutes, free)

1. Cloudflare dashboard > Storage & Databases > KV > Create a namespace. Copy its id into `wrangler.toml`
   (replace PASTE_KV_NAMESPACE_ID) and commit.
2. Deploy, either way:
   - Automatic: in this repo add the Actions secrets CLOUDFLARE_API_TOKEN (create it from the "Edit Cloudflare Workers"
     template) and CLOUDFLARE_ACCOUNT_ID, then run Actions > Deploy gate. Every later push that changes `gate/` deploys again.
   - By hand: Cloudflare > Workers & Pages > Create > Worker, paste `worker.js`, deploy, then Settings > Bindings >
     add a KV namespace with variable name `GATE`.
3. Cloudflare > your Worker > Settings > Variables and Secrets > add two secrets:
   - `ADMIN_TOKEN`: a long random string (40+ characters). Only you know it.
   - `NTFY_TOPIC`: a hard-to-guess topic name, for example `recore-k29x8f3q7m`.
4. On your phone install the ntfy app and subscribe to that topic. Requests arrive there with Approve and Deny buttons.
5. In this repo > Settings > Secrets and variables > Actions > Variables, add `GATE_URL` = your worker address
   (for example `https://recore-gate.yourname.workers.dev`, no trailing slash), then run Build updater app again.

## Daily use

- A tester taps Request access in ReCore Updater. You get a push; tap Approve or Deny.
- See everyone: open `https://<worker>/list?token=<ADMIN_TOKEN>` in a browser.
- Remove someone: send a POST to `https://<worker>/revoke?id=<id>&branch=beta&token=<ADMIN_TOKEN>`.
- Approve your own phone the same way, or find its id in /list.

## Limits

- Soft gate: the app only offers branches you approved, but the update files are public links on GitHub.
- A phone is identified by a hash of its Android ID, so a data wipe needs a new request.
- Requests are limited per phone (5 a day) and per address (30 a day).

`npm test` (or `node test.mjs`) tests the worker logic with a fake store.
