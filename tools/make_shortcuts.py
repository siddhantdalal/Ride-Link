#!/usr/bin/env python3
"""
Builds Bike Link's iPhone Shortcuts as signed files, ready to AirDrop to the iPhone and import.

    python3 tools/make_shortcuts.py [--address 172.20.10.3] [--code ABCD-EFGH] [--no-sign]

Shortcuts made (each talks to the bike phone's command listener, port 8765, over the iPhone's hotspot):

    Send to Bike   Share sheet: a Google Maps / Apple Maps / WhatsApp location, a link, or text → navigate there
    Ride to        "Hey Siri, Ride to": asks where, then navigates
    Ride Home      "Hey Siri, Ride Home": navigates to the home address given at import
    Stop Ride      "Hey Siri, Stop Ride": ends navigation

On import, iOS asks for the bike phone's address, the bike code (both shown in Bike Link → Setup), and for Ride Home,
the home address. The --address and --code values only pre-fill those questions.

Signing uses macOS's `shortcuts sign` (mode "anyone"), which needs this Mac to be signed in to iCloud. Output goes to
build/shortcuts/, which git ignores: signed files carry the signer's identity, so they aren't committed.
"""

from __future__ import annotations  # modern type hints on the Python that ships with macOS

import argparse
import plistlib
import subprocess
import sys
import uuid
from pathlib import Path

PORT = 8765
ATTACHMENT = "￼"  # Shortcuts marks where a variable sits in text with this object-replacement character.

# Variables that point at earlier actions' results or at the shortcut's input.
SHORTCUT_INPUT = {"Type": "ExtensionInput"}


def output_of(action_uuid: str, name: str) -> dict:
    return {"Type": "ActionOutput", "OutputUUID": action_uuid, "OutputName": name}


def text(template: str, *variables: dict) -> dict:
    """Text with variables: each {} in the template becomes the next variable."""
    parts = template.split("{}")
    assert len(parts) == len(variables) + 1, template
    string, attachments = parts[0], {}
    for variable, rest in zip(variables, parts[1:]):
        # Shortcuts counts positions in UTF-16 code units, like NSString.
        position = len(string.encode("utf-16-le")) // 2
        attachments[f"{{{position}, 1}}"] = variable
        string += ATTACHMENT + rest
    return {"Value": {"string": string, "attachmentsByRange": attachments}, "WFSerializationType": "WFTextTokenString"}


def dictionary(**fields: dict) -> dict:
    """A dictionary of text fields (for HTTP headers and the JSON body)."""
    items = [
        {"WFItemType": 0, "WFKey": text(key), "WFValue": value}
        for key, value in fields.items()
    ]
    return {"Value": {"WFDictionaryFieldValueItems": items}, "WFSerializationType": "WFDictionaryFieldValue"}


def action(identifier: str, **parameters) -> dict:
    parameters.setdefault("UUID", str(uuid.uuid4()).upper())
    return {"WFWorkflowActionIdentifier": f"is.workflow.actions.{identifier}", "WFWorkflowActionParameters": parameters}


def uuid_of(step: dict) -> str:
    return step["WFWorkflowActionParameters"]["UUID"]


def settings_steps(address: str, code: str) -> tuple[list, list, dict, dict]:
    """The two Text actions every shortcut starts with: the bike phone's address and the bike code."""
    address_step = action("gettext", WFTextActionText=address)
    code_step = action("gettext", WFTextActionText=code)
    questions = [
        {
            "ActionIndex": 0,
            "Category": "Parameter",
            "DefaultValue": address,
            "ParameterKey": "WFTextActionText",
            "Text": "The bike phone's address (Bike Link → Setup)",
        },
        {
            "ActionIndex": 1,
            "Category": "Parameter",
            "DefaultValue": code,
            "ParameterKey": "WFTextActionText",
            "Text": "Your bike code (Bike Link → Setup)",
        },
    ]
    return [address_step, code_step], questions, output_of(uuid_of(address_step), "Text"), output_of(uuid_of(code_step), "Text")


def request_steps(address: dict, code: dict, path: str, destination: dict | None) -> list:
    """POST to the bike phone, then pick out the reply's message for Siri or a notification."""
    parameters = {
        "WFURL": text(f"http://{{}}:{PORT}{path}", address),
        "WFHTTPMethod": "POST",
        "ShowHeaders": True,
        "WFHTTPHeaders": dictionary(**{"X-Bike-Code": text("{}", code)}),
    }
    if destination is not None:
        parameters["WFHTTPBodyType"] = "JSON"
        parameters["WFJSONValues"] = dictionary(to=text("{}", destination))
    request = action("downloadurl", **parameters)
    message = action(
        "getvalueforkey",
        WFGetDictionaryValueType="Value",
        WFDictionaryKey="message",
        WFInput={"Value": output_of(uuid_of(request), "Contents of URL"), "WFSerializationType": "WFTextTokenAttachment"},
    )
    return [request, message]


