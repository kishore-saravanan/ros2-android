"""Check tracked public Markdown navigation without network access or private notes."""
from pathlib import Path
import re
import subprocess
import unittest
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]


class DocumentationTest(unittest.TestCase):
    def test_local_markdown_links_resolve(self):
        names = subprocess.check_output(
            ['git', 'ls-files', '-z', '--', '*.md'], cwd=ROOT).decode().split('\0')
        for name in filter(None, names):
            path = ROOT / name
            text = path.read_text()
            # Ignore examples inside fenced code, which are not navigation links.
            text = re.sub(r'^```[^\n]*\n.*?^```\s*$', '', text, flags=re.M | re.S)
            targets = re.findall(r'\]\(([^)]+)\)', text)
            targets += re.findall(r'(?:src|href)="([^"]+)"', text)
            for target in targets:
                if '://' in target or target.startswith(('#', 'mailto:')):
                    continue
                target = unquote(target.split('#', 1)[0].strip('<>'))
                if target:
                    with self.subTest(document=name, target=target):
                        self.assertTrue((path.parent / target).exists(), 'Missing local link')


if __name__ == '__main__':
    unittest.main()
