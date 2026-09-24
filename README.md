# Anubhav Life Care — Android app + AKTIV API

Patient-facing Android app for Anubhav Life Care (Kolkata diagnostic clinic), plus the
small FastAPI service that connects it to the clinic's AKTIV billing system.

## What is in this repository

| Path | What it is |
|------|------------|
| `app/` | Android app (Kotlin, `com.anubhav.app`) |
| `api/` | FastAPI service the app talks to (`api/main.py`) |
| `api/tests/` | Unit tests for the API (`python -m pytest api/tests`) |
| `etl/sync.py` | Batch copy of AKTIV SQL Server tables into Neon Postgres |
| `scripts/` | One-off schema inspection helpers |
| `docs/` | Play Console notes |
| `env.example` | Every environment variable the API and ETL read |

## App features

- **Login** — Google / Facebook / email via Firebase, or the OTP-free clinic check
  (any two of patient name, bill number or bill date, phone must match an AKTIV bill).
- **Book a test** — search the catalog, pick a prebook date (10th / 20th / 30th) and time
  slot, optionally geotag the home-collection address (GPS or an offline Leaflet map
  picker), and pay the 50% advance through Razorpay.
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
database. Neon is a batch mirror used for master data and the patient's report history,
so it also works while the clinic PC is switched off.

## Building the app

1. Open the repo in Android Studio (AGP 8.13, Gradle 9.4).
2. Add `app/google-services.json` for your Firebase project (a copy is committed).
3. Optional overrides in `local.properties` (or as Gradle / environment properties):

   ```properties
   AKTIV_API_URL=https://api.anubhavlifecare.in/     # default; use http://192.168.29.157:8080/ on the clinic LAN
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
