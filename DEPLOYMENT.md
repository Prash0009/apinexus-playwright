# Deploying WireMock Stubs to the Internet

By default ApiNexus runs WireMock **embedded inside the JVM** — it starts on
`localhost:8089` and stops when the JVM exits. That is great for isolated
developer machines but unusable for:

- CI pipelines that need a stable mock URL
- Teams where multiple developers share stubs
- Mobile apps or frontend devs who need to hit the mock from a real device

This guide covers **three ways** to make the stubs available from anywhere:

| Option | Cost | Setup time | Best for |
|---|---|---|---|
| **A — WireMock Cloud** | Free tier (50k req/month) | 5 min | Quickest start, no infrastructure |
| **B — Fly.io (self-hosted Docker)** | Free tier (3 VMs) | 10 min | Full control, team-shared, persistent |
| **C — Docker Compose (VPS / home server)** | Your server costs | 5 min | On-premises, corporate proxy |

Once deployed, a **single line change** in `config.properties` points the
whole test suite at the remote server — no test code changes at all.

---

## How the switch works

`config.properties` has a `mock.mode` key:

```properties
# local  = embedded WireMock JVM server (default, offline-safe)
# remote = register stubs via WireMock Admin REST API
mock.mode=local
mock.server.port=8089

mock.remote.url=https://YOUR-ID.wiremockapi.cloud
mock.remote.api.key=YOUR-API-KEY-HERE
```

`BaseApiTest.suiteSetUp()` reads this and picks the right implementation:

```
mock.mode=local   →  MockServerManager       (embedded, starts/stops with JVM)
mock.mode=remote  →  RemoteMockServerManager (calls /__admin REST API on remote server)
```

`RemoteMockServerManager` has **the same stub methods** as the local manager.
It builds WireMock JSON mapping documents and POSTs them to
`{mock.remote.url}/__admin/mappings` before each test, then calls
`POST /__admin/reset` after each test to clean up.

---

## Option A — WireMock Cloud (recommended for teams)

WireMock Cloud is the official hosted service. The free tier provides 50,000
requests/month with a persistent public HTTPS URL.

### Step 1 — Create a mock server

