#!/usr/bin/env python3
"""
Validates captured JustBans Redis messages against the JSON Schemas in docs/schemas.

Requirements: python 3.9+, jsonschema >= 4.18 (pip install jsonschema)

Usage:
  python3 tools/check-schemas.py message1.json message2.json ...
  python3 tools/check-schemas.py justbans-common/target/interop/*.json

Each file holds one envelope as received from Redis. The schema is chosen from the envelope's `type`.
The plugin's integration test WebClientInteropTest dumps real traffic to justbans-common/target/interop/
(see its Javadoc), which is the intended input. Exit code 0 if everything validates, 1 otherwise.
"""

import json
import pathlib
import sys

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

SCHEMAS = pathlib.Path(__file__).resolve().parent.parent / "docs" / "schemas"

BY_TYPE = {
    "ISSUE_PUNISHMENT": "issue-punishment.schema.json",
    "REVOKE_PUNISHMENT": "revoke-punishment.schema.json",
    "COMMAND_RESULT": "command-result.schema.json",
    "UPDATE_PUNISHMENT": "update-punishment.schema.json",
    "PUNISHMENT_CREATED": "punishment-created.schema.json",
    "PUNISHMENT_UPDATED": "punishment-updated.schema.json",
    "PUNISHMENT_EXPIRED": "punishment-expired.schema.json",
}
# Events that carry the same {"punishment": {...}} payload as PUNISHMENT_CREATED.
PUNISHMENT_EVENTS = {"PUNISHMENT_REVOKED", "JOIN_DENIED"}


def load_registry():
    registry = Registry()
    documents = {}
    for path in SCHEMAS.glob("*.schema.json"):
        document = json.loads(path.read_text(encoding="utf-8"))
        documents[path.name] = document
        registry = registry.with_resource(document["$id"], Resource.from_contents(document, DRAFT202012))
    return registry, documents


def main(paths):
    registry, documents = load_registry()
    failed = 0

    for name, document in documents.items():
        Draft202012Validator.check_schema(document)

    for path in paths:
        message = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
        kind = message.get("type")
        schema_name = BY_TYPE.get(kind)

        if schema_name is None and kind in PUNISHMENT_EVENTS:
            schema = {"allOf": [{"$ref": documents["envelope.schema.json"]["$id"]}],
                      "properties": {"payload": {"required": ["punishment"], "properties": {"punishment": {"$ref": documents["punishment.schema.json"]["$id"]}}}}}
        elif schema_name is None:
            schema = {"$ref": documents["envelope.schema.json"]["$id"]}  # unknown or payload-free types: envelope only
        else:
            schema = {"$ref": documents[schema_name]["$id"]}

        errors = sorted(Draft202012Validator(schema, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER).iter_errors(message), key=lambda e: list(e.path))

        if errors:
            failed += 1
            print("FAIL %s (%s)" % (path, kind))
            for error in errors:
                print("   %s: %s" % ("/".join(str(p) for p in error.absolute_path) or "(root)", error.message))
        else:
            print("ok   %s (%s)" % (path, kind))

    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
