import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

API_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(API_DIR))

import main  # noqa: E402
import auth  # noqa: E402
from auth import AuthUser  # noqa: E402


class BackendSmokeTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)
        env = patch.dict(os.environ, {"AKTIV_TOKEN_SECRET": "test-secret", "AKTIV_API_KEY": ""})
        env.start()
        self.addCleanup(env.stop)

    def test_health(self):
        response = self.client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")

    def test_aktiv_bookings_default_to_live(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertTrue(main.aktiv_settings()["allow_live_bookings"])

    def test_login_returns_authenticated_user(self):
        user = AuthUser(
            user_key=7,
            userid="reception",
            username="Reception",
        )

        with patch.object(main, "authenticate", return_value=user):
            response = self.client.post(
                "/api/auth/login",
                json={"userid": "reception", "password": "secret"},
            )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        token = body.pop("token")
        self.assertEqual(
            body,
            {
                "success": True,
                "user_key": 7,
                "userid": "reception",
                "username": "Reception",
                "role": "staff",
                "collector_key": None,
                "permissions": {},
            },
        )
        self.assertEqual(main.tokens.staff_user_key(token), 7)

    def test_login_rejects_invalid_credentials(self):
        with patch.object(main, "authenticate", side_effect=ValueError("bad login")):
            response = self.client.post(
                "/api/auth/login",
                json={"userid": "x", "password": "wrong"},
            )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "bad login")

    def test_authenticate_keeps_staff_out_of_collector_scope(self):
        with patch.object(auth, "mssql_conn", return_value=_FakeConn((9, "frontdesk", "Front Desk", "secret"))),                 patch.object(auth, "load_user_permissions", return_value={"roles": ["REPORT"]}):
            user = auth.authenticate("frontdesk", "secret")

        self.assertEqual(user.role, "staff")
        self.assertIsNone(user.collector_key)

    def test_authenticate_assigns_collector_scope_only_to_collectors(self):
        with patch.object(auth, "mssql_conn", return_value=_FakeConn((11, "collector1", "Sample Collector", "secret"))),                 patch.object(auth, "load_user_permissions", return_value={"roles": []}):
            user = auth.authenticate("collector1", "secret")

        self.assertEqual(user.role, "collector")
        self.assertEqual(user.collector_key, 11)

    def test_tests_endpoint_maps_backend_errors(self):
        with patch.object(main, "search_tests", side_effect=RuntimeError("db offline")):
            response = self.client.get("/api/tests")

        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json()["detail"], "Internal server error")

    def test_customer_profile_requires_identifier(self):
        response = self.client.get("/api/customer/profile")

        self.assertEqual(response.status_code, 400)

    def test_customer_profile_returns_profile(self):
        expected = {
            "found": True,
            "patient_name": "Test Patient",
            "phone": "9230755875",
        }

        with patch.object(main, "get_customer_profile", return_value=expected):
            response = self.client.get(
                "/api/customer/profile",
                params={"phone": "9230755875"},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), expected)

    def test_collector_patients_lists_by_owner(self):
        with patch.object(
            main,
            "list_collector_patients",
            return_value=[{"id": 1, "patient_name": "Demo", "phone": "9230755876"}],
        ) as list_patients:
            response = self.client.get(
                "/api/collector/patients",
                params={"collector_user_key": 7},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()[0]["patient_name"], "Demo")
        list_patients.assert_called_once_with(collector_user_key=7, limit=100)

    def test_collector_patient_requires_valid_phone(self):
        response = self.client.post(
            "/api/collector/patients",
            json={
                "collector_user_key": 7,
                "patient_name": "Demo",
                "phone": "123",
            },
        )
        self.assertEqual(response.status_code, 422)

    def test_collector_patient_passes_followup_status(self):
        expected = {
            "id": 5,
            "collector_user_key": 7,
            "patient_name": "Demo",
            "phone": "9230755876",
            "followup_status": "CONTACTED",
        }
        with patch.object(main, "create_collector_patient", return_value=expected) as create_patient:
            response = self.client.post(
                "/api/collector/patients",
                json={
                    "collector_user_key": 7,
                    "patient_name": "Demo",
                    "phone": "9230755876",
                    "followup_status": "CONTACTED",
                },
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), expected)
        create_patient.assert_called_once_with(
            collector_user_key=7,
            patient_name="Demo",
            phone="9230755876",
            age_year=None,
            sex=None,
            referred_by=None,
            notes=None,
            followup_status="CONTACTED",
        )


class _FakeCursor:
 def __init__(self, row):
  self.row = row

 def execute(self, *_args, **_kwargs):
  return None

 def fetchone(self):
  return self.row

 def __enter__(self):
  return self

 def __exit__(self, *_args):
  return False


class _FakeConn:
 def __init__(self, row):
  self.row = row

 def cursor(self):
  return _FakeCursor(self.row)

 def __enter__(self):
  return self

 def __exit__(self, *_args):
  return False


if __name__ == "__main__":
    unittest.main()
