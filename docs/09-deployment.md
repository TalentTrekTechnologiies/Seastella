# 09 — Deployment

How to put SeaStella online: the backend as a container with PostgreSQL, the
frontend on Netlify proxying `/api` to it. Written for the hosted demo of
21 Sep 2026; the same steps serve a pilot deployment with the demo profile left
off.

## 1. Shape

```
Browser ──▶ Netlify (React app)
              │  /api/*  proxied, same origin, no CORS
              ▼
           Backend container (Spring Boot, Java 17) ──▶ PostgreSQL 16
              │
              └──▶ SMTP (alert emails, optional)
```

## 2. Backend

Built from `backend/Dockerfile`. Any host that runs a Dockerfile works (Render,
Railway, Azure Container Apps, a VPS with Docker). The host must not put the
service to sleep: a cold start takes about a minute, which is a long silence in
a presentation. Free tiers that sleep are not suitable for the demo.

### Environment variables

| Variable | Required | Example / note |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | `prod,demo` for the hosted demo; `prod` for a real pilot |
| `DB_URL` | yes | `jdbc:postgresql://HOST:5432/seastella?sslmode=require` |
| `DB_USERNAME` | yes | |
| `DB_PASSWORD` | yes | |
| `JWT_SECRET` | yes | 32+ random characters. The app will not start without it |
| `DEMO_PASSWORD` | demo only | Shared by every seeded account; 12+ characters. Not the one in the repository |
| `APP_BASE_URL` | yes | The public https address people open, e.g. the Netlify site. Invitation, reset and alert emails link here; a wrong value sends people to the wrong site |
| `SPRING_MAIL_HOST` | for email | e.g. `smtp.gmail.com`, `smtp.sendgrid.net`. Unset = emails recorded as SKIPPED |
| `SPRING_MAIL_PORT` | for email | `587` |
| `SPRING_MAIL_USERNAME` | for email | |
| `SPRING_MAIL_PASSWORD` | for email | an app password or API key, never an account password |
| `MAIL_FROM` | for email | `SeaStella Alerts <alerts@your-domain>`; must be a sender the SMTP account may use |
| `UPLOAD_DIR` | yes for documents | Where certificates and manuals are stored, e.g. `/var/seastella/documents`. Must be a **persistent** volume — a container filesystem is wiped on redeploy and the records would then point at files that are gone — and must not sit inside anything the web server serves |
| `PORT` | host-injected | Most hosts set it; defaults to 8080 |

Health check path: `/actuator/health`.

The site must be served over HTTPS end to end: the refresh cookie is `Secure`, so
a plain-HTTP deployment cannot keep anyone signed in. Serve the frontend and
`/api` from one origin (the Netlify proxy does this) so the cookie is first-party.

### What happens on first start

1. Flyway creates the schema (V1–V19 and V21–V26, plus the PostgreSQL-only V7
   audit trigger and V20 guided-check uniqueness indexes).
2. With the `demo` profile, the seed runs into the empty database: two client
   organizations, six vessels, the demo users and service history, with dates
   relative to that day.
3. The maintenance scan announces spares already in an attention band — one
   grouped alert per vessel to its Captain, Ship Manager and Technical Head.

### Java version

The pom targets Java 25 since the upgrade commit, but every test has run on
Java 17 and 25 has not been run at all. The Dockerfile builds and runs on 17
with `-Djava.version=17`. Move to 25 (or 21) after the demo, with a full test
run on that JDK.

## 3. Frontend (Netlify)

`netlify.toml` already points at `frontend/`. Set one site environment variable
and redeploy:

| Variable | Value |
|---|---|
| `SEASTELLA_API_ORIGIN` | the backend's public https origin, no path, e.g. `https://seastella-api.onrender.com` |

With it set, the build is the live app and `/api/*` is proxied to the backend.
Without it, the site stays the frontend-only demo that answers from seed data.

## 4. Resetting demo data

The seed only runs into an empty database. To start the demo from a clean
state (e.g. Sunday evening, after rehearsals):

1. Drop and recreate the database (or its `public` schema).
2. Restart the backend. It migrates and re-seeds, with dates relative to today.

Accounts created during rehearsal are removed by this — note any you want to
recreate live.

## 5. Backup and recovery (SEC-26)

Two things have to survive a lost server: the database, and the upload
directory. Neither is reproducible — the database holds the fleet's real
records, and the files are the only copy of every certificate and photograph.
The schema is not in the backup set, because Flyway rebuilds it from the
migrations in the jar.

**What to back up**

| What | Where | How often | Keep |
|---|---|---|---|
| PostgreSQL database | managed provider snapshot, plus a nightly `pg_dump -Fc` to object storage | nightly, and before every deployment | 30 daily, 12 monthly |
| Upload directory (`SEASTELLA_UPLOAD_DIR`) | object storage, `rclone sync` or the provider's volume snapshot | nightly | 30 daily |
| `.env` / configuration secrets | the password manager, not the backup bucket | on change | current + previous |

