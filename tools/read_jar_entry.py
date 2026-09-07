"""Read selected third-party archive entries without extracting into mod sources."""
import argparse
import re
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("archive")
parser.add_argument("pattern")
parser.add_argument("--list", action="store_true")
args = parser.parse_args()
with zipfile.ZipFile(args.archive) as archive:
    entries = [name for name in archive.namelist() if re.search(args.pattern, name)]
    for name in entries:
        print(name if args.list else f"\n=== {name} ===\n{archive.read(name).decode('utf-8')}")
