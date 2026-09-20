import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check_translations.py"


class TranslationAuditTest(unittest.TestCase):
    def make_tree(self, default_xml, locale_xml, *, allowlist=None, extra_directories=None):
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        res_directory = Path(temporary_directory.name) / "res"
        self.write(res_directory / "values" / "strings.xml", default_xml)
        self.write(res_directory / "values-fr" / "strings.xml", locale_xml)
        for name, contents in (extra_directories or {}).items():
            self.write(res_directory / name / "strings.xml", contents)
        allowlist_path = res_directory.parent / "allowlist.json"
        allowlist_path.write_text(
            json.dumps(allowlist or {"allowed_untranslated": []}),
            encoding="utf-8",
        )
        return res_directory, allowlist_path

    @staticmethod
    def write(path, contents):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")

    def audit(self, res_directory, allowlist_path):
        completed = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--res-dir",
                str(res_directory),
                "--allowlist",
                str(allowlist_path),
                "--json",
            ],
            capture_output=True,
            check=False,
            text=True,
        )
        return completed, json.loads(completed.stdout)

    def audit_human(self, res_directory, allowlist_path):
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--res-dir",
                str(res_directory),
                "--allowlist",
                str(allowlist_path),
            ],
            capture_output=True,
            check=False,
            text=True,
        )

    def test_reports_missing_string_key(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"welcome\">Welcome</string><string name=\"bye\">Bye</string></resources>",
            "<resources><string name=\"welcome\">Bonjour</string></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            ("missing-key", "values-fr", "bye"),
            {(item["code"], item["locale"], item["key"]) for item in report["defects"]},
        )

    def test_reports_extra_string_key(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"welcome\">Welcome</string></resources>",
            "<resources><string name=\"welcome\">Bonjour</string><string name=\"extra\">Supplément</string></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            ("extra-key", "values-fr", "extra"),
            {(item["code"], item["locale"], item["key"]) for item in report["defects"]},
        )

    def test_reports_malformed_xml(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"welcome\">Welcome</string></resources>",
            "<resources><string name=\"welcome\">Bonjour</resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("xml-parse-error", {item["code"] for item in report["defects"]})

    def test_reports_empty_string(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"welcome\">Welcome</string></resources>",
            "<resources><string name=\"welcome\">   </string></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            ("empty-value", "values-fr", "welcome"),
            {(item["code"], item["locale"], item["key"]) for item in report["defects"]},
        )

    def test_reports_positional_printf_type_and_position_mismatch(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"message\">%1$s has %2$d songs</string></resources>",
            "<resources><string name=\"message\">%2$s a %1$d chansons</string></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            ("placeholder-mismatch", "values-fr", "message"),
            {(item["code"], item["locale"], item["key"]) for item in report["defects"]},
        )

    def test_reports_plural_without_other_quantity(self):
        res_directory, allowlist = self.make_tree(
            "<resources><plurals name=\"songs\"><item quantity=\"one\">%d song</item><item quantity=\"other\">%d songs</item></plurals></resources>",
            "<resources><plurals name=\"songs\"><item quantity=\"one\">%d chanson</item></plurals></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            ("plural-missing-other", "values-fr", "songs"),
            {(item["code"], item["locale"], item["key"]) for item in report["defects"]},
        )

    def test_reports_plural_placeholder_multiplicity_mismatch(self):
        res_directory, allowlist = self.make_tree(
            "<resources><plurals name=\"songs\"><item quantity=\"other\">%1$d of %1$d songs</item></plurals></resources>",
            "<resources><plurals name=\"songs\"><item quantity=\"other\">%1$d chansons</item></plurals></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn(
            ("placeholder-mismatch", "values-fr", "songs[other]"),
            {(item["code"], item["locale"], item["key"]) for item in report["defects"]},
        )

    def test_allows_reviewed_proper_noun_and_ignores_nonlocale_values_directory(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"brand\">OuterTune</string><string name=\"internal\" translatable=\"false\">Internal</string></resources>",
            "<resources><string name=\"brand\">OuterTune</string><string name=\"internal\" translatable=\"false\">Internal</string></resources>",
            allowlist={"allowed_untranslated": ["OuterTune"]},
            extra_directories={"values-night": "<not valid XML"},
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(report["defects"], [])
        human_output = self.audit_human(res_directory, allowlist)
        self.assertEqual(human_output.returncode, 0)
        self.assertEqual(
            human_output.stdout,
            "Translation audit: 1 locales, 1 canonical keys, 0 defects.\n",
        )

    def test_reports_duplicate_named_resources(self):
        res_directory, allowlist = self.make_tree(
            "<resources><string name=\"welcome\">Welcome</string></resources>",
            "<resources><string name=\"welcome\">Bonjour</string><string name=\"welcome\">Salut</string></resources>",
        )

        completed, report = self.audit(res_directory, allowlist)

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("duplicate-resource", {item["code"] for item in report["defects"]})


if __name__ == "__main__":
    unittest.main()
