#!/usr/bin/env python3
"""Builds the Sileo/apt repo that GitHub Pages serves.

    python3 build_repo.py <dir with .deb files> <output dir> [base URL]

Writes a flat repo (no dists/): debs/, Packages(.gz/.bz2/.xz), Release, CydiaIcon.png and
index.html. Only uk.maynotfunction.pingrelay packages are included. The GitHub Actions workflow
(.github/workflows/sileo-repo.yml) runs this with every release's .deb. Standard library only.
"""

import bz2
import gzip
import hashlib
import io
import lzma
import os
import shutil
import sys
import tarfile
from email.utils import formatdate
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACKAGE = "uk.maynotfunction.pingrelay"
# Field order Sileo/apt expect to see first; anything else follows in the deb's own order.
FIRST_FIELDS = ["Package", "Version", "Architecture", "Maintainer", "Depends", "Conflicts", "Replaces"]


def read_control(deb: Path) -> list[tuple[str, str]]:
    """Returns the control file's fields from a .deb (ar archive with control.tar.{gz,xz})."""
    data = deb.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise SystemExit(f"{deb.name}: not a .deb")
    pos = 8
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size % 2)
        if not name.startswith("control.tar"):
            continue
        if name.endswith(".gz"):
            body = gzip.decompress(body)
        elif name.endswith(".xz"):
            body = lzma.decompress(body)
        elif name != "control.tar":
            raise SystemExit(f"{deb.name}: unsupported {name}")
        with tarfile.open(fileobj=io.BytesIO(body)) as tf:
            member = next(m for m in tf.getmembers() if m.name.lstrip("./") == "control")
            text = tf.extractfile(member).read().decode()
        fields: list[tuple[str, str]] = []
        for line in text.splitlines():
            if line[:1] in (" ", "\t") and fields:
                key, value = fields[-1]
                fields[-1] = (key, value + "\n" + line)
            elif ":" in line:
                key, value = line.split(":", 1)
                fields.append((key.strip(), value.strip()))
        return fields
    raise SystemExit(f"{deb.name}: no control.tar")


def digests(blob: bytes) -> dict[str, str]:
    return {name: hashlib.new(name, blob).hexdigest() for name in ("md5", "sha1", "sha256")}


def stanza(deb: Path, blob: bytes) -> str:
    fields = dict(read_control(deb))
    order = [k for k in FIRST_FIELDS if k in fields] + [k for k in fields if k not in FIRST_FIELDS]
    d = digests(blob)
    lines = [f"{k}: {fields[k]}" for k in order]
    lines += [
        f"Filename: debs/{deb.name}",
        f"Size: {len(blob)}",
        f"MD5sum: {d['md5']}",
        f"SHA1: {d['sha1']}",
        f"SHA256: {d['sha256']}",
    ]
    return "\n".join(lines) + "\n"


def main() -> None:
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    repo = os.environ.get("GITHUB_REPOSITORY", "nickredible/ping")
    owner, name = repo.split("/")
    base_url = sys.argv[3] if len(sys.argv) == 4 else f"https://{owner}.github.io/{name}/"

    debs = []
    for deb in sorted(src.glob("*.deb")):
        if dict(read_control(deb)).get("Package") == PACKAGE:
            debs.append(deb)
        else:
            print(f"skipping {deb.name} (not {PACKAGE})")
    if not debs:
        raise SystemExit(f"No {PACKAGE} packages in {src}")

    if out.exists():
        shutil.rmtree(out)
    (out / "debs").mkdir(parents=True)

    stanzas, versions = [], []
    for deb in debs:
        blob = deb.read_bytes()
        shutil.copyfile(deb, out / "debs" / deb.name)
        stanzas.append(stanza(deb, blob))
        versions.append(dict(read_control(deb))["Version"])
        print(f"added {deb.name}")

    packages = "\n".join(stanzas).encode()
    indexes = {
        "Packages": packages,
        "Packages.gz": gzip.compress(packages, mtime=0),
        "Packages.bz2": bz2.compress(packages),
        "Packages.xz": lzma.compress(packages, format=lzma.FORMAT_XZ),
    }
    for filename, blob in indexes.items():
        (out / filename).write_bytes(blob)

    release = [
        "Origin: Ping",
        "Label: Ping",
        "Suite: stable",
        "Version: 1.0",
        "Codename: ios",
        "Architectures: iphoneos-arm64",
        "Components: main",
        "Description: Ping Relay, the iPhone side of Ping",
        f"Date: {formatdate(usegmt=True)}",
    ]
    for title, algo in (("MD5Sum", "md5"), ("SHA1", "sha1"), ("SHA256", "sha256")):
        release.append(f"{title}:")
        release += [f" {digests(b)[algo]} {len(b)} {f}" for f, b in indexes.items()]
    (out / "Release").write_text("\n".join(release) + "\n")

    shutil.copyfile(HERE.parent / "icons" / "CydiaIcon.png", out / "CydiaIcon.png")
    latest = max(versions, key=lambda v: [int(p) if p.isdigit() else p for p in v.replace("-", ".").split(".")])
    page = (HERE / "index.html").read_text()
    page = page.replace("{{BASE_URL}}", base_url).replace("{{VERSION}}", latest)
    (out / "index.html").write_text(page)
    (out / ".nojekyll").write_text("")
    print(f"repo for {base_url} written to {out} ({len(debs)} package(s), latest {latest})")


if __name__ == "__main__":
    main()