def spoken_result(message_step: dict) -> dict:
    """Show Result: on screen, and read aloud when run through Siri (so it's heard in the helmet)."""
    return action("showresult", Text=text("{}", output_of(uuid_of(message_step), "Dictionary Value")))


def workflow(actions: list, questions: list, *, share_sheet: bool) -> dict:
    shortcut = {
        "WFWorkflowClientVersion": "2700",
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowActions": actions,
        "WFWorkflowImportQuestions": questions,
        "WFWorkflowTypes": ["ActionExtension"] if share_sheet else [],
        "WFWorkflowInputContentItemClasses": [
            "WFURLContentItem",
            "WFStringContentItem",
            "WFLocationContentItem",
        ] if share_sheet else [],
        "WFWorkflowHasShortcutInputVariables": share_sheet,
        "WFWorkflowHasOutputFallback": False,
        "WFWorkflowOutputContentItemClasses": [],
        "WFQuickActionSurfaces": [],
    }
    if share_sheet:
        # Run without anything shared (e.g. from the Shortcuts app), it asks for a place instead.
        shortcut["WFWorkflowNoInputBehavior"] = {
            "Name": "WFWorkflowNoInputBehaviorAskForInput",
            "Parameters": {"ItemClass": "WFStringContentItem"},
        }
    return shortcut


def send_to_bike(address: str, code: str) -> dict:
    steps, questions, address_var, code_var = settings_steps(address, code)
    request, message = request_steps(address_var, code_var, "/navigate", SHORTCUT_INPUT)
    notify = action(
        "notification",
        WFNotificationActionTitle="Bike Link",
        WFNotificationActionBody=text("{}", output_of(uuid_of(message), "Dictionary Value")),
    )
    return workflow(steps + [request, message, notify], questions, share_sheet=True)


def ride_to(address: str, code: str) -> dict:
    steps, questions, address_var, code_var = settings_steps(address, code)
    ask = action("ask", WFAskActionPrompt="Where to?", WFInputType="Text")
    request, message = request_steps(address_var, code_var, "/navigate", output_of(uuid_of(ask), "Provided Input"))
    return workflow(steps + [ask, request, message, spoken_result(message)], questions, share_sheet=False)


def ride_home(address: str, code: str) -> dict:
    steps, questions, address_var, code_var = settings_steps(address, code)
    home = action("gettext", WFTextActionText="")
    questions.append({
        "ActionIndex": 2,
        "Category": "Parameter",
        "DefaultValue": "",
        "ParameterKey": "WFTextActionText",
        "Text": "Your home address (street, area and city)",
    })
    request, message = request_steps(address_var, code_var, "/navigate", output_of(uuid_of(home), "Text"))
    return workflow(steps + [home, request, message, spoken_result(message)], questions, share_sheet=False)


def stop_ride(address: str, code: str) -> dict:
    steps, questions, address_var, code_var = settings_steps(address, code)
    request, message = request_steps(address_var, code_var, "/stop", destination=None)
    return workflow(steps + [request, message, spoken_result(message)], questions, share_sheet=False)


SHORTCUTS = {
    "Send to Bike": send_to_bike,
    "Ride to": ride_to,
    "Ride Home": ride_home,
    "Stop Ride": stop_ride,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--address", default="172.20.10.3", help="pre-fills the bike phone's address question")
    parser.add_argument("--code", default="", help="pre-fills the bike code question")
    parser.add_argument("--no-sign", action="store_true", help="only write the unsigned files")
    args = parser.parse_args()

    out = Path(__file__).resolve().parent.parent / "build" / "shortcuts"
    unsigned = out / "unsigned"
    unsigned.mkdir(parents=True, exist_ok=True)
    for name, build in SHORTCUTS.items():
        source = unsigned / f"{name}.shortcut"
        with source.open("wb") as f:
            plistlib.dump(build(args.address, args.code), f, fmt=plistlib.FMT_BINARY)
        if args.no_sign:
            print(f"wrote {source}")
            continue
        signed = out / f"{name}.shortcut"
        result = subprocess.run(
            ["shortcuts", "sign", "--mode", "anyone", "--input", str(source), "--output", str(signed)],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print(f"signing {name} failed: {result.stderr.strip() or result.stdout.strip()}", file=sys.stderr)
            return 1
        print(f"signed {signed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
