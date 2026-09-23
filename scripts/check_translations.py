#!/usr/bin/env python3
"""Audit Android string resources against the default OuterTune resources."""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as element_tree
from collections import Counter, defaultdict
from pathlib import Path


RESOURCE_FILES = ("strings.xml", "strings-ot.xml")
LOCALE_DIRECTORY = re.compile(
    r"^values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z]{2,3}(?:\+[A-Za-z0-9]+)*)(?:-[A-Za-z0-9]+)*$"
)
FORMAT_TOKEN = re.compile(
    r"%(?:(?P<position>\d+)\$)?(?P<flags>[-#+ 0,(<]*)?(?:\d+)?(?:\.\d+)?(?:(?P<datetime>[tT])(?P<datetime_conversion>[a-zA-Z])|(?P<conversion>[a-zA-Z]))"
)


def defect(code, locale, key, path, detail):
    return {
        "code": code,
        "detail": detail,
        "key": key,
        "locale": locale,
        "path": path,
    }


def element_value(element):
    return "".join(element.itertext()).strip()


def parse_resource_file(path, locale, defects, root_directory):
    relative_path = path.relative_to(root_directory).as_posix()
    try:
        root = element_tree.parse(path).getroot()
    except element_tree.ParseError as error:
        defects.append(defect("xml-parse-error", locale, "", relative_path, str(error)))
        return {}

    resources = {}
    for element in root:
        if element.tag not in {"string", "plurals"} or element.get("translatable") == "false":
            continue
        name = element.get("name")
        if not name:
            continue
        if name in resources:
            defects.append(
                defect("duplicate-resource", locale, name, relative_path, "resource name is repeated")
            )
            continue
        if element.tag == "string":
            resources[name] = {"kind": "string", "value": element_value(element)}
            continue

        quantities = {}
        for item in element.findall("item"):
            quantity = item.get("quantity")
            if not quantity:
                continue
            if quantity in quantities:
                defects.append(
                    defect(
                        "duplicate-plural-quantity",
                        locale,
                        name,
                        relative_path,
                        f"quantity '{quantity}' is repeated",
                    )
                )
                continue
            quantities[quantity] = element_value(item)
        resources[name] = {"kind": "plural", "values": quantities}
    return resources


def load_resources(directory, locale, defects, root_directory):
    resources = {}
    for filename in RESOURCE_FILES:
        path = directory / filename
        if not path.exists():
            continue
        parsed = parse_resource_file(path, locale, defects, root_directory)
        relative_path = path.relative_to(root_directory).as_posix()
        for name, resource in parsed.items():
            if name in resources:
                defects.append(
                    defect(
                        "duplicate-resource",
                        locale,
                        name,
                        relative_path,
                        "resource name is repeated across resource files",
                    )
                )
                continue
            resources[name] = resource
    return resources


def format_signature(value):
    tokens = []
    next_position = 1
    previous_position = None
    index = 0
    while index < len(value):
        if value[index] != "%":
            index += 1
            continue
        if value.startswith("%%", index):
            index += 2
            continue
        match = FORMAT_TOKEN.match(value, index)
        if match is None:
            index += 1
            continue
        conversion = match.group("conversion")
        datetime_prefix = match.group("datetime")
        if conversion == "n":
            index = match.end()
            continue
        if datetime_prefix:
            conversion = f"{datetime_prefix.lower()}{match.group('datetime_conversion').lower()}"
        else:
            conversion = conversion.lower()
        position_text = match.group("position")
        if position_text:
            position = int(position_text)
        elif "<" in match.group("flags") and previous_position is not None:
            position = previous_position
        else:
            position = next_position
            next_position += 1
        previous_position = position
        tokens.append((position, conversion))
        index = match.end()
    return sorted(Counter(tokens).items())


def validate_resource_values(resources, locale, root_directory, defects):
    for key, resource in resources.items():
        if resource["kind"] == "string":
            if not resource["value"]:
                defects.append(defect("empty-value", locale, key, "", "string value is empty"))
            continue
        if "other" not in resource["values"]:
            defects.append(defect("plural-missing-other", locale, key, "", "plural is missing 'other'"))
        for quantity, value in resource["values"].items():
            if not value:
                defects.append(
                    defect("empty-value", locale, f"{key}[{quantity}]", "", "plural value is empty")
                )


def validate_locale(canonical, localized, locale, root_directory, allowlist, defects):
    canonical_keys = set(canonical)
    localized_keys = set(localized)
    for key in sorted(canonical_keys - localized_keys):
        defects.append(defect("missing-key", locale, key, "", "not present in locale"))
    for key in sorted(localized_keys - canonical_keys):
        defects.append(defect("extra-key", locale, key, "", "not present in canonical resources"))

    for key in sorted(canonical_keys & localized_keys):
        source = canonical[key]
        translation = localized[key]
        if source["kind"] != translation["kind"]:
            defects.append(
                defect(
                    "resource-type-mismatch",
                    locale,
                    key,
                    "",
                    f"canonical is {source['kind']}; locale is {translation['kind']}",
                )
            )
            continue
        if source["kind"] == "string":
            validate_value_pair(source["value"], translation["value"], locale, key, allowlist, defects)
            continue
        dynamic_source = next(
            (value for value in source["values"].values() if format_signature(value)),
            source["values"].get("other", ""),
        )
        for quantity in sorted(translation["values"]):
            validate_value_pair(
                source["values"].get(quantity, dynamic_source),
                translation["values"][quantity],
                locale,
                f"{key}[{quantity}]",
                allowlist,
                defects,
                dynamic_source=dynamic_source,
                check_untranslated=quantity in source["values"],
            )


