# Anubhav Life Care — Android app + AKTIV API

Patient-facing Android app for Anubhav Life Care (Kolkata diagnostic clinic), plus the
small FastAPI service that connects it to the clinic's AKTIV billing system.

## What is in this repository

| Path | What it is |
|------|------------|
| `app/` | Android app (Kotlin, `com.anubhav.app`) |
| `api/` | FastAPI service the app talks to (`api/main.py`) |
| `api/tests/` | Unit tests for the API (`python -m unittest discover -s api/tests`) |
| `etl/sync.py` | Batch copy of AKTIV SQL Server tables into Neon Postgres |
| `scripts/` | One-off schema inspection helpers |
| `docs/` | Play Console notes |
| `env.example` | Every environment variable the API and ETL read |

## App features

- **Login** — Google / Facebook / email via Firebase, or the OTP-free clinic check:
  patient name, phone and bill number are all required and must match one AKTIV bill.
  The phone must match in full, the bill number can be just its last 3 or 4 digits, and
  the name is matched loosely (typos and a missing middle name are fine). There is no
  bill-date option. A successful check returns a patient token scoped to that phone; the
  API answers report, bill and payment calls only for the phone a token was issued for.
- **Guest mode** — "Continue as guest" on the login screen opens the app without an
  account: home, test catalog, health library and settings work as usual, and My Reports
  finds reports with the same name + phone + bill check. Booking and payments ask the
  guest to sign in, since they need a verified phone.
- **Admin** — the small "Admin? Click here" link under the login form. Staff sign in with
  their AKTIV user id and password; the screen offers what their AKTIV roles allow
  (test counts and bill lookup for everyone; income/CC/due for account rights; new
  booking, edit and cancel for booking, BILLCHANGE and cancel rights). The server checks
  the same roles on every call (`api/roles.py`, `X-Staff-Token`).
- **Book a test** — search the catalog, pick an available date in the calendar and time
  slot, optionally add a collection address or map pin, and pay the 50% test advance
  through Razorpay. The separate **Home Collection** action opens a prefilled WhatsApp
  request to the clinic; the pickup itself requires no payment.
- **My bookings / Pending payments** — bills from AKTIV with outstanding balance, payable
  in-app.
- **My reports** — every visit under the patient's phone; the collated report PDF is
  fetched and cached only when tapped.
- **Health library** — bilingual (English / Bengali) explainers and reference-range dials
  for 94 common tests, shareable as PDF.
- **Collector dashboard** — sample collectors log referred patients (works offline, syncs
  later) and share reports on WhatsApp.
- **Bengali UI** — an in-app language setting; Bengali strings live next to the English
  ones with a `_bn` suffix.

## Tech stack

- Kotlin, AndroidX, Material Components, Navigation component, view binding
- Retrofit + OkHttp + Gson for the API; Room for the on-device catalog cache
- Firebase Auth, Google Sign-In, Facebook Login, Razorpay Checkout
- Framework `LocationManager` / `Geocoder` (no Play Services location), Leaflet +
  OpenStreetMap bundled in `assets/map/` for the pin picker
- Kotlin coroutines; Robolectric for JVM unit tests
- API: FastAPI, `pymssql` (AKTIV SQL Server, read/write), `psycopg2` (Neon mirror, read)

## Data flow

```
Android app ──HTTPS──▶ api/ (FastAPI)
                          ├── AKTIV SQL Server  : logins, bills, receipts, live reports
                          └── Neon Postgres     : test catalog, doctors, all-history reports
                                                  (filled by etl/sync.py)
```

Bills, receipts and bill numbers are always written to and read from the live AKTIV
database. Neon is a batch mirror (every 15 min while the clinic PC is on) used for master
data and the patient's report history. Patient login and history read the live database
first and fall back to the mirror, and history overlays the last 45 days from the live
database so a bill made minutes ago shows up.

The API, the Cloudflare tunnel, IIS (which renders report PDFs) and SQL Server all run on
the clinic PC, which shuts down at midnight and starts at 7 AM. Overnight the app can only
show what it has already cached on the phone (report lists and PDFs a patient has opened).

