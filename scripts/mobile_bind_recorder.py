#!/usr/bin/env python3
"""Record the production builder's actual bind argv, then execute it unchanged."""
import json
import os
from pathlib import Path
import shutil
import sys


def install(report):
    executable = Path(shutil.which("gomobile"))
    real = executable.with_name("gomobile-production-real")
    if real.exists():
        raise ValueError("An existing mobile recorder must be preserved/inspected")
    executable.rename(real)
    executable.write_text("#!" + sys.executable + "\nimport os\nos.execv(" + repr(sys.executable) +
                          ", [" + repr(sys.executable) + ", " + repr(str(Path(__file__).resolve())) +
                          ", 'record', " + repr(str(real)) + ", " + repr(str(report.resolve())) +
                          "] + __import__('sys').argv[1:])\n")
    executable.chmod(0o755)


if __name__ == "__main__":
    if sys.argv[1] == "install":
        install(Path(sys.argv[2]))
    elif sys.argv[1] == "restore":
        executable = Path(shutil.which("gomobile"))
        executable.with_name("gomobile-production-real").replace(executable)
    elif sys.argv[1] == "record":
        real, report, *arguments = sys.argv[2:]
        if arguments and arguments[0] == "bind":
            with open(report, "a") as output:
                output.write(json.dumps({"cwd": os.getcwd(), "argv": arguments}) + "\n")
        os.execv(real, [real, *arguments])
    else:
        raise SystemExit("Usage: mobile_bind_recorder.py install REPORT_JSONL | restore")
