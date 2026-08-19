#!/usr/bin/env python3
"""Fill gaps in the internet test set from known Wikimedia Commons files."""
from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

from fetch_test_images import IMG_DIR, MANIFEST, UA, _download
from species import SPECIES

# Commons file titles. Weak labels from the file subject, not a pot.
CURATED = [
    ("cherry_tomato", "healthy", "Cherry tomatoes.jpg"),
    ("cherry_tomato", "healthy", "Solanum lycopersicum 002.JPG"),
    ("cherry_tomato", "healthy", "Tomato plant 1.jpg"),
    ("cherry_tomato", "water_low", "Fusarium wilt of tomato.jpg"),
    ("cherry_tomato", "water_high", "Tomato yellow leaf curl virus.jpg"),
    ("cherry_tomato", "light_low", "Tomato seedlings.jpg"),
    ("cherry_tomato", "pest", "Late blight on tomato leaf.jpg"),
    ("cherry_tomato", "pest", "Tomato leaf damaged by Tuta absoluta.jpg"),
    ("cherry_tomato", "heat", "Sunscald on tomato.jpg"),
    ("basil", "healthy", "Basil (Ocimum basilicum).jpg"),
    ("basil", "healthy", "Ocimum basilicum 002.JPG"),
    ("basil", "healthy", "Basil-Basilico-Ocimum basilicum-albahaca.jpg"),
    ("basil", "water_high", "Basil downy mildew.jpg"),
    ("basil", "pest", "Aphis fabae01.jpg"),
    ("cactus", "healthy", "Echinocactus grusonii 1.jpg"),
    ("cactus", "healthy", "Ferocactus pilosus 2.jpg"),
    ("cactus", "water_low", "Shriveled cactus.jpg"),
    ("cactus", "pest", "Mealybug on cactus.jpg"),
    ("cactus", "heat", "Sunburned cactus.jpg"),
    ("herb", "healthy", "Rosmarinus officinalis 002.JPG"),
    ("herb", "healthy", "Rosmarinus officinalis - Köhler–s Medizinal-Pflanzen-123.jpg"),
    ("herb", "healthy", "Rosemary bush.jpg"),
    ("herb", "water_low", "Dried rosemary.jpg"),
    ("herb", "pest", "Aphids on rosemary.jpg"),
    ("strawberry", "healthy", "Fragaria × ananassa.jpg"),
    ("strawberry", "healthy", "Strawberry plant.jpg"),
    ("strawberry", "pest", "Strawberry leaf spot.jpg"),
    ("strawberry", "water_high", "Yellowing strawberry leaves.jpg"),
    ("pepper", "healthy", "Capsicum annuum 002.JPG"),
    ("pepper", "healthy", "Chili pepper plant.jpg"),
    ("pepper", "pest", "Aphids on pepper plant.jpg"),
    ("pepper", "heat", "Pepper sunscald.jpg"),
    ("pumpkin", "healthy", "Cucurbita pepo 002.JPG"),
    ("pumpkin", "healthy", "Pumpkin plant leaves.jpg"),
    ("pumpkin", "pest", "Aphids on pumpkin leaf.jpg"),
    ("pumpkin", "water_high", "Powdery mildew on pumpkin.jpg"),
    ("zucchini", "healthy", "Zucchini plant.jpg"),
    ("zucchini", "healthy", "Cucurbita pepo zucchini.jpg"),
    ("zucchini", "pest", "Zucchini leaf damage.jpg"),
    ("zucchini", "water_high", "Zucchini yellow leaves.jpg"),
]

BROAD = {
    "cherry_tomato": {
        "healthy": ["tomato plant"],
        "water_low": ["wilted tomato"],
        "water_high": ["yellow tomato leaf"],
        "light_low": ["tomato seedling indoor"],
        "pest": ["tomato aphids", "tomato blight leaf"],
        "heat": ["tomato sunscald"],
    },
    "basil": {
        "healthy": ["basil plant"],
        "water_low": ["wilted basil"],
        "water_high": ["yellow basil leaf"],
        "light_low": ["basil indoor"],
        "pest": ["basil insect", "aphids basil"],
        "heat": ["basil sun"],
    },
    "cactus": {
        "healthy": ["cactus pot"],
        "water_low": ["shriveled cactus"],
        "water_high": ["cactus rot"],
        "light_low": ["etiolated cactus"],
        "pest": ["cactus mealybug"],
        "heat": ["cactus sunburn"],
    },
    "herb": {
        "healthy": ["rosemary plant"],
        "water_low": ["dry rosemary"],
        "water_high": ["yellow rosemary"],
        "light_low": ["indoor rosemary"],
        "pest": ["rosemary aphid"],
        "heat": ["rosemary sun"],
    },
    "strawberry": {
        "healthy": ["strawberry plant"],
        "water_low": ["wilted strawberry"],
        "water_high": ["yellow strawberry leaf"],
        "light_low": ["indoor strawberry"],
        "pest": ["strawberry aphid"],
        "heat": ["strawberry scorch"],
    },
    "pepper": {
        "healthy": ["chili plant"],
        "water_low": ["wilted pepper plant"],
        "water_high": ["yellow pepper leaf"],
        "light_low": ["pepper seedling"],
        "pest": ["pepper aphid"],
        "heat": ["pepper sunscald"],
    },
    "pumpkin": {
        "healthy": ["pumpkin vine"],
        "water_low": ["wilted pumpkin"],
        "water_high": ["yellow pumpkin leaf"],
        "light_low": ["pumpkin seedling"],
        "pest": ["pumpkin aphid"],
        "heat": ["pumpkin leaf scorch"],
    },
    "zucchini": {
        "healthy": ["zucchini plant"],
        "water_low": ["wilted zucchini"],
        "water_high": ["yellow zucchini leaf"],
        "light_low": ["zucchini seedling"],
        "pest": ["zucchini insect"],
        "heat": ["zucchini sunscald"],
    },
}


