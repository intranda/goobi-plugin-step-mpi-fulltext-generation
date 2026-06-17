#!/usr/bin/env python3
"""
Rename files by shifting the numeric counter in their names.

Usage: rename_counter.py <directory> <offset> [--new-prefix <prefix>]

File naming pattern: <prefix><counter>.<extension>
Example: Chart-A-02175-16-0002.txt  ->  rename with offset +2  ->  Chart-A-02175-16-0004.txt
Example: Chart-A-02175-16-0002.txt  ->  rename with offset 0 --new-prefix NewPrefix-  ->  NewPrefix-0002.txt
"""

import argparse
import re
import sys
import os


def main():
    parser = argparse.ArgumentParser(
        description="Rename files by shifting the numeric counter in their names."
    )
    parser.add_argument("directory", help="Directory containing the files to rename")
    parser.add_argument("offset", type=int, help="Integer offset to add to each counter")
    parser.add_argument("--new-prefix", metavar="PREFIX", default=None,
                        help="Replace the existing prefix with this value")
    args = parser.parse_args()

    directory = args.directory
    offset = args.offset
    new_prefix = args.new_prefix

    if offset == 0 and new_prefix is None:
        print("Offset is 0 and no new prefix given, nothing to do.")
        sys.exit(0)

    if not os.path.isdir(directory):
        print(f"Error: '{directory}' is not a directory", file=sys.stderr)
        sys.exit(1)

    # Match files where the last segment before the extension is all digits
    pattern = re.compile(r'^(.+?)(\d+)(\.[^.]+)$')

    files = []
    for name in os.listdir(directory):
        m = pattern.match(name)
        if m:
            prefix, counter_str, ext = m.group(1), m.group(2), m.group(3)
            files.append((name, prefix, counter_str, ext))

    if not files:
        print("No matching files found.")
        sys.exit(0)

    # Validate all target names before renaming anything
    existing = {f[0] for f in files}
    targets = {}
    to_delete = []
    errors = []
    for name, prefix, counter_str, ext in files:
        new_counter = int(counter_str) + offset
        if new_counter < 0:
            to_delete.append(name)
            continue
        out_prefix = new_prefix if new_prefix is not None else prefix
        new_name = f"{out_prefix}{str(new_counter).zfill(len(counter_str))}{ext}"
        targets[name] = new_name

    # Check for collisions with files not in our rename set
    rename_set = set(targets.keys())
    delete_set = set(to_delete)
    for old_name, new_name in targets.items():
        if new_name in existing and new_name not in rename_set and new_name not in delete_set:
            errors.append(f"  {old_name} -> {new_name} (target already exists and won't be moved)")

    if errors:
        print("Error: the following renames would overwrite existing files:")
        for e in errors:
            print(e)
        sys.exit(1)

    # Delete files whose counter would become negative
    deleted = 0
    for name in to_delete:
        path = os.path.join(directory, name)
        os.remove(path)
        print(f"  deleted: {name} (counter would become negative)")
        deleted += 1
    if deleted:
        print(f"Deleted {deleted} file(s) with out-of-range counter.")

    # Sort to avoid overwriting: positive offset -> rename largest counter first
    #                            negative offset -> rename smallest counter first
    sorted_files = sorted(
        targets.items(),
        key=lambda item: int(pattern.match(item[0]).group(2)),
        reverse=(offset > 0)
    )

    renamed = 0
    for old_name, new_name in sorted_files:
        old_path = os.path.join(directory, old_name)
        new_path = os.path.join(directory, new_name)
        os.rename(old_path, new_path)
        print(f"  {old_name} -> {new_name}")
        renamed += 1

    summary = f"Renamed {renamed} file(s) with offset {offset:+d}"
    if new_prefix is not None:
        summary += f", new prefix '{new_prefix}'"
    print(f"\n{summary}.")

    # Fill missing pages between 1 and the first page with empty files
    new_names_parsed = []
    for new_name in targets.values():
        m = pattern.match(new_name)
        if m:
            new_names_parsed.append((m.group(1), m.group(2), m.group(3)))

    if new_names_parsed:
        min_entry = min(new_names_parsed, key=lambda x: int(x[1]))
        fill_prefix, fill_counter_str, fill_ext = min_entry
        min_counter = int(fill_counter_str)
        counter_width = len(fill_counter_str)

        if min_counter > 1:
            filled = 0
            for i in range(1, min_counter):
                fill_name = f"{fill_prefix}{str(i).zfill(counter_width)}{fill_ext}"
                fill_path = os.path.join(directory, fill_name)
                if not os.path.exists(fill_path):
                    open(fill_path, 'w').close()
                    print(f"  created empty: {fill_name}")
                    filled += 1
            if filled:
                print(f"Created {filled} empty file(s) to fill gap 1–{min_counter - 1}.")


if __name__ == "__main__":
    main()
