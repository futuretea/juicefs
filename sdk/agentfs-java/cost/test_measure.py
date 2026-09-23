"""C1 parser checks using synthetic ps output and the verified-read marker."""

import unittest

from measure import parse_rss, verify_ready


class MeasurementParsingTest(unittest.TestCase):
    def test_parse_rss_accepts_positive_kib_with_surrounding_whitespace(self):
        for output, expected in (("1", 1), ("  12345\n", 12345)):
            with self.subTest(output=output):
                self.assertEqual(expected, parse_rss(output))

    def test_parse_rss_rejects_missing_invalid_and_nonpositive_samples(self):
        for output in ("", " \n", "RSS\n", "NaN", "1.5", "12 kB",
                       "0\n", "-1\n", "12\n34\n"):
            with self.subTest(output=output):
                with self.assertRaises(ValueError):
                    parse_rss(output)

    def test_verify_ready_accepts_only_verified_4096_byte_marker(self):
        self.assertIsNone(verify_ready("READY 4096\n"))

    def test_verify_ready_rejects_missing_wrong_or_incomplete_status(self):
        for line in ("", "READY\n", "READY 0\n", "READY 4095\n",
                     "READY 4097\n", "FAILED 4096\n", "READY 4096",
                     "READY 4096\r\n", " READY 4096\n", "READY 4096 extra\n"):
            with self.subTest(line=line):
                with self.assertRaises(ValueError):
                    verify_ready(line)


if __name__ == "__main__":
    unittest.main()
