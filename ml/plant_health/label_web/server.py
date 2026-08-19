#!/usr/bin/env python3
"""Local labeling server for test_internet/images.

Developer confirms or changes the weak search label. Images left unlabeled
are deleted. Short classes can be refilled from Openverse / Wikimedia / iNat.
"""
from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timezone
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
ML = HERE.parent
sys.path.insert(0, str(ML))

from fetch_more import add, inaturalist, openverse_page  # noqa: E402
from fetch_test_images import IMG_DIR, MANIFEST, wikimedia  # noqa: E402
from species import INAT_TAXA, LABELS, MORE_QUERIES, SPECIES, WEB_QUERIES  # noqa: E402

LABEL_KO = {
    "healthy": "건강",
    "water_low": "물 부족",
    "water_high": "물 과다",
    "light_low": "햇빛 부족",
    "pest": "해충",
    "heat": "고온",
}
PORT = 8765
TARGET_DEFAULT = 12


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def load_man() -> dict:
    if MANIFEST.exists():
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    return {"n": 0, "items": []}


def save_man(man: dict) -> None:
    man["n"] = len(man["items"])
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(man, indent=2, ensure_ascii=False), encoding="utf-8")


def is_reviewed(item: dict) -> bool:
    return bool(item.get("reviewed") or item.get("human_label"))


def sync_disk(man: dict) -> int:
    """Index image files that are not in the manifest as unlabeled."""
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    known = {i.get("file") for i in man["items"]}
    added = 0
    for path in sorted(IMG_DIR.iterdir()):
        if not path.is_file() or path.name in known:
            continue
        if path.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
            continue
        parts = path.name.split("__")
        sid = parts[0] if parts and parts[0] in SPECIES else ""
        lab = parts[1] if len(parts) > 1 and parts[1] in LABELS else ""
        man["items"].append(
            {
                "species": sid or "cherry_tomato",
                "korean": SPECIES.get(sid, {}).get("ko", ""),
                "label": lab or "healthy",
                "query": "disk-orphan",
                "file": path.name,
                "source": "local",
                "reviewed": False,
            }
        )
        added += 1
    if added:
        save_man(man)
    return added


def counts(man: dict) -> dict:
    confirmed = {sid: {lab: 0 for lab in LABELS} for sid in SPECIES}
    pending = {sid: {lab: 0 for lab in LABELS} for sid in SPECIES}
    reviewed = unlabeled = 0
    for it in man["items"]:
        sid = it.get("species") or ""
        lab = it.get("human_label") or it.get("label") or ""
        if sid not in SPECIES or lab not in LABELS:
            continue
        if is_reviewed(it):
            confirmed[sid][lab] += 1
            reviewed += 1
        else:
            pending[sid][lab] += 1
            unlabeled += 1
    gaps = []
    for sid in SPECIES:
        for lab in LABELS:
            n = confirmed[sid][lab]
            if n < TARGET_DEFAULT:
                gaps.append(
                    {
                        "species": sid,
                        "korean": SPECIES[sid]["ko"],
                        "label": lab,
                        "label_ko": LABEL_KO[lab],
                        "confirmed": n,
                        "need": TARGET_DEFAULT - n,
                    }
                )
    gaps.sort(key=lambda g: -g["need"])
    return {
        "reviewed": reviewed,
        "unlabeled": unlabeled,
        "total": len(man["items"]),
        "target": TARGET_DEFAULT,
        "confirmed": confirmed,
        "pending": pending,
        "gaps": gaps,
    }


def queries_for(species: str, label: str) -> list[str]:
    out = []
    out.extend((WEB_QUERIES.get(species) or {}).get(label) or [])
    out.extend((MORE_QUERIES.get(species) or {}).get(label) or [])
    return out or [f"{species} {label}"]


