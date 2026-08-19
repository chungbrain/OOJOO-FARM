#!/usr/bin/env python3
"""Download CC-licensed internet photos for held-out plant-health tests.

Uses official Openverse and Wikimedia Commons APIs only.
Images are evaluation-only and are never used for training.
"""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from species import LABELS, SPECIES, WEB_QUERIES

UA = "OOJOO-FARM-eval/1.0 (plant-health test set; https://github.com/chungbrain/OOJOO-FARM)"
ROOT = Path(__file__).resolve().parent / "test_internet"
IMG_DIR = ROOT / "images"
MANIFEST = ROOT / "manifest.json"
PER_QUERY = 4
PAUSE = 0.35


def _get_json(url: str) -> dict | None:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        return None


def _download(url: str, dest: Path) -> bool:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
        if len(data) < 2500:
            return False
        dest.write_bytes(data)
        return True
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def openverse(query: str, count: int) -> list[dict]:
    q = urllib.parse.urlencode(
        {
            "q": query,
            "page_size": min(20, count * 3),
            "license_type": "all-cc",
            "category": "photograph",
        }
    )
    data = _get_json(f"https://api.openverse.org/v1/images/?{q}")
    out = []
    for hit in (data or {}).get("results") or []:
        url = hit.get("url") or hit.get("thumbnail")
        if not url:
            continue
        mime = (hit.get("filetype") or "jpg").lower()
        if mime in {"svg", "gif"}:
            continue
        out.append(
            {
                "url": url,
                "source": "openverse",
                "title": hit.get("title") or "",
                "license": hit.get("license") or "",
                "creator": hit.get("creator") or "",
                "foreign_landing_url": hit.get("foreign_landing_url") or "",
            }
        )
        if len(out) >= count:
            break
    return out


def wikimedia(query: str, count: int) -> list[dict]:
    q = urllib.parse.urlencode(
        {
            "action": "query",
            "generator": "search",
            "gsrsearch": query,
            "gsrnamespace": 6,
            "gsrlimit": max(count * 2, 8),
            "prop": "imageinfo",
            "iiprop": "url|mime|extmetadata|size",
            "format": "json",
        }
    )
    data = _get_json(f"https://commons.wikimedia.org/w/api.php?{q}")
    pages = ((data or {}).get("query") or {}).get("pages") or {}
    out = []
    for page in pages.values():
        info = (page.get("imageinfo") or [None])[0] or {}
        url = info.get("url")
        mime = (info.get("mime") or "").lower()
        if not url or not mime.startswith("image/") or "svg" in mime or "gif" in mime:
            continue
        meta = info.get("extmetadata") or {}
        license_ = (meta.get("LicenseShortName") or {}).get("value") or "commons"
        out.append(
            {
                "url": url,
                "source": "wikimedia",
                "title": page.get("title") or "",
                "license": license_,
                "creator": (meta.get("Artist") or {}).get("value") or "",
                "foreign_landing_url": info.get("descriptionurl") or "",
            }
        )
        if len(out) >= count:
            break
    return out


def collect(query: str, count: int) -> list[dict]:
    hits = openverse(query, count)
    if len(hits) < count:
        hits.extend(wikimedia(query, count - len(hits)))
    seen = set()
    uniq = []
    for h in hits:
        if h["url"] in seen:
            continue
        seen.add(h["url"])
        uniq.append(h)
    return uniq[:count]


def main() -> int:
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    items = []
    for sid, queries in WEB_QUERIES.items():
        ko = SPECIES[sid]["ko"]
        for label in LABELS:
            for qi, query in enumerate(queries[label]):
                print(f"search {sid}/{label}: {query}", flush=True)
                for hi, hit in enumerate(collect(query, PER_QUERY)):
                    ext = ".jpg"
                    low = hit["url"].lower()
                    if ".png" in low:
                        ext = ".png"
                    elif ".webp" in low:
                        ext = ".webp"
                    name = f"{sid}__{label}__{qi}_{hi}{ext}"
                    dest = IMG_DIR / name
                    ok = dest.exists() or _download(hit["url"], dest)
                    time.sleep(PAUSE)
                    if not ok:
                        print(f"  skip {hit['url']}", flush=True)
                        continue
                    items.append(
                        {
                            "species": sid,
                            "korean": ko,
                            "label": label,
                            "query": query,
                            "file": name,
                            **hit,
                        }
                    )
                    print(f"  saved {name}", flush=True)
    MANIFEST.write_text(json.dumps({"n": len(items), "items": items}, indent=2), encoding="utf-8")
    print(json.dumps({"n": len(items), "manifest": str(MANIFEST)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
