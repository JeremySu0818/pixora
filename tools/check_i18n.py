#!/usr/bin/env python3
"""Verify complete catalogs, formatting placeholders, and UI translation keys."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/main/assets"
expected = set(re.findall(r'AppLanguage\("([^"]+)"', (root / "app/src/main/java/org/pixora/app/data/AppLanguage.kt").read_text()))
base = json.loads((assets / "strings.json").read_text())
catalogs = {p.parent.name: json.loads(p.read_text()) for p in (assets / "locales").glob("*/strings.json")}
assert set(catalogs) == expected, "Catalog languages do not match the picker"
placeholder = re.compile(r'%(?:\d+\$)?[dsf]')
for tag, catalog in catalogs.items():
    assert catalog.keys() == base.keys(), f"{tag}: missing or extra keys"
    for key, value in catalog.items():
        assert isinstance(value, str) and value.strip(), f"{tag}/{key}: empty translation"
        assert placeholder.findall(value) == placeholder.findall(base[key]), f"{tag}/{key}: placeholder mismatch"
assert catalogs["en"] == base, "English fallback and English catalog differ"
ui = (root / "app/src/main/java/org/pixora/app/MainActivity.kt").read_text()
for key in re.findall(r'\bt\("([^"$]+)"', ui):
    assert key in base, f"UI translation missing: {key}"
models = (root / "app/src/main/java/org/pixora/app/data/AppModels.kt").read_text()
for model_id in re.findall(r'UpscaleModel\("([^"]+)"', models):
    assert f"model_{model_id}_description" in base, f"Model description missing: {model_id}"
print(f"Verified {len(catalogs)} languages, {len(base)} keys each, placeholders and UI/model keys.")
