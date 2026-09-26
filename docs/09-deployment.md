# 09 — Deployment

How Thawe Marine runs on the Hostinger VPS (62.72.31.13), at
**https://thawemarine.seastella.in/**.
The application ships with no demo data: it starts empty, with one Platform
Admin created from the environment, and everything else is entered in the app.

## 1. Shape

```
Browser ──▶ nginx on the VPS (HTTPS for thawemarine.seastella.in)
              │  /        built React app, /var/www/thawemarine
              │  /api/    proxied to 127.0.0.1:8080/api/
              ▼
           Backend container (Spring Boot, Java 17) ──▶ PostgreSQL 16 container
              │
              └──▶ SMTP (email, optional)
```

The frontend and the API share one origin, so there is no CORS and the sign-in
refresh cookie is first-party. The backend and database listen on the VPS's
loopback only; nginx is the only public entry point. The VPS also hosts
rishtabox.com; Thawe Marine has its own nginx site file and touches nothing of
it. Everything the VPS needs is
in [`deploy/`](../deploy).

## 2. First deployment

On the VPS (Ubuntu), once:

1. **Install Docker** with the Compose plugin (`docker compose version` works),
   plus `git`, `rsync` and `curl`. nginx and the certificate for
   thawemarine.seastella.in (certbot) are already in place; on a new server,
   `apt install nginx certbot python3-certbot-nginx` and
   `certbot --nginx -d thawemarine.seastella.in`, with the subdomain's DNS A
   record pointing at the server first.
2. **Clone the repository**, e.g. to `/opt/thawemarine`.
3. **Configure:** `cp deploy/.env.example deploy/.env`, then fill it in (table
   below). Generate the secrets with `openssl rand -base64 48`.
4. **nginx:** install `deploy/nginx-thawemarine.conf` as the site config
   (the commands are at the top of the file), then
   `nginx -t && systemctl reload nginx`. If `BACKEND_PORT` is not 8080, change
   the two `proxy_pass` lines to match.
5. **Deploy:** `./deploy/deploy.sh`. It builds and starts the database and
   backend, builds the frontend in a Node container (nothing to install on the
   host), copies it to `/var/www/thawemarine` and waits for the backend to
   report healthy.
6. **Sign in** at https://thawemarine.seastella.in/ with the bootstrap admin,
   change the password (Change password, bottom of the side rail), and remove
   `BOOTSTRAP_ADMIN_PASSWORD` from `deploy/.env`.

**Updates** after that are just `./deploy/deploy.sh`: it pulls, rebuilds, and
Flyway applies any new migrations on start. The database and uploads live in
Docker volumes and survive rebuilds.

### Environment variables (`deploy/.env`)

| Variable | Required | Example / note |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | yes | The database is created with these on first start |
| `JWT_SECRET` | yes | 32+ random characters. The app will not start without it |
| `APP_BASE_URL` | yes | `https://thawemarine.seastella.in`. Invitation, reset and alert emails link here |
| `BOOTSTRAP_ADMIN_EMAIL` | first start | The first Platform Admin. Used only while no Platform Admin exists |
| `BOOTSTRAP_ADMIN_PASSWORD` | first start | 12+ characters, not containing the email name. Remove after first sign-in |
| `BOOTSTRAP_ADMIN_NAME` | no | Defaults to "Platform Administrator" |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | for email | Unset = deliveries recorded as SKIPPED (section 5) |
| `MAIL_FROM`, `MAIL_REPLY_TO` | for email | A sender the SMTP account may use |
| `BACKEND_PORT` | no | Loopback port for the backend, default 8080 |

`SPRING_PROFILES_ACTIVE=prod`, `DB_URL` and `UPLOAD_DIR` are set by
`docker-compose.yml`.

### What happens on first start

1. Flyway creates the schema, including the reference data the app needs: the
   equipment categories and the default alert rules.
2. The first Platform Admin is created from `BOOTSTRAP_ADMIN_*`. If those are
   unset the log says so, and nothing else happens until they are set and the
   backend restarts (`docker compose -f deploy/docker-compose.yml up -d backend`).
