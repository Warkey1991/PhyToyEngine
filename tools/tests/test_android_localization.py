"""Check resource coverage and formatting across main and store-specific locales."""
from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2] / "android/sample/src"
LOCALES = {"ja": "values-ja", "ko": "values-ko", "de": "values-de", "fr": "values-fr",
           "es": "values-es", "pt": "values-pt", "id": "values-in", "hi": "values-hi", "th": "values-th"}
FORMAT = re.compile(r"%(?:[0-9]+\$)?[-#+ 0,(]*[0-9]*(?:\.[0-9]+)?[a-zA-Z%]")


def resources(folder):
    result = {}
    for file in sorted(folder.glob("*.xml")):
        for node in ET.parse(file).getroot():
            if node.tag not in {"string", "plurals"}:
                continue
            key = node.tag, node.attrib["name"]
            if key in result:
                raise AssertionError(f"Duplicate resource {key} in {folder}")
            result[key] = node
    return result


def formats(text):
    return Counter(token for token in FORMAT.findall(text) if token != "%%")


class AndroidLocalizationTest(unittest.TestCase):
    def test_declared_locales_and_complete_resource_coverage(self):
        config = ROOT / "main/res/xml/locales_config.xml"
        declared = {node.attrib["{http://schemas.android.com/apk/res/android}name"]
                    for node in ET.parse(config).getroot()}
        declared = {"id" if locale == "in" else locale for locale in declared}
        self.assertEqual(declared, {"en", "zh-CN", *LOCALES})
        for source_set in ["main", "play", "galaxy"]:
            base = resources(ROOT / source_set / "res/values")
            expected = {key for key, node in base.items() if node.get("translatable") != "false"}
            for locale, folder in LOCALES.items():
                with self.subTest(source_set=source_set, locale=locale):
                    localized = resources(ROOT / source_set / "res" / folder)
                    self.assertEqual(set(localized), expected)
                    for key, node in localized.items():
                        self.assertTrue("".join(node.itertext()).strip(), key)

    def test_format_arguments_preserve_index_type_and_precision(self):
        for source_set in ["main", "play", "galaxy"]:
            base = resources(ROOT / source_set / "res/values")
            for locale, folder in LOCALES.items():
                for key, node in resources(ROOT / source_set / "res" / folder).items():
                    with self.subTest(source_set=source_set, locale=locale, resource=key):
                        source = base[key]
                        if node.tag == "plurals":
                            expected = formats(source.find("item[@quantity='other']").text or "")
                            self.assertIsNotNone(node.find("item[@quantity='other']"))
                            if locale in {"fr", "es", "pt"}:
                                self.assertIsNotNone(node.find("item[@quantity='many']"))
                            for item in node:
                                self.assertEqual(formats(item.text or ""), expected)
                        else:
                            self.assertEqual(formats(node.text or ""), formats(source.text or ""))

    def test_store_overrides_keep_correct_provider_and_account(self):
        for locale, folder in LOCALES.items():
            for channel, provider, account, wrong_provider in [
                ("play", "Google Play", "Google Play", "Galaxy Store"),
                ("galaxy", "Galaxy Store", "Samsung", "Google Play"),
            ]:
                merged = resources(ROOT / "main/res" / folder)
                merged.update(resources(ROOT / channel / "res" / folder))
                with self.subTest(locale=locale, channel=channel):
                    self.assertEqual(merged["string", "billing_store_name"].text, provider)
                    self.assertIn(account, merged["string", "billing_account_name"].text)
                    for key in ["purchase_scope", "purchase_payment_provider", "settings_restore_purchases_hint",
                                "billing_loading", "billing_unavailable", "billing_pending", "billing_restore_failed", "billing_error"]:
                        text = merged["string", key].text or ""
                        self.assertNotIn(wrong_provider, text, key)
                    for key in ["purchase_buy", "purchase_buy_all", "purchase_buy_single"]:
                        text = merged["string", key].text or ""
                        self.assertNotIn("5.99", text)
                        self.assertNotIn("19.99", text)

    def test_simplified_chinese_keeps_complete_coverage(self):
        base = resources(ROOT / "main/res/values")
        expected = {key for key, node in base.items() if node.get("translatable") != "false"}
        chinese = resources(ROOT / "main/res/values-zh")
        chinese.update(resources(ROOT / "main/res/values-zh-rCN"))
        self.assertEqual(set(chinese), expected)
        for key, node in chinese.items():
            if node.tag == "string":
                self.assertEqual(formats(node.text or ""), formats(base[key].text or ""), key)


if __name__ == "__main__":
    unittest.main()