## Building the app

1. Open the repo in Android Studio (AGP 8.13, Gradle 9.4).
2. Add `app/google-services.json` for your Firebase project (a copy is committed).
3. Optional overrides in `local.properties` (or as Gradle / environment properties):

   ```properties
   AKTIV_API_URL=https://api.anubhavlifecare.in/     # default; use http://192.168.29.157:8080/ on the clinic LAN
   AKTIV_API_KEY=your-shared-app-key                # must match the API server
   RAZORPAY_KEY_ID=rzp_live_xxxxxxxx                 # payments are disabled when blank
   ```

4. Replace the Facebook placeholders in `app/src/main/res/values/strings.xml`
   (`facebook_app_id`, `facebook_client_token`, `fb_login_protocol_scheme`) to enable
   Facebook login; the button reports "not configured" until then.
5. Release builds are minified; the keep rules are in `app/proguard-rules.pro`. A
   `keystore.properties` file at the repo root wires up release signing.

Unit tests: `./gradlew :app:testDebugUnitTest`

## Running the API

```bash
cp env.example .env          # fill in MSSQL_* and NEON_DATABASE_URL
pip install -r api/requirements.txt
uvicorn main:app --app-dir api --host 0.0.0.0 --port 8080
python -m pytest api/tests   # no database needed
```

Key endpoints: `POST /api/auth/login`, `GET /api/catalog`, `GET /api/tests`,
`POST /api/bookings`, `POST /api/customer/verify`, `GET /api/customer/history`,
`GET /api/customer/prebook/calendar`, `POST /api/customer/prebook`,
`POST /api/customer/payments`, `GET|POST /api/collector/patients`, `GET /health`.

## ETL

`python etl/sync.py` truncates and reloads the mirrored AKTIV tables in Neon. Run it on a
schedule from the clinic PC; the tables must already exist in Neon.

## Contact

- Phone: +91-9230755875 | +91-9230755870
- WhatsApp: +91-9230755876
- Email: contact.anubhavlife@gmail.com
- Website: www.anubhavlifecare.in

© Anubhav Life Care. All rights reserved.

## Release APKs that update in place

A new APK installs over the old one (no uninstall, data kept) only when it is signed with
the **same release key** and has an equal or higher `versionCode`:

- `versionCode` is set automatically to the UTC build hour (`yyMMddHH`, e.g. `26101604`),
  so every build is newer than the last. Override with `-PversionCode=N`.
- CI signs with the release key held in the `ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS` and `KEY_PASSWORD` secrets; each build's release notes say `Signing: stable`.
  Locally, put that key's `keystore.properties` at the repo root (gitignored) and run
  `./gradlew :app:assembleRelease`.
- Release key fingerprints: SHA-1 `75:BD:AD:5C:04:67:7B:61:8C:77:9B:60:F0:5C:A5:5F:FA:08:37:14`,
  SHA-256 `F7:DB:E7:D0:…:CB:79:78:B7`. Google sign-in only works once that SHA-1 is added
  to the `com.anubhav.app` Android app in the Firebase console and the refreshed
  `google-services.json` is committed.

## Login and report fixes: local testing

Build a test APK with `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
The installable output is `app/build/outputs/apk/debug/app-debug.apk`. A debug APK uses a
different signing certificate from the release key, so Android requires removing the
release app before installing it (which erases the app's local data); prefer a release
build for anything a patient will keep. Clinic verification and AKTIV staff login use the
API instead of Firebase.

The API changes must be deployed separately from the APK. Set `AKTIV_TOKEN_SECRET` to a
random server-only value distinct from `AKTIV_API_KEY` before starting the updated API.
The shared key inside the APK is no longer accepted as a token signing secret. Changing
the token secret requires patients to verify again and staff to sign in again.

Run backend regressions with `python -m unittest discover -s api/tests` after installing
`api/requirements.txt`. Tests use synthetic data and mock database connections.
