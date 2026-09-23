"""T5 negative controls for the standalone consumer and runtime JAR audit."""

import gzip
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from audit import audit_classpath, validate_report


class ClasspathAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="agentfs-audit-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def jar(self, name, entries):
        with zipfile.ZipFile(self.directory / name, "w") as archive:
            for entry, data in entries.items():
                archive.writestr(entry, data)

    def test_valid_runtime_jars_report_observed_counts(self):
        self.jar("agentfs.jar", {"io/juicefs/agentfs/AgentFS.class": b"synthetic class bytes"})
        self.jar("helper.jar", {"example/Helper.class": b"Ljava/lang/String;"})
        result = audit_classpath(self.directory)
        self.assertEqual(2, result["jars"])
        self.assertEqual(2, result["classes"])
        self.assertEqual(0, result["native_bytes"])

    def test_innocuous_jar_name_does_not_hide_forbidden_entries(self):
        namespaces = (
            "org/apache/hadoop/fs/FileSystem",
            "org/apache/hadoop/hdfs/DFSClient",
            "org/apache/hadoop/yarn/api/Client",
            "org/apache/spark/SparkContext",
            "org/apache/flink/api/Client",
            "org/apache/ranger/plugin/Policy",
        )
        for namespace in namespaces:
            with self.subTest(namespace=namespace):
                self.jar("innocent.jar", {namespace + ".class": b"synthetic bytes"})
                with self.assertRaises(ValueError):
                    audit_classpath(self.directory)

    def test_all_runtime_jars_are_checked_not_only_sdk_jar(self):
        self.jar("agentfs.jar", {"io/juicefs/agentfs/AgentFS.class": b"clean"})
        self.jar("transitive-helper.jar", {"org/apache/hadoop/conf/Configuration.class": b"bad"})
        with self.assertRaises(ValueError):
            audit_classpath(self.directory)

    def test_bytecode_reference_is_rejected_even_with_clean_entry_name(self):
        self.jar("helper.jar", {"example/Helper.class": b"prefix Lorg/apache/hadoop/fs/Path; suffix"})
        with self.assertRaises(ValueError):
            audit_classpath(self.directory)

    def test_relocated_namespace_is_still_rejected(self):
        self.jar("helper.jar", {"vendor/shaded/org/apache/hadoop/fs/Path.class": b"clean"})
        with self.assertRaises(ValueError):
            audit_classpath(self.directory)

    def test_relocated_bytecode_reference_is_still_rejected(self):
        self.jar("helper.jar", {"example/Helper.class": b"Lvendor/shaded/org/apache/spark/Task;"})
        with self.assertRaises(ValueError):
            audit_classpath(self.directory)

    def test_nested_jar_cannot_hide_dependencies(self):
        self.jar("helper.jar", {"BOOT-INF/lib/hidden.jar": b"nested archive"})
        with self.assertRaises(ValueError):
            audit_classpath(self.directory)

    def test_native_bytes_count_decompressed_library_not_gzip_size(self):
        native = b"synthetic native bytes" * 1000
        compressed = gzip.compress(native)
        self.assertNotEqual(len(native), len(compressed))
        self.jar("agentfs.jar", {"native/linux-aarch64/libjfs.so.gz": compressed})
        self.assertEqual(len(native), audit_classpath(self.directory)["native_bytes"])

    def test_dotted_class_reference_is_rejected(self):
        self.jar("helper.jar", {"example/Helper.class": b"org.apache.hadoop.fs.FileSystem"})
        with self.assertRaises(ValueError):
            audit_classpath(self.directory)

    def test_classpath_cli_success_and_invalid_input_exit_status(self):
        self.jar("valid.jar", {"example/Valid.class": b"clean"})
        success = self.cli("classpath", self.directory)
        self.assertEqual(0, success.returncode, success.stderr)
        self.assertEqual({"jars": 1, "classes": 1, "native_bytes": 0}, json.loads(success.stdout))
        self.jar("invalid.jar", {"org/apache/ranger/Policy.class": b"forbidden"})
        rejected = self.cli("classpath", self.directory)
        self.assertNotEqual(0, rejected.returncode)
        self.assertTrue(rejected.stderr)
        self.assertEqual("", rejected.stdout)

    @staticmethod
    def cli(command, path):
        return subprocess.run(
            [sys.executable, str(Path(__file__).with_name("audit.py")), command, str(path)],
            capture_output=True, text=True, check=False,
        )


class ConsumerReportTest(unittest.TestCase):
    @staticmethod
    def report():
        return {
            "operations": ["readFile", "writeFile", "listDirectory", "search", "editFile", "applyPatch"],
            "search_offsets": [0, 1, 2],
            "edited_text": "cat\n",
        }

    def test_complete_correct_report_passes(self):
        self.assertIsNone(validate_report(self.report()))

    def test_missing_operation_cannot_count_as_complete(self):
        report = self.report()
        report["operations"].remove("applyPatch")
        with self.assertRaises(ValueError):
            validate_report(report)

    def test_wrong_operation_cannot_replace_required_operation(self):
        report = self.report()
        report["operations"][-1] = "unrelatedOperation"
        with self.assertRaises(ValueError):
            validate_report(report)

    def test_unexpected_extra_operation_is_not_the_fixed_report(self):
        report = self.report()
        report["operations"].append("unrelatedOperation")
        with self.assertRaises(ValueError):
            validate_report(report)

    def test_wrong_offsets_order_or_missing_overlap_fails(self):
        for offsets in ([0, 2], [0, 2, 1], [1, 2, 3], [0, 1, 2, 3]):
            with self.subTest(offsets=offsets):
                report = self.report()
                report["search_offsets"] = offsets
                with self.assertRaises(ValueError):
                    validate_report(report)

    def test_missing_fields_fail(self):
        for field in ("operations", "search_offsets", "edited_text"):
            with self.subTest(field=field):
                report = self.report()
                del report[field]
                with self.assertRaises(ValueError):
                    validate_report(report)

    def test_edited_bytes_must_preserve_context_newline(self):
        for edited in ("cat", "cat\r\n", "cat\ndog"):
            with self.subTest(edited=edited):
                report = self.report()
                report["edited_text"] = edited
                with self.assertRaises(ValueError):
                    validate_report(report)

    def test_boolean_offsets_are_not_integer_offsets(self):
        report = self.report()
        report["search_offsets"] = [False, True, 2]
        with self.assertRaises(ValueError):
            validate_report(report)

    def test_report_cli_success_and_invalid_input_exit_status(self):
        with tempfile.TemporaryDirectory(prefix="agentfs-report-test-") as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(self.report()), encoding="utf-8")
            success = ClasspathAuditTest.cli("report", path)
            self.assertEqual(0, success.returncode, success.stderr)
            self.assertEqual({"valid": True}, json.loads(success.stdout))
            invalid = self.report()
            invalid["search_offsets"] = [0, 2]
            path.write_text(json.dumps(invalid), encoding="utf-8")
            rejected = ClasspathAuditTest.cli("report", path)
            self.assertNotEqual(0, rejected.returncode)
            self.assertTrue(rejected.stderr)
            self.assertEqual("", rejected.stdout)
            path.write_text("not json", encoding="utf-8")
            self.assertNotEqual(0, ClasspathAuditTest.cli("report", path).returncode)


if __name__ == "__main__":
    unittest.main()
