"""Regression checks for the migration inventory gate; run with Python's unittest."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("migration_check", Path(__file__).with_name("check_kotlin_test_migration.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class MigrationGateChecks(unittest.TestCase):
    def test_unmapped_source_is_pending_and_cannot_disappear(self):
        errors, states = checker.check({"tests/test_a.py::test_a": "fingerprint"},
            {"tests/test_a.py::test_a": "fingerprint"}, [], Path("/unused"))
        self.assertEqual([], errors)
        self.assertEqual({"tests/test_a.py::test_a": "pending"}, states)

    def test_source_changes_removals_and_additions_are_not_silent(self):
        errors, _ = checker.check({"a": "old", "removed": "old"}, {"a": "new", "added": "new"}, [], Path("/unused"))
        self.assertEqual(3, len(errors))
        self.assertTrue(any("changed" in error for error in errors))
        self.assertTrue(any("removed" in error for error in errors))
        self.assertTrue(any("New Python test" in error for error in errors))

    def test_parameter_changes_invalidate_the_source_fingerprint(self):
        source = '@pytest.mark.parametrize("value", [1, 2])\ndef test_a(value):\n    assert value > 0\n'
        changed = source.replace("[1, 2]", "[1]")
        self.assertNotEqual(checker.definitions(source, "test_a.py"), checker.definitions(changed, "test_a.py"))

    def test_reviewed_addition_is_bound_to_its_current_body_and_remains_pending(self):
        additions = {"added": {"source_sha256": "approved", "reason": "Authorized encoding rollback regression"}}
        errors, states = checker.check({}, {"added": "approved"}, [], Path("/unused"), source_additions=additions)
        self.assertEqual([], errors)
        self.assertEqual({"added": "pending"}, states)
        for current, original in (({"added": "changed"}, {}), ({}, {}), ({"added": "approved"}, {"added": "approved"})):
            self.assertTrue(checker.check(original, current, [], Path("/unused"), source_additions=additions)[0])
        self.assertTrue(checker.check({}, {"added": "approved"}, [], Path("/unused"),
            source_additions={"added": {"source_sha256": "approved"}})[0])

    def test_reviewed_addition_requires_the_same_assertion_parameter_and_kotlin_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Example.kt").write_text('class Example { @Test fun rollback() {} }')
            additions = {"added": {"source_sha256": "approved", "reason": "Authorized regression"}}
            entry = {"source": "added", "source_sha256": "approved", "status": "migrated",
                "assertions": ["All financial writes roll back"], "parameters": ["added[one]", "added[two]"],
                "targets": [{"path": "Example.kt", "test": "rollback"}]}
            errors, states = checker.check({}, {"added": "approved"}, [entry], root, source_additions=additions)
            self.assertEqual([], errors)
            self.assertEqual({"added": "migrated"}, states)
            self.assertTrue(checker.check_parameters([entry], ["added[one]"]))
            for field, value in (("source_sha256", "stale"), ("assertions", []), ("parameters", []), ("targets", [])):
                self.assertTrue(checker.check({}, {"added": "approved"}, [{**entry, field: value}], root,
                    source_additions=additions)[0], field)
            entry["targets"][0]["test"] = "absent"
            self.assertTrue(checker.check({}, {"added": "approved"}, [entry], root, source_additions=additions)[0])

    def test_global_parameters_and_fixture_helpers_cannot_weaken_silently(self):
        source = 'CASES = [1, 2]\n@pytest.mark.parametrize("value", CASES)\ndef test_a(value):\n    assert value > 0\n'
        changed = source.replace("[1, 2]", "[1]")
        self.assertEqual(checker.definitions(source, "test_a.py"), checker.definitions(changed, "test_a.py"))
        old = checker.input_fingerprint(source.encode(), "test_a.py")
        new = checker.input_fingerprint(changed.encode(), "test_a.py")
        self.assertNotEqual(old, new)
        self.assertTrue(checker.check_inputs({"test_a.py": old}, {"test_a.py": new}, {}))
        fixture = b'def account_fixture():\n    return [1, 2]\n'
        self.assertNotEqual(checker.input_fingerprint(fixture, "conftest.py"),
            checker.input_fingerprint(fixture.replace(b"[1, 2]", b"[1]"), "conftest.py"))

    def test_new_root_pytest_configuration_requires_exact_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pytest.ini").write_text("[pytest]\naddopts = --ignore=tests/test_a.py\n")
            with patch.object(checker, "ROOT", root), patch.object(checker, "git", return_value=""):
                original, current = checker.input_inventory("baseline", [])
            self.assertIn("pytest.ini", current)
            self.assertTrue(checker.check_inputs(original, current, {}))

    def test_parameter_mapping_must_cover_the_complete_collected_source_case_set(self):
        nodes = ["tests/test_a.py::test_a[one]", "tests/test_a.py::test_a[two]"]
        entry = {"source": "tests/test_a.py::test_a", "parameters": nodes[:1]}
        self.assertTrue(checker.check_parameters([entry], nodes))
        entry["parameters"] = list(nodes)
        self.assertEqual([], checker.check_parameters([entry], nodes))
        entry["parameters"].append(nodes[0])
        self.assertTrue(checker.check_parameters([entry], nodes))

    def test_reviewed_source_change_is_bound_to_the_exact_baseline_and_new_body(self):
        change = {"source": {"baseline_sha256": "old", "current_sha256": "approved", "reason": "User approved regression extension"}}
        self.assertEqual([], checker.check({"source": "old"}, {"source": "approved"}, [], Path("/unused"), change)[0])
        self.assertTrue(checker.check({"source": "old"}, {"source": "other"}, [], Path("/unused"), change)[0])

    def test_mapping_requires_a_real_kotlin_test_and_assertion_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Example.kt").write_text('class Example { @Test fun actualRule() {} }')
            entry = {"source": "source", "source_sha256": "fingerprint", "status": "migrated",
                "assertions": ["No writes after rejection"], "parameters": ["all original values"],
                "targets": [{"path": "Example.kt", "test": "actualRule"}]}
            self.assertEqual([], checker.check({"source": "fingerprint"}, {"source": "fingerprint"}, [entry], root)[0])
            entry["targets"][0]["test"] = "missingRule"
            self.assertTrue(any("Missing Kotlin @Test" in error for error in checker.check(
                {"source": "fingerprint"}, {"source": "fingerprint"}, [entry], root)[0]))
            entry["assertions"] = []
            self.assertTrue(any("Missing assertion" in error for error in checker.check(
                {"source": "fingerprint"}, {"source": "fingerprint"}, [entry], root)[0]))

    def test_retaining_a_test_requires_its_reason_and_execution_path(self):
        entry = {"source": "source", "source_sha256": "fingerprint", "status": "retained",
            "assertions": ["Build helper behaviour remains"], "parameters": ["original"]}
        errors, _ = checker.check({"source": "fingerprint"}, {"source": "fingerprint"}, [entry], Path("/unused"))
        self.assertTrue(any("Retained test needs" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
