import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from repository_check import ROOT, validate

class RepositoryCheckTest(unittest.TestCase):
    def test_current_tree(self):
        self.assertEqual(validate(ROOT), [])

    def test_duplicate_web_directory_is_reported(self):
        with tempfile.TemporaryDirectory() as folder:
            # A minimal fixture is enough to prove the duplicated-source guard.
            nested = Path(folder) / 'firmware' / 'web'
            nested.mkdir(parents=True)
            problems = validate(Path(folder))
            self.assertTrue(any('Duplicate firmware/web/' in p for p in problems))

    def test_missing_tree_is_reported(self):
        with tempfile.TemporaryDirectory() as folder:
            problems = validate(Path(folder))
            self.assertTrue(any('Missing:' in problem for problem in problems))
