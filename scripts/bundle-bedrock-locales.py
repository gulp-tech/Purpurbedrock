#!/usr/bin/env python3
import json
import pathlib
import sys
import urllib.request
import zipfile


def main() -> None:
    geyser_dir = pathlib.Path(sys.argv[1]).resolve()
    out_dir = geyser_dir / "core/src/main/resources/purpurbedrock/locales"
    out_dir.mkdir(parents=True, exist_ok=True)

    manifest = json.loads(
        urllib.request.urlopen(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", timeout=30
        ).read().decode("utf-8")
    )
    ver_entry = next((v for v in manifest["versions"] if v["id"] == "26.2"), None)
    if ver_entry is None:
        latest_rel = manifest["latest"]["release"]
        ver_entry = next(v for v in manifest["versions"] if v["id"] == latest_rel)
    print(f"[Purpurbedrock] Using Mojang locale manifest version: {ver_entry['id']}")

    ver_info = json.loads(urllib.request.urlopen(ver_entry["url"], timeout=30).read().decode("utf-8"))
    assets = json.loads(
        urllib.request.urlopen(ver_info["assetIndex"]["url"], timeout=30).read().decode("utf-8")
    )["objects"]

    for code in ("ru_ru", "uk_ua", "be_by", "kk_kz"):
        key = f"minecraft/lang/{code}.json"
        if key in assets:
            h = assets[key]["hash"]
            data = urllib.request.urlopen(
                f"https://resources.download.minecraft.net/{h[:2]}/{h}", timeout=30
            ).read()
            (out_dir / f"{code}.json").write_bytes(data)
            print(f"[Purpurbedrock] Bundled {code}.json ({len(data)} bytes)")

    client_url = ver_info["downloads"]["client"]["url"]
    client_zip = pathlib.Path("/tmp/mc-locale-client.jar")
    urllib.request.urlretrieve(client_url, client_zip)
    with zipfile.ZipFile(client_zip, "r") as zf:
        for name in ("en_us.json", "deprecated.json"):
            entry = f"assets/minecraft/lang/{name}"
            if entry in zf.namelist():
                raw = zf.read(entry)
                (out_dir / name).write_bytes(raw)
                print(f"[Purpurbedrock] Bundled {name} ({len(raw)} bytes)")
    client_zip.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