1. Go to [app.wiremock.cloud](https://app.wiremock.cloud) and sign up.
2. Click **New mock server**.
3. Give it a name (e.g. `apinexus`) and click **Create**.
4. Your mock URL appears at the top of the page:
   ```
   https://abc123ef.wiremockapi.cloud
   ```

### Step 2 — Get your API key

1. In WireMock Cloud, click your **profile icon → API** (top right).
2. Copy the API key shown there.

### Step 3 — Update config.properties

```properties
mock.mode=remote
mock.remote.url=https://abc123ef.wiremockapi.cloud
mock.remote.api.key=wm-your-api-key-here
```

### Step 4 — Run tests

```bash
mvn test
```

`RemoteMockServerManager` will POST all stubs to the cloud server before each
test and DELETE them after. Every test call then goes to the cloud URL.

### Step 5 — Upload static stubs (optional but recommended)

For stubs that never change (GET /api/users, rate-limit responses, etc.) you
can upload the JSON files from `wiremock/mappings/` directly via the WireMock
Cloud dashboard (**Stubs → Import**). Static stubs survive a `resetAllStubs()`
call because `POST /__admin/reset` only removes stubs that were registered
via the API during the test run, not stubs you uploaded through the UI.

> To preserve uploaded stubs across resets, mark them as **persistent** in the
> WireMock Cloud UI (edit stub → toggle "Persistent").

---

## Option B — Fly.io (free, self-hosted, public HTTPS)

Fly.io runs Docker containers on their global edge infrastructure with a free
tier of 3 shared VMs, 160 GB outbound traffic/month, and automatic TLS.

### Prerequisites

```bash
# Install Fly CLI (macOS)
brew install flyctl

# Or on Linux / WSL
curl -L https://fly.io/install.sh | sh

# Sign up / log in
fly auth signup   # or: fly auth login
```

### Step 1 — Build the image

The `Dockerfile` at the project root packages WireMock with all the stubs:

```bash
cd apinexus-playwright
docker build -t apinexus-wiremock .
```

Verify it works locally first:

```bash
docker run --rm -p 8080:8080 apinexus-wiremock

# In another terminal:
curl http://localhost:8080/__admin/health
# → {"status":"running","version":"..."}

curl http://localhost:8080/api/users
# → {"page":1,"per_page":3,"total":3,"data":[...]}
```

### Step 2 — Create the Fly app

```bash
fly launch --no-deploy --name apinexus-wiremock --region sin
```

> Change `sin` (Singapore) in `fly.toml` to the region closest to your team.
> Run `fly platform regions` for the full list.

### Step 3 — Deploy

```bash
fly deploy
```

Fly builds the Docker image, pushes it, and starts the VM. The first deploy
takes about 2–3 minutes. Subsequent deploys (after stub changes) take ~30s.

### Step 4 — Verify

```bash
fly status         # shows running instances
fly logs           # tails live logs

# Check health
curl https://apinexus-wiremock.fly.dev/__admin/health

# List all loaded stubs
curl https://apinexus-wiremock.fly.dev/__admin/mappings
```

### Step 5 — Update config.properties

```properties
mock.mode=remote
mock.remote.url=https://apinexus-wiremock.fly.dev
mock.remote.api.key=          # leave blank — no auth on Fly.io by default
```

### Step 6 — Run tests

```bash
mvn test
```

### Adding stub authentication (optional)

To protect the Admin API on Fly.io, add `--api-key <secret>` to the CMD in
the Dockerfile:

```dockerfile
CMD ["--port", "8080", "--verbose", "--global-response-templating",
     "--api-key", "my-shared-secret"]
```

Then set in config.properties:

```properties
mock.remote.api.key=my-shared-secret
```

### Updating stubs after deployment

Edit any file in `wiremock/mappings/` then redeploy:

```bash
fly deploy
```

The new image picks up the updated mapping files. Running tests will see the
new stubs within seconds.

---

## Option C — Docker Compose on a VPS or home server

Use this when you want the mock server on your own infrastructure —
a DigitalOcean droplet, an AWS EC2 instance, an on-premises VM, or even
a Raspberry Pi on your LAN.

### Start the server

```bash
cd apinexus-playwright
docker compose up -d
```

This starts WireMock on **port 8080** of the host machine.

```bash
# Verify
curl http://YOUR-SERVER-IP:8080/__admin/health
curl http://YOUR-SERVER-IP:8080/api/users
```

### Make it accessible from the internet

By default the server is only accessible on your LAN. To expose it publicly:

**Option 1 — Open port in firewall (simplest)**
```bash
# On the server (Ubuntu/Debian with ufw):
sudo ufw allow 8080/tcp

# Then access via:
# http://YOUR-PUBLIC-IP:8080
```

**Option 2 — Nginx reverse proxy with HTTPS (recommended)**

Install Certbot and Nginx, then add a site config:

```nginx
server {
    listen 443 ssl;
    server_name mock.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/mock.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/mock.yourdomain.com/privkey.pem;

    location / {
        proxy_pass         http://localhost:8080;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
    }
}
```

After setting up Nginx + Certbot your mock server gets a proper HTTPS URL.

### Update config.properties

```properties
mock.mode=remote
mock.remote.url=http://YOUR-SERVER-IP:8080        # or https://mock.yourdomain.com
mock.remote.api.key=                              # blank unless you added --api-key
```

### Live stub reload (no server restart)

The docker-compose.yml mounts `wiremock/mappings/` as a volume and passes
`--watch-files`. Edit any mapping JSON file and WireMock reloads it within
2 seconds — no restart needed.

---

## WireMock Admin API reference

Every deployment exposes the same Admin API. You can call it directly with
curl to inspect or manage stubs at any time.

```bash
BASE=https://apinexus-wiremock.fly.dev
KEY=your-api-key

# List all registered stubs
curl -H "Authorization: Token $KEY" $BASE/__admin/mappings

# Register a new stub manually
curl -X POST $BASE/__admin/mappings \
     -H "Authorization: Token $KEY" \
     -H "Content-Type: application/json" \
     -d '{
       "request": { "method": "GET", "url": "/api/hello" },
       "response": { "status": 200, "body": "Hello from WireMock!" }
     }'

# Test the new stub
curl $BASE/api/hello
# → Hello from WireMock!

# Reset all stubs (removes API-registered ones, keeps file-based ones on Docker)
curl -X POST $BASE/__admin/reset \
     -H "Authorization: Token $KEY"

# Get server health
curl $BASE/__admin/health
```

---

## Switching between local and remote

You can keep two config files and swap them:

```bash
# config.properties        ← local mode (default, committed to git)
# config.remote.properties ← remote mode (gitignored, contains API key)
```

Run with remote config:

```bash
mvn test -Dconfig.file=config.remote.properties
```

Or override on the command line without any extra file:

```bash
mvn test \
  -Dmock.mode=remote \
  -Dmock.remote.url=https://apinexus-wiremock.fly.dev \
  -Dmock.remote.api.key=
```

---

## Comparison summary

```
┌────────────────────────┬──────────────────┬──────────────┬────────────────────┐
│ Feature                │ WireMock Cloud   │ Fly.io       │ Docker Compose VPS │
├────────────────────────┼──────────────────┼──────────────┼────────────────────┤
│ Free tier              │ 50k req/month    │ 3 VMs        │ Depends on your VPS│
│ HTTPS out of the box   │ Yes              │ Yes          │ Manual Nginx setup │
│ Custom domain          │ No (free)        │ Yes          │ Yes                │
│ Stub file hot-reload   │ Via API only     │ fly deploy   │ --watch-files      │
│ Auth on Admin API      │ Yes (mandatory)  │ Optional     │ Optional           │
│ Data retention         │ Persistent       │ Ephemeral*   │ Persistent (volume)│
│ Setup time             │ 5 min            │ 10 min       │ 5 min              │
│ Requires Docker        │ No               │ Yes          │ Yes                │
└────────────────────────┴──────────────────┴──────────────┴────────────────────┘
* Fly.io VMs are ephemeral by default; stubs baked into the Docker image survive restarts.
```