A nightly dump, from a host that can reach the database:

```bash
pg_dump --format=custom --no-owner --no-privileges   --file "seastella-$(date +%F).dump" "$DATABASE_URL"
```

**The restore, which is the part that matters.** A backup nobody has restored
is a belief, not a backup. Into an empty database:

```bash
createdb seastella_restore
pg_restore --no-owner --dbname seastella_restore "seastella-2026-09-18.dump"
# then point the backend at it and start it: Flyway validates that the schema
# matches the migrations in the jar, so a mismatched pair fails loudly at boot
# rather than quietly at the first query.
```

Restore the upload directory to the same path the backend is configured with.
A document row whose file is missing answers 404 on download and is visible in
the logs; the record itself survives, so nothing silently disappears.

**Rehearse it quarterly**, and after any change to the storage arrangement:
restore last night's dump into a scratch database, start the backend against
it, sign in, open a certificate. Record the date and the time it took. The
recovery objectives the pilot is sized for are **24 hours of data loss (RPO)**
and **4 hours to be serving again (RTO)**; both are set by the nightly
schedule, and both are worth confirming with Seastella before the pilot.

**Before the pilot:** enable the managed provider's automated backups (a
provider snapshot is not a substitute for an off-provider dump — it is lost
with the account), and put the upload directory on a volume that is backed up
rather than on the container's own filesystem, which is discarded on every
deploy.

## 6. Demo-day checks

- [ ] `/actuator/health` answers `UP` on the backend address
- [ ] Netlify site loads and signs in as each of the six roles
- [ ] A raised request produces an alert in the Ship Manager's bell
- [ ] With email configured: an alert arrives in a real inbox, and the Platform
      Admin delivery log shows `SENT` (`GET /api/v1/notifications/deliveries`)
- [ ] Create an organization → Technical Head → vessel → Ship Manager → Captain;
      each invitation arrives by email (or, with no mail server, the link is
      shown to the creator), and the Captain sets a password and signs in
- [ ] A certificate uploaded against a spare, and the vessel's Captain, Ship
      Manager and Technical Head each see the expiry reminder in their bell
- [ ] A report opens on screen and downloads as a PDF that names its scope
- [ ] Data reset done after the last rehearsal
- [ ] Laptop fallback ready: `java -jar seastella.jar` (dev profile, H2) and
      `npx vite`, in case the hosted site is unreachable

## 7. Email: what is needed to send

The platform sends invitations, password resets and alerts. With no mail server
it degrades honestly rather than failing silently: every delivery is recorded
`SKIPPED`, and an invitation link is shown once to the administrator who created
the account, to pass on another way.

| Setting | Value |
|---|---|
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | **Seastella supplies.** A mailbox on their own domain. |
| `MAIL_FROM` | `SeaStella Maritime Ops <no-reply@seastella.in>` |
| `MAIL_REPLY_TO` | `team@seastella.in` |
| `BRAND_NAME` / `BRAND_SITE` | `Seastella` / `seastella.in` |

**A published contact address is not a sending account.** Mail claiming to come
from `seastella.in`, sent by a server that domain's SPF and DKIM records do not
name, is filtered or rejected — whatever the From line says. So what is needed
is credentials for a mailbox on the domain, not an address copied from the
website. Once they arrive, set the four `SPRING_MAIL_*` variables and restart;
nothing else changes, and the delivery log shows `SENT` instead of `SKIPPED`.

Check it end to end after configuring: create a test account, confirm the
invitation arrives in a real inbox (not spam), and that a reply to it reaches
`team@seastella.in`.

## 8. Known limits of this deployment

| Limit | Why it is acceptable for the demo | Before a real pilot |
|---|---|---|
| Uploads are capped at 25 MB (about 40,000 spreadsheet rows), and an import at 5,000 rows | Far above a vessel's equipment list | Confirm the limit with Seastella (OI-09) |
| Rate limits are counted per backend instance | One instance in the pilot | A shared store (Redis) if scaled out (SEC-23) |
| A production database has no problem types or guided checks | The demo profile seeds sample ones | Seastella enters its pilot content under Problem types and Guided checks (OI-05) |
| Without a mail server, invitation links are shown to the creator to pass on | Onboarding still works | Configure SMTP so links go only to their owner (OI-21) |
| Rate limiting counts in one instance's memory | One backend instance in the pilot | A shared store (Redis) if scaled out (SEC-23) |
| Single backend instance; the scheduler and the activity stream's subscribers live in it | Pilot volume | Leader lock for the scheduler, and a shared bus for the stream, if scaled out (FEE-04) |
| No SMS | Pending Seastella's decision (OI-18) | Gateway + phone numbers |
| Backups are not yet scheduled on the host | Demo data is reproducible from the seed | Turn on the nightly dump and the upload sync in section 5, and rehearse one restore (SEC-26) |
