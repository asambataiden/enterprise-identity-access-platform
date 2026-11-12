#!/usr/bin/env python3
"""Unit-check local IAM fixture's container guard and SQL invocation without Docker."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

path = Path(__file__).resolve().with_name("seed-authorized-iam-fixture.py")
spec = importlib.util.spec_from_file_location("fixture", path)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class FixtureGuardTest(unittest.TestCase):
    def test_uses_verified_container_without_compose_interpolation(self):
        inspect_result = subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"55432"}]}',
            stderr="")
        sql_result = subprocess.CompletedProcess(args=[], returncode=0, stdout="1\n", stderr="")
        with patch.object(fixture.subprocess, "run", side_effect=[inspect_result, sql_result]) as mock:
            self.assertEqual("1", fixture.psql("SELECT 1;"))
        command = mock.call_args_list[1].args[0]
        self.assertEqual(command[:4], ["docker", "exec", "-i", fixture.DB_CONTAINER])
        self.assertIn("ON_ERROR_STOP=1", command)
        self.assertEqual(mock.call_args_list[1].kwargs["input"], "SELECT 1;")

    def test_refuses_wrong_port_and_does_not_execute_sql(self):
        inspect_result = subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5432"}]}',
            stderr="")
        with patch.object(fixture.subprocess, "run", return_value=inspect_result) as mock:
            with self.assertRaisesRegex(RuntimeError, "Refusing SQL"):
                fixture.psql("SELECT 1;")
        self.assertEqual(mock.call_count, 1)

    def test_redacts_failed_sql_stderr(self):
        inspect_result = subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"55432"}]}',
            stderr="")
        sql_result = subprocess.CompletedProcess(args=[], returncode=1, stdout="", stderr="SENSITIVE SQL")
        with patch.object(fixture.subprocess, "run", side_effect=[inspect_result, sql_result]):
            with self.assertRaises(RuntimeError) as caught:
                fixture.psql("SELECT 1;")
        self.assertNotIn("SENSITIVE", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