def fetch_class(species: str, label: str, count: int) -> dict:
    man = load_man()
    items = man["items"]
    seen = {i.get("url") for i in items if i.get("url")}
    added = 0
    hits: list[dict] = []
    for query in queries_for(species, label):
        hits.extend(openverse_page(query, 1, count + 4))
        time.sleep(0.15)
        if len(hits) >= count * 2:
            break
    if len(hits) < count:
        hits.extend(wikimedia(f"filetype:bitmap {queries_for(species, label)[0]}", count + 4))
    if label in {"healthy", "pest"} and species in INAT_TAXA:
        extra = "aphid" if label == "pest" else None
        hits.extend(inaturalist(INAT_TAXA[species], extra, count + 4))
    query = queries_for(species, label)[0]
    for hit in hits:
        if added >= count:
            break
        if add(items, seen, species, label, query, hit):
            items[-1]["reviewed"] = False
            items[-1]["human_label"] = None
            items[-1]["suggested_label"] = label
            added += 1
    man["items"] = items
    save_man(man)
    return {"added": added, "species": species, "label": label}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(HERE), **kwargs)

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, code: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path in {"/", "/index.html"}:
            html = (HERE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
            return
        if path.startswith("/images/"):
            name = Path(path).name
            dest = IMG_DIR / name
            if not dest.is_file():
                self.send_error(404)
                return
            data = dest.read_bytes()
            ctype = "image/jpeg"
            if name.lower().endswith(".png"):
                ctype = "image/png"
            elif name.lower().endswith(".webp"):
                ctype = "image/webp"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/api/meta":
            self._json(
                200,
                {
                    "labels": [{"id": lab, "ko": LABEL_KO[lab]} for lab in LABELS],
                    "species": [{"id": sid, "ko": spec["ko"]} for sid, spec in SPECIES.items()],
                    "target": TARGET_DEFAULT,
                },
            )
            return
        if path == "/api/queue":
            man = load_man()
            sync_disk(man)
            man = load_man()
            items = []
            for it in man["items"]:
                items.append(
                    {
                        "file": it.get("file"),
                        "species": it.get("species"),
                        "korean": it.get("korean") or SPECIES.get(it.get("species"), {}).get("ko"),
                        "label": it.get("label"),
                        "human_label": it.get("human_label"),
                        "reviewed": is_reviewed(it),
                        "query": it.get("query"),
                        "source": it.get("source"),
                        "license": it.get("license"),
                        "title": it.get("title"),
                    }
                )
            self._json(200, {"items": items, "stats": counts(man)})
            return
        return super().do_GET()

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        body = self._read_json()
        man = load_man()
        if path == "/api/label":
            name = body.get("file")
            sid = body.get("species")
            lab = body.get("label")
            if sid not in SPECIES or lab not in LABELS:
                return self._json(400, {"error": "invalid species or label"})
            found = False
            for it in man["items"]:
                if it.get("file") == name:
                    it["species"] = sid
                    it["korean"] = SPECIES[sid]["ko"]
                    it["label"] = lab
                    it["human_label"] = lab
                    it["reviewed"] = True
                    it["labeled_at"] = now_iso()
                    found = True
                    break
            if not found:
                return self._json(404, {"error": "file not in manifest"})
            save_man(man)
            return self._json(200, {"ok": True, "stats": counts(man)})
        if path == "/api/delete":
            names = body.get("files") or ([body.get("file")] if body.get("file") else [])
            removed = 0
            keep = []
            for it in man["items"]:
                if it.get("file") in names:
                    dest = IMG_DIR / it["file"]
                    dest.unlink(missing_ok=True)
                    removed += 1
                else:
                    keep.append(it)
            man["items"] = keep
            save_man(man)
            return self._json(200, {"removed": removed, "stats": counts(man)})
        if path == "/api/delete-unlabeled":
            keep = []
            removed = 0
            for it in man["items"]:
                if is_reviewed(it):
                    keep.append(it)
                else:
                    (IMG_DIR / it["file"]).unlink(missing_ok=True)
                    removed += 1
            man["items"] = keep
            save_man(man)
            return self._json(200, {"removed": removed, "stats": counts(man)})
        if path == "/api/fetch":
            sid = body.get("species")
            lab = body.get("label")
            n = int(body.get("count") or 6)
            if sid not in SPECIES or lab not in LABELS:
                return self._json(400, {"error": "invalid species or label"})
            result = fetch_class(sid, lab, max(1, min(n, 16)))
            man = load_man()
            result["stats"] = counts(man)
            return self._json(200, result)
        if path == "/api/fetch-gaps":
            stats = counts(man)
            fetched = []
            for gap in stats["gaps"][: int(body.get("max_cells") or 6)]:
                if gap["need"] <= 0:
                    continue
                n = min(gap["need"], int(body.get("per_cell") or 6))
                fetched.append(fetch_class(gap["species"], gap["label"], n))
            man = load_man()
            return self._json(200, {"fetched": fetched, "stats": counts(man)})
        return self._json(404, {"error": "unknown endpoint"})


def main() -> int:
    IMG_DIR.mkdir(parents=True, exist_ok=True)
    man = load_man()
    sync_disk(man)
    print(f"Label UI  http://127.0.0.1:{PORT}/", flush=True)
    print(f"Images    {IMG_DIR}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
