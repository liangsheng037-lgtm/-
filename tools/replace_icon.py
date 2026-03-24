import argparse
import io
import os
import sys
import urllib.request

from PIL import Image


def download(url: str) -> bytes:
    req = urllib.request.Request(
        url,
        headers={
            "ngrok-skip-browser-warning": "1",
            "User-Agent": "android-apk-builder",
        },
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read()


def ensure_dir(p: str) -> None:
    os.makedirs(p, exist_ok=True)


def write_png(img: Image.Image, out_path: str, size: int) -> None:
    im = img.copy()
    im = im.convert("RGBA")
    im = im.resize((size, size), Image.LANCZOS)
    ensure_dir(os.path.dirname(out_path))
    im.save(out_path, format="PNG", optimize=True)


def remove_if_exists(path: str) -> None:
    try:
        if os.path.exists(path):
            os.remove(path)
    except Exception:
        pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--icon-url", required=True)
    ap.add_argument("--res-dir", required=True)
    args = ap.parse_args()

    raw = download(args.icon_url)
    img = Image.open(io.BytesIO(raw))

    targets = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]
    for d, size in targets:
        base = os.path.join(args.res_dir, d)
        remove_if_exists(os.path.join(base, "ic_launcher.webp"))
        remove_if_exists(os.path.join(base, "ic_launcher_round.webp"))
        remove_if_exists(os.path.join(base, "ic_launcher.png"))
        remove_if_exists(os.path.join(base, "ic_launcher_round.png"))
        write_png(img, os.path.join(base, "ic_launcher.png"), size)
        write_png(img, os.path.join(base, "ic_launcher_round.png"), size)

    remove_if_exists(os.path.join(args.res_dir, "mipmap-anydpi-v26", "ic_launcher.xml"))
    remove_if_exists(os.path.join(args.res_dir, "mipmap-anydpi-v26", "ic_launcher_round.xml"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
