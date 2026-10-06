import io
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch
from pypdf import PdfReader, PdfWriter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report_pdf as pdf


def document(pages=1):
    out = io.BytesIO()
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(width=100, height=100)
    writer.write(out)
    return out.getvalue()


class CollatedPdfTests(unittest.TestCase):
    def call(self, payloads):
        head = [{'patientname': 'Test Patient', 'phone': '9830012345', 'bill_no': '2026/09/ALC/1'}]
        items = [{'category_key': 1, 'report_key': i} for i in range(1, len(payloads) + 1)]
        with patch.object(pdf, 'mssql_conn', return_value=MagicMock()), \
                patch.object(pdf, 'fetch_all', side_effect=[head, items]), \
                patch.object(pdf, '_fetch_pdf', side_effect=payloads):
            return pdf.collated_report_pdf(bill_key=1, phone='9830012345')

    def test_all_reports_are_collated(self):
        result = self.call([document(2), document()])
        self.assertEqual(len(PdfReader(io.BytesIO(result)).pages), 3)

    def test_failed_report_does_not_return_partial_success(self):
        with self.assertRaises(RuntimeError):
            self.call([document(), None])

    def test_corrupt_report_does_not_return_partial_success(self):
        with self.assertRaises(RuntimeError):
            self.call([document(), b'%PDF-broken'])

    def test_empty_pdf_is_not_a_complete_report(self):
        with self.assertRaises(RuntimeError):
            self.call([document(0)])
