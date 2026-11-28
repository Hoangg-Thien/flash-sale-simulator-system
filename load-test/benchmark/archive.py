"""Lưu mẫu đo và ảnh chụp DB dưới dạng XZ, giữ nguyên các tệp gzip nguồn."""

import argparse
import gzip
import hashlib
import json
import lzma
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory


def digest(stream):
    with stream:
        return hashlib.file_digest(stream, "sha256").digest()


def archive(source):
    target = source.with_suffix(".xz")
    expected = digest(gzip.open(source, "rb"))
    if target.exists():
        if digest(lzma.open(target, "rb")) != expected:
            raise ValueError(f"Dữ liệu khác tệp nguồn: {target}")
        return

    with TemporaryDirectory(prefix=".archive-", dir=source.parent) as folder:
        candidate = Path(folder) / target.name
        with gzip.open(source, "rb") as raw, lzma.open(candidate, "wb", preset=3) as compressed:
            shutil.copyfileobj(raw, compressed)
        if digest(lzma.open(candidate, "rb")) != expected:
            raise ValueError(f"Kiểm tra dữ liệu giải nén thất bại: {source}")
        candidate.rename(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="Thư mục kết quả có results.json")
    root = parser.parse_args().results
    trials = json.loads((root / "results.json").read_text())
    before = after = count = 0
    for trial in trials:
        if trial["rep"] == 0:
            continue
        folder = root / "{mode}-{vus}-{rep}-{arch}".format_map(trial)
        for name in ("samples.json.gz", "database.json.gz"):
            source = folder / name
            archive(source)
            before += source.stat().st_size
            after += source.with_suffix(".xz").stat().st_size
            count += 1
    print(f"{count} tệp đã đối chiếu SHA-256 sau giải nén. {before:,} → {after:,} byte.")


if __name__ == "__main__":
    main()