3. From there, the SoW s4.1 chain in the app: the Platform Admin creates an
   organization and its Technical Head; the Technical Head adds vessels (the
   standard bridge fit, or the vessel's own Excel equipment list) and Ship
   Managers; each Ship Manager assigns a Captain.

A new installation has **no problem types or guided checks**. The Platform
Admin enters them under Problem types and Guided checks before Captains raise
requests (OI-05).

### Useful commands

```bash
cd /opt/thawemarine
docker compose -f deploy/docker-compose.yml ps
docker compose -f deploy/docker-compose.yml logs -f backend
curl -s http://127.0.0.1:8080/actuator/health
```

### Java version

The Dockerfile builds and runs on Java 17, the version every test has passed on.

## 3. Local development

Backend: `mvn -pl app -am spring-boot:run` in `backend/` (dev profile, H2 in
`backend/data/`). Set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` in
the environment for the first run, so there is an account to sign in with.

Frontend: `npm run dev` in `frontend/`, then open http://localhost:5173/.
The dev server proxies `/api` to the backend exactly as nginx does.

The integration tests load their own fixture dataset from test sources
(`backend/app/src/test/java`); none of it is part of the application.

## 4. Backup and recovery (SEC-26)

Two things have to survive a lost server: the database, and the upload volume.
Neither is reproducible — the database holds the fleet's real records, and the
files are the only copy of every certificate and photograph. The schema is not
in the backup set, because Flyway rebuilds it from the migrations in the jar.

**What to back up**

| What | Where | How often | Keep |
|---|---|---|---|
| PostgreSQL database | nightly `pg_dump -Fc`, copied off the VPS (object storage or another machine) | nightly, and before every deployment | 30 daily, 12 monthly |
| Upload volume (`thawemarine_uploads`) | `rclone sync` of the volume to object storage | nightly | 30 daily |
| `deploy/.env` | the password manager, not the backup bucket | on change | current + previous |

`deploy/backup.sh` does both each night, keeping 30 days in
`/var/backups/thawemarine`; its header has the one-line cron install. Those
copies sit on the same VPS, so copy the folder off it as well.

The dump it takes, if you need one by hand:

```bash
docker compose -f /opt/thawemarine/deploy/docker-compose.yml exec -T db \
  pg_dump -U thawemarine --format=custom --no-owner --no-privileges thawemarine \
  > "/var/backups/thawemarine-$(date +%F).dump"
```

The upload volume's files are at the path printed by
`docker volume inspect thawemarine_uploads --format '{{ .Mountpoint }}'`.

**The restore, which is the part that matters.** A backup nobody has restored
is a belief, not a backup. Into an empty database:

```bash
docker compose -f deploy/docker-compose.yml exec -T db \
  pg_restore -U thawemarine --no-owner --dbname thawemarine < thawemarine-2026-09-26.dump
# then start the backend: Flyway validates that the schema matches the
# migrations in the jar, so a mismatched pair fails loudly at boot rather than
# quietly at the first query.
```

Restore the upload files into the same volume. A document row whose file is
missing answers 404 on download and is visible in the logs; the record itself
survives, so nothing silently disappears.

**Rehearse it quarterly**, and after any change to the storage arrangement:
restore last night's dump into a scratch database, start the backend against
it, sign in, open a certificate. Record the date and the time it took. The
recovery objectives the pilot is sized for are **24 hours of data loss (RPO)**
and **4 hours to be serving again (RTO)**; both are set by the nightly
schedule, and both are worth confirming with Seastella.

A Hostinger VPS snapshot is useful, but it is not a substitute for a dump kept
off the VPS: it is lost with the account.

## 5. Email: what is needed to send

The platform sends invitations, password resets and alerts. With no mail server
it degrades honestly rather than failing silently: every delivery is recorded
`SKIPPED`, and an invitation link is shown once to the administrator who created
the account, to pass on another way.

| Setting | Value |
|---|---|
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | **Seastella supplies.** A mailbox on their own domain. |
| `MAIL_FROM` | `Thawe Marine <no-reply@seastella.in>` |
| `MAIL_REPLY_TO` | `team@seastella.in` |
| `BRAND_NAME` / `BRAND_SITE` | `Thawe Marine` / `seastella.in` (the defaults) |

**A published contact address is not a sending account.** Mail claiming to come
from `seastella.in`, sent by a server that domain's SPF and DKIM records do not
name, is filtered or rejected — whatever the From line says. So what is needed
is credentials for a mailbox on the domain, not an address copied from the
website. Once they arrive, set the four `SPRING_MAIL_*` variables in
`deploy/.env` and run `deploy.sh` again; nothing else changes, and the delivery
log shows `SENT` instead of `SKIPPED`.

Check it end to end after configuring: create a test account, confirm the
invitation arrives in a real inbox (not spam), and that a reply to it reaches
`team@seastella.in`.

## 6. Go-live checks

- [ ] `curl http://127.0.0.1:8080/actuator/health` on the VPS answers `UP`
- [ ] https://thawemarine.seastella.in/ loads, and so does a deep link such as
      https://thawemarine.seastella.in/requests (not an nginx 404)
- [ ] The bootstrap admin signs in, and a page reload keeps them signed in
- [ ] `BOOTSTRAP_ADMIN_PASSWORD` removed from `deploy/.env`
- [ ] Create an organization → Technical Head → vessel → Ship Manager → Captain;
      each invitation link opens on thawemarine.seastella.in
- [ ] A certificate uploads and downloads (the upload volume is writable)
- [ ] The Platform Admin's activity feed updates live (the event stream passes
      through nginx unbuffered)
- [ ] With email configured: an alert arrives in a real inbox, and the delivery
      log shows `SENT`
- [ ] The nightly database dump and upload backup from section 4 are scheduled

## 7. Known limits of this deployment

| Limit | Before scaling up |
|---|---|
| Uploads are capped at 25 MB (about 40,000 spreadsheet rows), and an import at 5,000 rows | Confirm the limit with Seastella (OI-09) |
| A new installation has no problem types or guided checks | Seastella enters its pilot content (OI-05) |
| Without a mail server, invitation links are shown to the creator to pass on | Configure SMTP so links go only to their owner (OI-21) |
| Rate limits are counted in one backend instance's memory | A shared store (Redis) if scaled out (SEC-23) |
| Single backend instance; the scheduler and the activity stream's subscribers live in it | Leader lock for the scheduler, and a shared bus for the stream, if scaled out (FEE-04) |
| No SMS | Pending Seastella's decision (OI-18) |
| Database and uploads live on the one VPS | Schedule the nightly dump and upload sync off the VPS (section 4) and rehearse one restore (SEC-26) |
