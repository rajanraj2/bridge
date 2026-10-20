#!/usr/bin/env python3
"""
Bridge Release & Download Observability Collector
Queries the GitHub API to gather download counts, unique release metrics,
and asset breakdown across Android APKs, macOS DMGs, PKGs, and ZIP archives.
"""

import sys
import json
import urllib.request
import urllib.error

REPO = "rajanraj2/bridge"
API_URL = f"https://api.github.com/repos/{REPO}/releases"

def fetch_release_metrics():
    req = urllib.request.Request(API_URL, headers={"User-Agent": "Bridge-Metrics-Collector"})
    try:
        with urllib.request.urlopen(req) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        print(f"Error fetching release data: {e}", file=sys.stderr)
        return

    total_downloads = 0
    apk_downloads = 0
    dmg_downloads = 0
    pkg_downloads = 0
    zip_downloads = 0

    print(f"# Bridge Observability: Release & Download Metrics ({REPO})\n")
    print("| Tag | Name | Published | Total Downloads | APK | DMG | PKG | ZIP |")
    print("|---|---|---|---|---|---|---|---|")

    for rel in releases:
        tag = rel.get("tag_name", "")
        name = rel.get("name", "")
        published = rel.get("published_at", "")[:10]
        
        rel_apk = 0
        rel_dmg = 0
        rel_pkg = 0
        rel_zip = 0

        for asset in rel.get("assets", []):
            count = asset.get("download_count", 0)
            asset_name = asset.get("name", "").lower()
            if asset_name.endswith(".apk"):
                rel_apk += count
            elif asset_name.endswith(".dmg"):
                rel_dmg += count
            elif asset_name.endswith(".pkg"):
                rel_pkg += count
            elif asset_name.endswith(".zip"):
                rel_zip += count

        rel_total = rel_apk + rel_dmg + rel_pkg + rel_zip
        total_downloads += rel_total
        apk_downloads += rel_apk
        dmg_downloads += rel_dmg
        pkg_downloads += rel_pkg
        zip_downloads += rel_zip

        print(f"| `{tag}` | {name} | {published} | **{rel_total:,}** | {rel_apk:,} | {rel_dmg:,} | {rel_pkg:,} | {rel_zip:,} |")

    print("\n### Cumulative Download Summary")
    print(f"- **Total Asset Downloads:** {total_downloads:,}")
    print(f"- **Android APKs:** {apk_downloads:,}")
    print(f"- **macOS DMGs:** {dmg_downloads:,}")
    print(f"- **macOS PKGs:** {pkg_downloads:,}")
    print(f"- **macOS ZIPs:** {zip_downloads:,}")

if __name__ == "__main__":
    fetch_release_metrics()
