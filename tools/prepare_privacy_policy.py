#!/usr/bin/env python3
"""Render the public policy from the same English/Chinese text used in the app."""
from __future__ import annotations

import argparse
import html
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def paragraphs(language: str) -> str:
    source = ROOT / "android/sample/src/main/res" / language / "support.xml"
    strings = ET.parse(source).getroot()
    body = next(item.text for item in strings if item.get("name") == "privacy_channel_body")
    assert body is not None
    if language == "values":
        body = body.replace("%1$s", "Google Play or Galaxy Store")
        body = body.replace("%2$s", "Google Play or Samsung account")
    else:
        body = body.replace("%1$s", "Google Play 或 Galaxy Store")
        body = body.replace("%2$s", "Google Play／Samsung 账号")
    return "\n".join(f"<p>{html.escape(part)}</p>" for part in body.split(r"\n\n"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publisher")
    parser.add_argument("--email")
    parser.add_argument("--draft", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "release/privacy-policy.html")
    args = parser.parse_args()
    args.publisher = args.publisher.strip() if args.publisher else None
    args.email = args.email.strip() if args.email else None
    if not args.draft and (not args.publisher or not args.email or
                           not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", args.email)):
        parser.error("Public output requires --publisher and a valid --email; use --draft for preparation")
    publisher = html.escape(args.publisher or "[PUBLISHER LEGAL OR STORE NAME]")
    email = html.escape(args.email or "[SUPPORT AND PRIVACY EMAIL]")
    notice = ("<aside>Publication draft: supply the real publisher and support/privacy email before "
              "publishing or submitting. 发布草稿：公开前须补全真实发布者和联系邮箱。</aside>"
              if args.draft else "")
    page = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ToviCam Privacy Policy</title>
<style>body{{max-width:760px;margin:40px auto;padding:0 24px;color:#242424;background:#fff;font:17px/1.7 system-ui,sans-serif}}h1{{line-height:1.2}}aside{{padding:16px;background:#fff5d7;border-radius:12px}}</style></head>
<body><h1>ToviCam Privacy Policy</h1>{notice}
<p>Publisher / 发布者: <strong>{publisher}</strong><br>Support and privacy / 支持与隐私联系: <strong>{email}</strong></p>
{paragraphs("values")}
<hr><section lang="zh-CN"><h1>ToviCam 隐私政策</h1>{paragraphs("values-zh-rCN")}</section>
</body></html>
'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(page, encoding="utf-8")
    print(f"Wrote {'draft' if args.draft else 'publisher-configured'} policy: {args.output}")


if __name__ == "__main__":
    main()
