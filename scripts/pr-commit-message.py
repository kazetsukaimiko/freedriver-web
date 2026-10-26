#!/usr/bin/env python3
"""Join a pull request title and body into the squash commit message."""

import os
import sys


def main() -> None:
    title = (os.environ.get("PR_TITLE") or "").replace("\r\n", "\n").replace("\r", "\n").strip()
    body = (os.environ.get("PR_BODY") or "").replace("\r\n", "\n").replace("\r", "\n").strip()
    if not title:
        print("pull request title is empty", file=sys.stderr)
        sys.exit(1)
    text = title if not body else f"{title}\n\n{body}"
    sys.stdout.write(text + "\n")


if __name__ == "__main__":
    main()