def commons_file(title: str) -> dict | None:
    if not title.lower().startswith("file:"):
        title = f"File:{title}"
    q = urllib.parse.urlencode(
        {
            "action": "query",
            "titles": title,
            "prop": "imageinfo",
            "iiprop": "url|mime|extmetadata|size",
            "format": "json",
        }
    )
    req = urllib.request.Request(
        f"https://commons.wikimedia.org/w/api.php?{q}",
        headers={"User-Agent": UA, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:
        return None
    pages = (data.get("query") or {}).get("pages") or {}
    for page in pages.values():
        if "missing" in page or "imageinfo" not in page:
            return None
        info = page["imageinfo"][0]
        mime = (info.get("mime") or "").lower()
        if not mime.startswith("image/") or "svg" in mime:
            return None
        meta = info.get("extmetadata") or {}
        return {
            "url": info.get("url"),
            "source": "wikimedia-curated",
            "title": page.get("title") or title,
            "license": (meta.get("LicenseShortName") or {}).get("value") or "commons",
            "creator": (meta.get("Artist") or {}).get("value") or "",
            "foreign_landing_url": info.get("descriptionurl") or "",
        }
    return None


def commons_search(query: str, limit: int = 4) -> list[dict]:
    q = urllib.parse.urlencode(
        {
            "action": "query",
            "generator": "search",
            "gsrsearch": f"filetype:bitmap {query}",
            "gsrnamespace": 6,
            "gsrlimit": 8,
            "prop": "imageinfo",
            "iiprop": "url|mime|extmetadata|size",
            "format": "json",
        }
    )
    req = urllib.request.Request(
        f"https://commons.wikimedia.org/w/api.php?{q}",
        headers={"User-Agent": UA, "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:
        return []
    out = []
    for page in ((data.get("query") or {}).get("pages") or {}).values():
        info = (page.get("imageinfo") or [None])[0] or {}
        url = info.get("url") or ""
        mime = (info.get("mime") or "").lower()
        if not url or not mime.startswith("image/") or "svg" in mime or "djvu" in url.lower():
            continue
        meta = info.get("extmetadata") or {}
        out.append(
            {
                "url": url,
                "source": "wikimedia-search",
                "title": page.get("title") or "",
                "license": (meta.get("LicenseShortName") or {}).get("value") or "commons",
                "creator": (meta.get("Artist") or {}).get("value") or "",
                "foreign_landing_url": info.get("descriptionurl") or "",
            }
        )
        if len(out) >= limit:
            break
    return out


def add_item(items: list, seen: set, species: str, label: str, query: str, hit: dict) -> bool:
    url = hit.get("url")
    if not url or url in seen:
        return False
    ext = ".jpg"
    low = url.lower()
    if ".png" in low:
        ext = ".png"
    elif ".webp" in low:
        ext = ".webp"
    name = f"{species}__{label}__fill_{len(items)}{ext}"
    dest = IMG_DIR / name
    if not dest.exists() and not _download(url, dest):
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


def main() -> int:
    man = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {"items": []}
    items = list(man.get("items") or [])
    seen = {i.get("url") for i in items if i.get("url")}
    IMG_DIR.mkdir(parents=True, exist_ok=True)

    print("curated commons files", flush=True)
    for species, label, title in CURATED:
        hit = commons_file(title)
        time.sleep(0.2)
        if not hit:
            print("  missing", title.encode("ascii", "replace").decode(), flush=True)
            continue
        add_item(items, seen, species, label, f"commons:{title}", hit)

    print("broad commons search", flush=True)
    for species, labels in BROAD.items():
        have = {(i["species"], i["label"]) for i in items}
        for label, queries in labels.items():
            n_have = sum(1 for i in items if i["species"] == species and i["label"] == label)
            if n_have >= 3:
                continue
            for query in queries:
                for hit in commons_search(query, 3):
                    add_item(items, seen, species, label, query, hit)
                    time.sleep(0.2)
                    n_have = sum(1 for i in items if i["species"] == species and i["label"] == label)
                    if n_have >= 3:
                        break
                if n_have >= 3:
                    break

    MANIFEST.write_text(json.dumps({"n": len(items), "items": items}, indent=2), encoding="utf-8")
    print(json.dumps({"n": len(items), "manifest": str(MANIFEST)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
