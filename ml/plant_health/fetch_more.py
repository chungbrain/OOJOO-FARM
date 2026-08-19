#!/usr/bin/env python3
"""Incrementally download more CC / iNaturalist photos for train+test."""
from __future__ import annotations

import json
import time
import urllib.parse
from pathlib import Path

from fetch_test_images import IMG_DIR, MANIFEST, UA, _download, _get_json, wikimedia
from species import INAT_TAXA, LABELS, MORE_QUERIES, SPECIES

PAUSE = 0.2
TARGET = 10
OPENVERSE_PAGES = 2


def openverse_page(query: str, page: int, count: int) -> list[dict]:
    q = urllib.parse.urlencode(
        {"q": query, "page": page, "page_size": min(20, count), "license_type": "all-cc"}
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


def inaturalist(taxon: str, extra_q: str | None, count: int) -> list[dict]:
    params = {
        "taxon_name": taxon,
        "photos": "true",
        "per_page": min(30, count * 2),
        "order": "desc",
        "order_by": "id",
    }
    if extra_q:
        params["q"] = extra_q
    data = _get_json("https://api.inaturalist.org/v1/observations?" + urllib.parse.urlencode(params))
    out = []
    for obs in (data or {}).get("results") or []:
        lic = (obs.get("license_code") or "").lower()
        if lic and not lic.startswith("cc"):
            continue
        for photo in obs.get("photos") or []:
            url = (photo.get("url") or "").replace("square", "medium")
            if not url:
                continue
            plic = (photo.get("license_code") or lic or "cc-by").lower()
            if plic and not plic.startswith("cc"):
                continue
            out.append(
                {
                    "url": url,
                    "source": "inaturalist",
                    "title": (obs.get("taxon") or {}).get("name") or taxon,
                    "license": plic or "cc",
                    "creator": (obs.get("user") or {}).get("login") or "",
                    "foreign_landing_url": obs.get("uri") or "",
                }
            )
            break
        if len(out) >= count:
            break
    return out


def add(items: list, seen: set, species: str, label: str, query: str, hit: dict) -> bool:
    url = hit.get("url")
    if not url or url in seen:
        return False
    ext = ".jpg"
    low = url.lower()
    if ".png" in low:
        ext = ".png"
    elif ".webp" in low:
        ext = ".webp"
    name = f"{species}__{label}__more_{len(items)}{ext}"
    dest = IMG_DIR / name
    if not dest.exists() and not _download(url, dest):
        return False
    if dest.exists() and dest.stat().st_size < 2500:
        dest.unlink(missing_ok=True)
        return False
    seen.add(url)
    items.append(
        {
            "species": species,
            "korean": SPECIES[species]["ko"],
            "label": label,
            "query": query,
            "file": name,
            **hit,
        }
    )
    print("  +", name, flush=True)
    MANIFEST.write_text(json.dumps({"n": len(items), "items": items}, indent=2), encoding="utf-8")
    return True


def count_of(items: list, species: str, label: str) -> int:
    return sum(1 for i in items if i["species"] == species and i["label"] == label)


def main() -> int:
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    man = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {"items": []}
    items = list(man.get("items") or [])
    seen = {i.get("url") for i in items if i.get("url")}

    for sid, taxon in INAT_TAXA.items():
        need = TARGET - count_of(items, sid, "healthy")
        if need > 0:
            print(f"inat healthy {sid}", flush=True)
            for hit in inaturalist(taxon, None, need + 6):
                add(items, seen, sid, "healthy", f"inat:{taxon}", hit)
                time.sleep(PAUSE)
                if count_of(items, sid, "healthy") >= TARGET:
                    break
        need = TARGET - count_of(items, sid, "pest")
        if need > 0:
            print(f"inat pest {sid}", flush=True)
            for hit in inaturalist(taxon, "aphid", need + 4):
                add(items, seen, sid, "pest", f"inat:{taxon} aphid", hit)
                time.sleep(PAUSE)
                if count_of(items, sid, "pest") >= TARGET:
                    break

    for sid, labels in MORE_QUERIES.items():
        for label in LABELS:
            for query in labels[label]:
                if count_of(items, sid, label) >= TARGET:
                    break
                print(f"search {sid}/{label}: {query}", flush=True)
                hits = []
                for page in range(1, OPENVERSE_PAGES + 1):
                    hits.extend(openverse_page(query, page, 12))
                    time.sleep(PAUSE)
                if len(hits) < 6:
                    hits.extend(wikimedia(f"filetype:bitmap {query}", 8))
                    time.sleep(PAUSE)
                for hit in hits:
                    if count_of(items, sid, label) >= TARGET:
                        break
                    add(items, seen, sid, label, query, hit)
                    time.sleep(0.12)

    print(json.dumps({"n": len(items), "manifest": str(MANIFEST)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
