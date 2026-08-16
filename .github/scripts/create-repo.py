#!/usr/bin/env python3
"""Generate the extension repo index from built release APKs.

Reads APKs produced by `assembleRelease`, extracts their manifest metadata
with aapt2, and emits the index files that Aniyomi/Mihon clients consume:

    repo/apk/<name>.apk        signed APKs
    repo/icon/<pkg>.png        launcher icon per extension
    repo/index.json            pretty-printed index
    repo/index.min.json        minified index (what clients fetch)
    repo/repo.json             repo metadata

The source ID is the same stable hash the app computes: the low 64 bits of
md5("<name.lower()>/<lang>/<versionId>"), with the sign bit cleared. Changing
a source's name or lang changes its ID and orphans user library data.
"""

from __future__ import annotations

import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

REPO_DIR = "repo"
APK_DIR = os.path.join(REPO_DIR, "apk")
ICON_DIR = os.path.join(REPO_DIR, "icon")

# Matches the app's extension package prefix.
PKG_PREFIX = "eu.kanade.tachiyomi.animeextension."


def find_aapt2() -> str:
    sdk = (
        os.environ.get("ANDROID_HOME")
        or os.environ.get("ANDROID_SDK_ROOT")
        or os.path.expanduser("~/Library/Android/sdk")
    )
    candidates = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    if not candidates:
        sys.exit(f"aapt2 not found under {sdk}/build-tools/*/")
    return candidates[-1]


def badging(aapt2: str, apk: str) -> str:
    return subprocess.run(
        [aapt2, "dump", "badging", apk],
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def xmltree(aapt2: str, apk: str) -> str:
    return subprocess.run(
        [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def manifest_meta(tree: str, key: str) -> str | None:
    """Read a <meta-data> value from an aapt2 xmltree dump.

    `aapt2 dump badging` omits meta-data entirely, so the extension's
    class/nsfw markers are only visible in the xmltree output. Values appear
    on the line after the matching name attribute.
    """
    lines = tree.splitlines()
    for i, line in enumerate(lines):
        if f'"{key}"' not in line or ":name(" not in line:
            continue
        for following in lines[i + 1 : i + 4]:
            match = re.search(r':value\(0x[0-9a-f]+\)=(?:"([^"]*)"|\(type [^)]*\)([0-9a-fx]+)|(\d+))', following)
            if match:
                return next(g for g in match.groups() if g is not None)
    return None


def source_id(name: str, lang: str, version_id: int = 1) -> str:
    key = f"{name.lower()}/{lang}/{version_id}"
    digest = hashlib.md5(key.encode()).digest()
    value = 0
    for i in range(8):
        value |= (digest[i] & 0xFF) << (8 * (7 - i))
    return str(value & 0x7FFFFFFFFFFFFFFF)


def extract_icon(apk: str, pkg: str, badge: str) -> None:
    """Pull the highest-density launcher icon out of the APK."""
    icons = re.findall(r"application-icon-(\d+):'([^']+)'", badge)
    if not icons:
        return
    path = max(icons, key=lambda pair: int(pair[0]))[1]
    try:
        with zipfile.ZipFile(apk) as zf, zf.open(path) as src:
            os.makedirs(ICON_DIR, exist_ok=True)
            with open(os.path.join(ICON_DIR, f"{pkg}.png"), "wb") as dst:
                shutil.copyfileobj(src, dst)
    except KeyError:
        pass


def main() -> None:
    aapt2 = find_aapt2()

    apks = sorted(
        p
        for p in glob.glob("src/*/*/build/outputs/apk/release/*.apk")
        if "unsigned" not in os.path.basename(p)
    )
    if not apks:
        sys.exit("No release APKs found. Run: ./gradlew assembleRelease")

    os.makedirs(APK_DIR, exist_ok=True)
    os.makedirs(ICON_DIR, exist_ok=True)

    index = []
    for apk in apks:
        badge = badging(aapt2, apk)
        tree = xmltree(aapt2, apk)

        pkg = re.search(r"package: name='([^']+)'", badge).group(1)
        version_code = int(re.search(r"versionCode='(\d+)'", badge).group(1))
        version_name = re.search(r"versionName='([^']+)'", badge).group(1)
        label = re.search(r"application-label:'([^']+)'", badge).group(1)

        raw_nsfw = manifest_meta(tree, "tachiyomi.animeextension.nsfw")
        nsfw = 1 if raw_nsfw not in (None, "0") else 0

        # lang is the directory component of the package: <prefix><lang>.<key>
        lang = pkg[len(PKG_PREFIX):].split(".")[0] if pkg.startswith(PKG_PREFIX) else "all"

        # Clients strip the "Aniyomi: " prefix for display.
        source_name = label.split(": ", 1)[1] if ": " in label else label

        filename = os.path.basename(apk).replace("-release", "")
        shutil.copy2(apk, os.path.join(APK_DIR, filename))
        extract_icon(apk, pkg, badge)

        index.append(
            {
                "name": label,
                "pkg": pkg,
                "apk": filename,
                "lang": lang,
                "code": version_code,
                "version": version_name,
                "nsfw": nsfw,
                "sources": [
                    {
                        "name": source_name,
                        "lang": lang,
                        "id": source_id(source_name, lang),
                        "baseUrl": "",
                    }
                ],
            }
        )
        print(f"  {filename}  ({pkg}, code={version_code}, nsfw={nsfw})")

    index.sort(key=lambda entry: entry["name"])

    with open(os.path.join(REPO_DIR, "index.json"), "w") as fh:
        json.dump(index, fh, indent=2)
        fh.write("\n")

    with open(os.path.join(REPO_DIR, "index.min.json"), "w") as fh:
        json.dump(index, fh, separators=(",", ":"))

    with open(os.path.join(REPO_DIR, "repo.json"), "w") as fh:
        json.dump({"meta": {"name": "Rentaro", "website": ""}}, fh, indent=2)
        fh.write("\n")

    print(f"\nWrote {len(index)} entries to {REPO_DIR}/index.min.json")


if __name__ == "__main__":
    main()
