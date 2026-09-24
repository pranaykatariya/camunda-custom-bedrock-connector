#!/usr/bin/env python3
"""
Derive the Barclays AI Agent element templates from Camunda's OFFICIAL templates.

Nothing is hand-copied: the script downloads the official templates of the exact connectors version
declared in pom.xml (<version.connectors>) and applies a small, reviewable set of changes:

  1. new template id / name        (so it never clashes with Camunda's template in Modeler)
  2. task definition type          -> the custom job type served by this runtime
  3. provider dropdown             -> "AWS Bedrock" only (hidden), other providers removed
  4. Bedrock authentication        -> fixed to "defaultCredentialsChain" (hidden, no fields). The
                                      runtime ignores it and sends Barclays credentials instead;
                                      access key, secret key and API key fields are removed.
  5. Bedrock custom endpoint       -> required (the Barclays Bedrock gateway URL), with an
                                      optional default value (--gateway-url)

Every other property (region, model, max tokens, temperature, top P, timeout, prompts, tools,
memory, limits, events, response, retries, ...) is left exactly as Camunda ships it.

Usage:
  python3 scripts/generate-element-templates.py \
      [--task-type barclays.ai-gateway:aiagent:1] \
      [--subprocess-type barclays.ai-gateway:aiagent-job-worker:1] \
      [--gateway-url https://bedrock-gateway.example.com/bedrock] \
      [--connectors-version 8.9.12] [--source-dir DIR] [--output-dir element-templates]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
UPSTREAM = (
    "https://raw.githubusercontent.com/camunda/connectors/{version}/"
    "connectors/agentic-ai/element-templates/{file}"
)

TEMPLATES = [
    {
        "upstream": "agenticai-aiagent-outbound-connector.json",
        "output": "barclays-ai-agent-task.json",
        "id": "com.barclays.groupcontrol.co.camunda.connectors.aiagent.v1",
        "name": "Barclays AI Agent",
        "type_arg": "task_type",
    },
    {
        "upstream": "agenticai-aiagent-job-worker.json",
        "output": "barclays-ai-agent-subprocess.json",
        "id": "com.barclays.groupcontrol.co.camunda.connectors.aiagent.jobworker.v1",
        "name": "Barclays AI Agent Sub-process",
        "type_arg": "subprocess_type",
    },
]

PROVIDER = "bedrock"
AUTH_TYPE_ID = "provider.bedrock.authentication.type"
FIXED_AUTH_TYPE = "defaultCredentialsChain"
ENDPOINT_ID = "provider.bedrock.endpoint"
REMOVED_PROPERTY_IDS = {
    "provider.bedrock.authentication.accessKey",
    "provider.bedrock.authentication.secretKey",
    "provider.bedrock.authentication.apiKey",
}


def connectors_version_from_pom() -> str:
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<version\.connectors>([^<]+)</version\.connectors>", pom)
    if not match:
        sys.exit("Could not find <version.connectors> in pom.xml")
    return match.group(1).strip()


def load_upstream(file: str, version: str, source_dir: str | None) -> dict:
    if source_dir:
        return json.loads((pathlib.Path(source_dir) / file).read_text(encoding="utf-8"))
    url = UPSTREAM.format(version=version, file=file)
    with urllib.request.urlopen(url, timeout=30) as response:  # noqa: S310 (fixed https host)
        return json.load(response)


def is_other_provider_property(prop: dict) -> bool:
    """Properties that only belong to another provider (bedrock, anthropic, ...)."""
    prop_id = prop.get("id") or ""
    binding_name = (prop.get("binding") or {}).get("name") or ""
    key = prop_id or binding_name
    if not key.startswith("provider.") or key == "provider.type":
        return False
    return not key.startswith(f"provider.{PROVIDER}.")


def customize(template: dict, spec: dict, job_type: str, gateway_url: str | None, version: str) -> dict:
    upstream_id = template.get("id")
    template["id"] = spec["id"]
    template["name"] = spec["name"]
    upstream_description = template.get("description", "").rstrip()
    if upstream_description and not upstream_description.endswith("."):
        upstream_description += "."
    template["description"] = (
        upstream_description
        + " Uses AWS Bedrock through the Barclays Bedrock gateway with runtime-managed credentials."
    ).lstrip()
    metadata = template.setdefault("metadata", {})
    metadata["keywords"] = ["Barclays", *metadata.get("keywords", [])]
    metadata["derivedFrom"] = {
        "id": upstream_id,
        "connectorsVersion": version,
    }

    properties = []
    task_type_set = False
    provider_type_set = False
    auth_type_set = False
    endpoint_set = False
    for prop in template["properties"]:
        binding = prop.get("binding") or {}

        if prop.get("id") in REMOVED_PROPERTY_IDS:
            continue
        if is_other_provider_property(prop):
            continue

        if binding.get("type") == "zeebe:taskDefinition" and binding.get("property") == "type":
            prop["value"] = job_type
            prop["type"] = "Hidden"
            task_type_set = True

        if prop.get("id") == "provider.type":
            if PROVIDER not in [c["value"] for c in prop.get("choices", [])]:
                sys.exit(f"{spec['upstream']}: provider '{PROVIDER}' no longer offered upstream")
            prop.pop("choices", None)  # Hidden properties carry a fixed value, no choices
            prop["value"] = PROVIDER
            prop["type"] = "Hidden"
            provider_type_set = True

        if prop.get("id") == AUTH_TYPE_ID:
            if FIXED_AUTH_TYPE not in [c["value"] for c in prop.get("choices", [])]:
                sys.exit(f"{spec['upstream']}: Bedrock authentication '{FIXED_AUTH_TYPE}' no longer offered upstream")
            prop.pop("choices", None)
            prop["value"] = FIXED_AUTH_TYPE
            prop["type"] = "Hidden"
            auth_type_set = True

        if prop.get("id") == ENDPOINT_ID:
            prop["label"] = "Barclays Bedrock gateway endpoint"
            prop["optional"] = False
            prop["constraints"] = {"notEmpty": True}
            prop["tooltip"] = (
                "Base URL of the Barclays Bedrock gateway. The runtime sends the Barclays "
                "credentials to exactly this URL; it is not validated against an allow-list. "
                "Authentication is handled by the connector runtime; no AWS keys are needed."
            )
            if gateway_url:
                prop["value"] = gateway_url
            endpoint_set = True

        properties.append(prop)

    if not (task_type_set and provider_type_set and auth_type_set and endpoint_set):
        sys.exit(
            f"{spec['upstream']}: upstream template structure changed "
            f"(task type found: {task_type_set}, provider.type found: {provider_type_set}, "
            f"{AUTH_TYPE_ID} found: {auth_type_set}, {ENDPOINT_ID} found: {endpoint_set}). "
            "Review the template before continuing."
        )

    template["properties"] = properties
    return template


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--connectors-version", default=None)
    parser.add_argument("--task-type", default="barclays.ai-gateway:aiagent:1")
    parser.add_argument("--subprocess-type", default="barclays.ai-gateway:aiagent-job-worker:1")
    parser.add_argument("--gateway-url", default=None)
    parser.add_argument("--source-dir", default=None, help="read upstream templates from here instead of GitHub")
    parser.add_argument("--output-dir", default=str(ROOT / "element-templates"))
    args = parser.parse_args()

    version = args.connectors_version or connectors_version_from_pom()
    out_dir = pathlib.Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    for spec in TEMPLATES:
        upstream = load_upstream(spec["upstream"], version, args.source_dir)
        job_type = getattr(args, spec["type_arg"])
        result = customize(upstream, spec, job_type, args.gateway_url, version)
        target = out_dir / spec["output"]
        target.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {target.relative_to(ROOT) if target.is_relative_to(ROOT) else target} "
              f"(from {spec['upstream']} @ {version}, job type {job_type})")


if __name__ == "__main__":
    main()
