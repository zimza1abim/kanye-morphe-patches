"""Append a version to a `*_VERSIONS = listOf(...)` block and keep the three newest.

Versions are compared numerically, so the block order does not matter: the three highest
are kept and the rest dropped, and a version older than the newest cannot evict it.
Prints `added` or `present`.

With `--check`, print `present` or `absent` and never write. The compatibility
workflow uses that to skip a release the repository already supports, without
relying on a branch-scoped cache to remember previous checks.
"""
import pathlib
import re
import sys

KEEP = 3  # Support scope: an app supports its three newest verified releases.


def sort_key(version):
    return tuple(int(part) for part in version.split("."))


def main():
    args = sys.argv[1:]
    check_only = "--check" in args
    args = [arg for arg in args if arg != "--check"]
    if len(args) != 2:
        raise SystemExit("usage: add_version.py [--check] <versions-file> <version>")
    path = pathlib.Path(args[0])
    version = args[1]
    if not re.fullmatch(r"[0-9][0-9.]*", version):
        raise SystemExit(f"Unexpected version: {version}")
    source = path.read_text()
    block = re.search(r"(_VERSIONS = listOf\(\n)(.*?)(\n\))", source, re.S)
    if block is None:
        raise SystemExit(f"No *_VERSIONS = listOf(...) block in {path}")
    versions = re.findall(r'"([^"]+)"', block.group(2))
    if f'"{version}"' in block.group(2):
        print("present")
        return
    if check_only:
        print("absent")
        return
    try:
        newest = max(versions, key=sort_key) if versions else version
        kept = sorted(set(versions + [version]), key=sort_key)[-KEEP:]
    except ValueError:
        raise SystemExit(f"Non-numeric version in {path}")
    if sort_key(version) < sort_key(newest):
        print(f"warning: {version} is older than {newest}", file=sys.stderr)
    body = "\n".join(f'    "{v}",' for v in kept)
    path.write_text(source[:block.start(2)] + body + source[block.end(2):])
    print("added")


if __name__ == "__main__":
    main()