def validate_value_pair(
    source, translation, locale, key, allowlist, defects, dynamic_source=None, check_untranslated=True
):
    if re.search(r"(?<!\\)'", translation):
        defects.append(
            defect(
                "invalid-android-escape",
                locale,
                key,
                "",
                "apostrophe must be escaped as \\' in an Android string resource",
            )
        )
    source_signature = format_signature(source)
    translation_signature = format_signature(translation)
    dynamic_signature = format_signature(dynamic_source) if dynamic_source is not None else []
    if source_signature != translation_signature and not (
        not source_signature and translation_signature == dynamic_signature
    ):
        defects.append(
            defect(
                "placeholder-mismatch",
                locale,
                key,
                "",
                "Android printf token position, type, or multiplicity differs from canonical",
            )
        )
    english_locale = (
        locale == "values-en"
        or locale.startswith("values-en-")
        or locale.startswith("values-b+en+")
    )
    reviewed_key = key in allowlist["keys"].get(locale, set())
    if (
        check_untranslated
        and source == translation
        and source
        and source not in allowlist["values"]
        and not reviewed_key
        and not english_locale
    ):
        defects.append(
            defect("untranslated-english", locale, key, "", "value matches canonical English"))


def locale_directories(res_directory):
    return sorted(
        directory
        for directory in res_directory.iterdir()
        if directory.is_dir() and LOCALE_DIRECTORY.fullmatch(directory.name)
    )


def audit(res_directory, allowlist):
    defects = []
    canonical = load_resources(res_directory / "values", "values", defects, res_directory)
    validate_resource_values(canonical, "values", res_directory, defects)
    locale_reports = []
    for directory in locale_directories(res_directory):
        locale = directory.name
        localized = load_resources(directory, locale, defects, res_directory)
        validate_resource_values(localized, locale, res_directory, defects)
        validate_locale(canonical, localized, locale, res_directory, allowlist, defects)
        canonical_keys = set(canonical)
        localized_keys = set(localized)
        matched_count = len(canonical_keys & localized_keys)
        locale_reports.append(
            {
                "coverage_percent": round(100 * matched_count / len(canonical_keys), 2) if canonical_keys else 100.0,
                "extra_key_count": len(localized_keys - canonical_keys),
                "locale": locale,
                "missing_key_count": len(canonical_keys - localized_keys),
                "present_key_count": matched_count,
            }
        )

    defects.sort(key=lambda item: (item["locale"], item["code"], item["key"], item["path"], item["detail"]))
    counts = Counter(item["code"] for item in defects)
    return {
        "canonical_key_count": len(canonical),
        "defects": defects,
        "locale_count": len(locale_reports),
        "locales": locale_reports,
        "summary": {"by_code": dict(sorted(counts.items())), "defect_count": len(defects)},
    }


def load_allowlist(path):
    with path.open(encoding="utf-8") as source:
        contents = json.load(source)
    values = contents.get("allowed_untranslated")
    if not isinstance(values, list) or not all(isinstance(value, str) for value in values):
        raise ValueError("allowed_untranslated must be a JSON array of strings")
    keys = contents.get("allowed_untranslated_keys", {})
    if not isinstance(keys, dict) or not all(
        isinstance(locale, str)
        and isinstance(locale_keys, list)
        and all(isinstance(key, str) for key in locale_keys)
        for locale, locale_keys in keys.items()
    ):
        raise ValueError(
            "allowed_untranslated_keys must be a JSON object of locale names to string arrays"
        )
    return {
        "keys": {locale: set(locale_keys) for locale, locale_keys in keys.items()},
        "values": set(values),
    }


def print_human_report(report):
    summary = report["summary"]
    print(
        "Translation audit: "
        f"{report['locale_count']} locales, {report['canonical_key_count']} canonical keys, "
        f"{summary['defect_count']} defects."
    )
    defects_by_locale = defaultdict(Counter)
    for item in report["defects"]:
        defects_by_locale[item["locale"]][item["code"]] += 1
    for locale in sorted(defects_by_locale):
        counts = ", ".join(
            f"{code}={count}" for code, count in sorted(defects_by_locale[locale].items())
        )
        print(f"  {locale}: {counts}")


def parse_arguments():
    parser = argparse.ArgumentParser(description="Validate localized Android string resources.")
    parser.add_argument(
        "--res-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res",
        help="Android res directory containing values and values-* directories",
    )
    parser.add_argument(
        "--allowlist",
        type=Path,
        default=Path(__file__).with_name("translation_allowlist.json"),
        help="JSON allowlist of reviewed untranslated values",
    )
    parser.add_argument("--json", action="store_true", help="write the audit report as JSON")
    return parser.parse_args()


def main():
    arguments = parse_arguments()
    try:
        allowlist = load_allowlist(arguments.allowlist)
        report = audit(arguments.res_dir, allowlist)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"translation audit configuration error: {error}", file=sys.stderr)
        return 2
    if arguments.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print_human_report(report)
    return 1 if report["defects"] else 0


if __name__ == "__main__":
    sys.exit(main())
