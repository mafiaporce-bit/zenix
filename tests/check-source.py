"""Static checks that run before every build. They read the source only: no phone, no network, no keys."""
from pathlib import Path
import re, subprocess, tempfile, unittest, xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
K = ROOT / 'app/src/main/java/com/jpsums/zenix'
HTML = (ASSETS/'index.html').read_text()
MANIFEST = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
BRIDGE = (K/'ZenixBridge.kt').read_text()

class SourceChecks(unittest.TestCase):
    def test_js_syntax(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d)/'main.mjs'; f.write_text(re.search(r'<script type="module">(.*?)</script>', HTML, re.S).group(1))
            subprocess.run(['node', '--check', str(f)], check=True)
            subprocess.run(['node', '--check', str(ASSETS/'kokoro-worker.js')], check=True)

    def test_manifest(self):
        ET.parse(ROOT/'app/src/main/AndroidManifest.xml')
        # this permission made the app think it was offline on a real phone (v2.55 to v2.62): it must stay out
        self.assertNotIn('ACCESS_NETWORK_STATE', MANIFEST)
        self.assertIn('android.permission.CALL_PHONE', MANIFEST)
        self.assertIn('.AutopilotJob', MANIFEST)

    def test_no_online_flag(self):
        # nothing decides anything from the phone's "connected" flag
        code = re.search(r'<script type="module">(.*?)</script>', HTML, re.S).group(1)
        uses = [l for l in code.split('\n') if 'navigator.onLine' in l]
        self.assertTrue(all('loadKokoro' in l for l in uses), uses)
        for f in K.glob('*.kt'):
            self.assertNotIn('ConnectivityManager', f.read_text(), f.name)

    def test_same_signing_key(self):
        # the fixed key is what lets every new build install over the old one without wiping his data
        self.assertTrue((ROOT/'app/zenix-debug.keystore').exists())
        self.assertIn('zenix-debug.keystore', (ROOT/'app/build.gradle.kts').read_text())

    def test_download_link_and_updates(self):
        wf = (ROOT/'.github/workflows/build-apk.yml').read_text()
        self.assertIn('tag_name: latest', wf)
        self.assertIn('releases/download/latest/', (K/'Updater.kt').read_text())

    def test_queue(self):
        self.assertIn('pendingTexts.push(text)', HTML)
        self.assertIn('pendingTexts.shift()', HTML)

    def test_credentials(self):
        self.assertIn('AndroidKeyStore', (K/'SecretStore.kt').read_text())
        self.assertIn("const SECRET_KEYS = new Set(['apikey','orkey','dgkey','elkey'])", HTML)
        self.assertIn('fun secretGet', BRIDGE); self.assertIn('fun secretSet', BRIDGE)

    def test_no_keys_in_source(self):
        for f in list(ROOT.rglob('*.kt')) + list(ROOT.rglob('*.html')) + list(ROOT.rglob('*.mjs')) + list(ROOT.rglob('*.js')) + list(ROOT.rglob('*.yml')):
            if 'node_modules' in f.parts or '.git' in f.parts: continue
            self.assertIsNone(re.search(r'sk-ant-[A-Za-z0-9_-]{20,}|sk-or-[A-Za-z0-9_-]{20,}', f.read_text(errors='ignore')), f.name)

    def test_hands_free_features_kept(self):
        # he chose to keep these: direct calls, auto-send after his spoken yes, tap/search in other apps, voice replies
        self.assertIn('Intent.ACTION_CALL', BRIDGE)
        self.assertIn('SendHelperService.arm(', BRIDGE)
        self.assertIn('NotifService.reply(', BRIDGE)
        self.assertNotIn('Manual interaction required', BRIDGE)
        self.assertNotIn("confirm('Allow ZENIX action", HTML)

    def test_reminders(self):
        self.assertIn('ReminderStore.restore(c)', (K/'Routines.kt').read_text())
        self.assertIn('ReminderStore.remove(context, id)', (K/'ReminderReceiver.kt').read_text())
        self.assertEqual(BRIDGE.count('ReminderStore.save('), 2)

    def test_helpers_match_modules(self):
        # index.html carries a copy of the two helper modules: the copies must be the same code the tests run
        for name in ('zenix-core.mjs', 'zenix-intelligence.mjs'):
            for line in (ASSETS/name).read_text().strip().split('\n'):
                if line.startswith('//') or not line.strip(): continue
                self.assertIn(line.replace('export function', 'function', 1), HTML, name + ': ' + line[:60])

if __name__ == '__main__': unittest.main(verbosity=2)
