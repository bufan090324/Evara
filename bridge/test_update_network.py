import unittest
import tempfile
from unittest.mock import patch
from updates import UpdateManager, update_client, DEFAULT_UPDATE_SOURCE


class NetworkChoiceTests(unittest.TestCase):
    def test_direct_does_not_change_system_proxy_or_disable_tls(self):
        with patch("updates.urllib.request.getproxies") as proxies, patch("updates.httpx.AsyncClient") as factory:
            update_client(DEFAULT_UPDATE_SOURCE, direct=True)
            proxies.assert_not_called()
            self.assertIsNone(factory.call_args.kwargs["proxy"])
            self.assertTrue(factory.call_args.kwargs["verify"].check_hostname)
            self.assertFalse(factory.call_args.kwargs["trust_env"])

    def test_choice_saved_and_old_settings_remain_compatible(self):
        with tempfile.TemporaryDirectory() as folder:
            manager = UpdateManager(folder)
            self.assertFalse(manager.direct())
            manager.save_source(DEFAULT_UPDATE_SOURCE, True)
            self.assertTrue(UpdateManager(folder).direct())
            manager.settings_path.write_text('{"url":"https://example.invalid/update.json"}',encoding="utf-8")
            self.assertFalse(manager.direct())
            with self.assertRaises(ValueError): manager.save_source(DEFAULT_UPDATE_SOURCE,"true")
