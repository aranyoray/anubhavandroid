"""Regression cases for patient identity selection and database fallback."""
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import patient_match as pm
import db

PHONE = '9830012345'
OTHER = '9830012346'


def row(key=1, phone=PHONE, name='RINA DAS', bill='2026/09/ALC/4171'):
    return dict(bill_key=key, phone=phone, patientname=name, bill_no=bill, billdate='24/09/2026')


class VerifyIdentityTests(unittest.TestCase):
    def verify(self, rows, name='RINA DAS', phone=PHONE, bill='4171', bill_date=None):
        with patch.object(pm, '_candidates_mssql', return_value=rows):
            return pm.verify_customer(name, phone, bill, bill_date)

    def test_three_factors_win_over_another_patients_two(self):
        result = self.verify([row(), row(2, OTHER)])
        self.assertTrue(result['matched'])
        self.assertEqual(result['phone'], PHONE)
        self.assertEqual([b['bill_key'] for b in result['bills']], [1])

    def test_ambiguous_name_and_bill_does_not_pick_last_phone(self):
        result = self.verify([row(), row(2, OTHER)], phone='')
        self.assertFalse(result['matched'])
        self.assertEqual(result['reason'], 'ambiguous_match')
        self.assertEqual(result['bills'], [])

    def test_missing_record_phone_does_not_authorise_unmatched_input_phone(self):
        result = self.verify([row(phone='')], phone=OTHER)
        self.assertFalse(result['matched'])
        self.assertEqual(result['reason'], 'missing_record_phone')

    def test_unique_name_bill_recovers_mistyped_phone(self):
        result = self.verify([row()], phone=OTHER)
        self.assertTrue(result['matched'])
        self.assertEqual(result['phone'], PHONE)

    def test_full_bill_month_wins_over_conflicting_optional_date(self):
        result = self.verify([row()], name='', bill='2026/09/ALC/4171', bill_date='2026-08-24')
        self.assertTrue(result['matched'])

    def test_multiple_bills_for_one_phone_are_not_ambiguous(self):
        result = self.verify([row(), row(2)], phone='')
        self.assertTrue(result['matched'])
        self.assertEqual(result['phone'], PHONE)

    def test_non_ascii_name_is_a_valid_name_factor(self):
        result = self.verify([row(name='রিনা দাস')], name='রিনা দাস', bill='')
        self.assertTrue(result['matched'])

    def test_wildcards_are_not_bill_numbers(self):
        with self.assertRaises(ValueError):
            self.verify([], phone='', bill='%')


class CandidateQueryTests(unittest.TestCase):
    def test_candidates_are_not_cut_off_by_unrelated_recent_bills(self):
        for backend, connection in ((pm._candidates_mssql, 'mssql_conn'), (pm._candidates_neon, 'neon_conn')):
            with self.subTest(backend=backend.__name__), patch.object(pm, connection, return_value=MagicMock()), \
                    patch.object(pm, 'fetch_all', return_value=[]) as fetch:
                backend(PHONE, True, '4171', None, None)
                sql = fetch.call_args.args[1].upper()
                self.assertNotIn('TOP 500', sql)
                self.assertNotIn('LIMIT 500', sql)


class ConnectionTimeoutTests(unittest.TestCase):
    def test_mssql_fails_fast_enough_for_the_mirror_to_answer(self):
        config = dict(server='localhost', port=1433, user='test', password='test', database='test', tds_version='7.0')
        with patch.object(db, 'mssql_config', return_value=config), patch.object(db.pymssql, 'connect') as connect:
            with db.mssql_conn():
                pass
        kwargs = connect.call_args.kwargs
        self.assertGreater(kwargs.get('login_timeout', 0), 0)
        self.assertLessEqual(kwargs['login_timeout'], 5)
        self.assertGreater(kwargs.get('timeout', 0), 0)
        self.assertLessEqual(kwargs['timeout'], 15)

    def test_neon_connect_and_query_have_deadlines(self):
        with patch.object(db, 'neon_url', return_value='postgresql://localhost/test'), \
                patch.object(db.psycopg2, 'connect') as connect:
            with db.neon_conn():
                pass
        kwargs = connect.call_args.kwargs
        self.assertGreater(kwargs.get('connect_timeout', 0), 0)
        self.assertIn('statement_timeout=', kwargs.get('options', ''))

class TokenInputTests(unittest.TestCase):
    def test_apk_api_key_cannot_be_used_as_a_signing_secret(self):
        import config
        import os
        with patch.dict(os.environ, {'AKTIV_API_KEY': 'public-apk-key', 'AKTIV_TOKEN_SECRET': ''}):
            self.assertEqual(config.token_secret(), '')

    def test_malformed_signed_claims_are_rejected_without_server_errors(self):
        import tokens
        with patch.object(tokens, 'token_secret', return_value='test-secret'):
            for expiry in ('not-a-time', None, [], float('inf')):
                with self.subTest(expiry=expiry):
                    token = tokens.issue('p', 60, exp=expiry, ph=PHONE)
                    self.assertIsNone(tokens.read(token, 'p'))
            token = tokens.issue('p', 60, ph=['not-a-phone'])
            self.assertEqual(tokens.patient_phones(token), set())
            token = tokens.issue('s', 60, uk='not-a-key')
            self.assertIsNone(tokens.staff_user_key(token))

    def test_expiration_boundary_and_zero_clock_are_respected(self):
        import tokens
        with patch.object(tokens, 'token_secret', return_value='test-secret'):
            token = tokens.issue('p', 10, now=0, ph=PHONE)
            self.assertIsNotNone(tokens.read(token, 'p', now=0))
            self.assertIsNone(tokens.read(token, 'p', now=10))
