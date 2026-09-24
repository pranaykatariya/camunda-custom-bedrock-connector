#!/usr/bin/env python3
"""
Render the Confluence figures: Mermaid source -> themed PNG -> framed figure PNG.

    src/<name>.mmd  --mermaid-cli + theme/-->  .build/<name>.png  --Pillow frame-->  <name>.png

Every figure gets the same frame: figure number, title, subtitle, the diagram on a dotted canvas,
and a legend that lists only the colours that figure uses. Upload the resulting <name>.png files
to the Confluence page as attachments (see ../README.md).

Requirements: Node.js (for npx), Python 3.9+ and Pillow (pip install pillow).
Icons come from Iconify packs (logos, mdi), fetched by mermaid-cli at render time.

Usage:
    python3 confluence/diagrams/render.py              # all figures
    python3 confluence/diagrams/render.py 05 09        # only figures whose file name starts with 05 or 09
"""

from __future__ import annotations

import subprocess
import sys
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
SRC, BUILD, THEME = HERE / "src", HERE / ".build", HERE / "theme"

MERMAID_CLI = "@mermaid-js/mermaid-cli@11.17.0"
ICON_PACKS = ["@iconify-json/logos", "@iconify-json/mdi"]
SCALE = 2  # every size below is in CSS pixels and multiplied by SCALE

FOOTER = "barclays-ai-agent-connector  ·  Camunda connectors 8.9.12"

# ---------------------------------------------------------------------------------------------
# Legend entries: key -> (label, fill, stroke, dashed). Keys match the classDefs in src/*.mmd.
# ---------------------------------------------------------------------------------------------
LEGEND = {
    "cam": ("Camunda / third-party · unchanged", "#EEF3FB", "#3B6FD8", False),
    "ours": ("This project", "#FFF1E6", "#F07A1A", False),
    "ext": ("External service", "#EAF7EF", "#22A05A", False),
    "cfg": ("Configuration · design time", "#F1ECFF", "#7A5AF0", False),
    "bad": ("Failure path", "#FDECEE", "#D8414F", False),
    "ghost": ("Inactive · out of scope", "#F6F7F9", "#B6BFCC", True),
    "navy": ("The integration seam", "#16253D", "#16253D", False),
    # figure-specific
    "g-cache": ("Token served from cache", "#E3F5EA", "#22A05A", False),
    "g-skew": ("Early expiry (refresh skew)", "#FFE9D6", "#F07A1A", False),
    "g-revoked": ("Revoked by a gateway 401", "#FDECEE", "#D8414F", False),
    "s-stale": ("Stale · must refresh", "#FFF8E1", "#D69E2E", False),
    "q-perm": ("Permanent", "#D8414F", "#D8414F", False),
    "q-trans": ("Transient", "#F07A1A", "#F07A1A", False),
    "q-retry": ("Handled by the 401 retry", "#22A05A", "#22A05A", False),
    "q-std": ("Standard connector handling", "#94A3B8", "#94A3B8", False),
    "seq-ours": ("This project's lanes", "#FDF3EA", "#F3C39A", False),
    "seq-cam": ("Camunda / LangChain4j / AWS SDK lanes", "#EFF3FA", "#C9D6EE", False),
    "seq-net": ("Barclays network", "#EDF7F1", "#A8DDBC", False),
}

# ---------------------------------------------------------------------------------------------
# Figures, in page order. width = mermaid-cli page width in CSS px (wide diagrams need more).
# ---------------------------------------------------------------------------------------------
FIGURES = [
    ("01-before-after", "Overview", "One change: how Bedrock authenticates",
     "Same AI Agent, same Converse request body. The signing step is gone; the Barclays gateway receives a cached BAM token instead.",
     ["cam", "ours"], 1500),
    ("02-layers", "Architecture · layers", "Who owns what at runtime",
     "Read top to bottom: a Zeebe job becomes an agent turn, the one replaced bean routes it, and only the Bedrock column carries this project's code.",
     ["cam", "navy", "ours", "ext", "ghost"], 1400),
    ("03-deployment", "Architecture · deployment", "Where everything runs",
     "The jar lives inside the Camunda connector runtime pod. Secrets arrive as environment variables; tokens flow only inside the Barclays network.",
     ["cam", "ours", "ext"], 1700),
    ("04-bean-override", "Architecture · the seam", "One Spring bean replaced, one router added",
     "Camunda declares ChatModelFactory as @ConditionalOnMissingBean. This auto-configuration runs first, so Camunda's default never gets created.",
     ["cam", "navy", "ours", "ghost"], 1500),
    ("05-request-lifecycle", "Request lifecycle", "One agent turn, end to end",
     "Credentials are checked twice on purpose: before the model is called (a BAM outage sends nothing) and on every HTTP attempt (SDK retries get fresh headers).",
     ["seq-cam", "seq-ours", "seq-net"], 2000),
    ("06-token-timeline", "Token management", "Twenty-three minutes in the life of one pod",
     "Tokens live ten minutes but are replaced after nine. A revoked token is dropped on the first 401 and replaced at once, without waiting for its expiry.",
     ["g-cache", "g-skew", "g-revoked"], 1500),
    ("07-token-states", "Token management", "What the cache can be doing",
     "A failed refresh is never cached: callers already waiting share the failure, and the next new caller tries again.",
     ["ghost", "ours", "g-cache", "s-stale", "bad"], 1500),
    ("08-single-flight", "Token management · concurrency", "Three jobs, one BAM call",
     "Single-flight refresh prevents a stampede when many agents start at once. BamTokenCacheTest proves it with 64 threads.",
     [], 1400),
    ("09-retry-401", "Failure handling", "A 401 from the gateway: invalidate, refresh, resend once",
     "Every response, including the final 401/403, goes back to the SDK unchanged. Sanitizing happens one layer up.",
     ["cam", "ours", "ext", "bad"], 1300),
    ("10-failure-quadrant", "Failure handling", "Every failure, placed by where and how long",
     "Top half: a person must fix configuration. Bottom half: Camunda's job retries will usually recover on their own.",
     ["q-perm", "q-trans", "q-retry", "q-std"], 1300),
    ("11-failure-taxonomy", "Failure handling", "Two exception types, one outcome",
     "Both exception types carry a fixed, sanitized message and no cause. Everything ends as FAILED_MODEL_CALL, exactly like the standard connector.",
     ["bad", "ours", "ghost", "cam"], 1700),
    ("12-config-map", "Configuration", "Every setting on one page",
     "All keys live under barclays.ai-gateway.auth. Only the three BAM secrets are required.",
     [], 1600),
    ("13-startup", "Configuration", "What happens at startup",
     "validate() collects every problem before failing, so one restart shows every mistake, and never a secret value.",
     ["cfg", "ours", "ext", "bad", "ghost"], 1800),
    ("14-trust-boundaries", "Security", "Where secrets live, and where they travel",
     "Credentials never touch BPMN. The one soft spot: the element's custom endpoint decides where the token is sent.",
     ["cfg", "ours", "bad", "ghost"], 1700),
    ("15-element-templates", "BPMN and Modeler", "Templates generated, never hand-edited",
     "The script downloads Camunda's official templates for the exact connectors version and applies five reviewable changes.",
     ["ours", "cfg"], 1800),
    ("16-code-map", "Developer guide", "Four packages, one direction of dependency",
     "config → camunda → transport → auth. If Camunda ever supports custom Bedrock auth, delete camunda/ and keep the rest.",
     ["cfg", "ours", "ghost"], 1900),
    ("17-auth-classes", "Developer guide", "The auth and transport core",
     "GatewayCredentials is compared by identity, not equality. That is what makes invalidate() precise under concurrency.",
     ["ours", "cam"], 1600),
    ("18-test-pyramid", "Developer guide · testing", "Eleven suites, zero external services",
     "Every layer runs real Camunda, LangChain4j and AWS SDK classes against in-process fake servers. ./mvnw verify runs all of them.",
     ["navy", "ours", "cam", "ghost"], 1500),
    ("19-upgrade", "Maintenance", "Upgrading the Camunda connectors version",
     "One property pins every version. The javap step catches upstream changes to the Bedrock setup this project copies.",
     ["cfg", "ours", "bad", "ext"], 1200),
]

# ---------------------------------------------------------------------------------------------
# Frame styling
# ---------------------------------------------------------------------------------------------
INK, SLATE, MUTED, ACCENT = "#0F1B2D", "#5B6B82", "#94A3B8", "#D9620E"
CARD, BORDER, PANEL, PANEL_BORDER, DOT = "#FFFFFF", "#E2E8F0", "#F8FAFD", "#E8EDF4", "#DCE3ED"

AVENIR = "/System/Library/Fonts/Avenir Next.ttc"  # macOS; falls back to Pillow's default elsewhere
FACE = {"bold": 0, "demi": 2, "medium": 5, "regular": 7}


def font(face: str, size: float) -> ImageFont.ImageFont:
    try:
        return ImageFont.truetype(AVENIR, int(size * SCALE), index=FACE[face])
    except OSError:
        return ImageFont.load_default(size=int(size * SCALE))


def px(v: float) -> int:
    return int(round(v * SCALE))


def render_mermaid(name: str, width: int) -> Path:
    BUILD.mkdir(exist_ok=True)
    out = BUILD / f"{name}.png"
    cmd = [
        "npx", "-y", MERMAID_CLI,
        "-i", str(SRC / f"{name}.mmd"), "-o", str(out),
        "-c", str(THEME / "mermaid.json"), "-C", str(THEME / "diagram.css"),
        "-b", "transparent", "-s", str(SCALE), "-w", str(width),
        "--iconPacks", *ICON_PACKS,
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    return out


def spaced(draw: ImageDraw.ImageDraw, xy, text: str, f, fill, tracking: float) -> None:
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=f, fill=fill)
        x += draw.textlength(ch, font=f) + px(tracking)


def wrap(draw, text: str, f, max_w: int) -> list[str]:
    words, lines, line = text.split(), [], ""
    for w in words:
        trial = f"{line} {w}".strip()
        if draw.textlength(trial, font=f) <= max_w:
            line = trial
        else:
            lines.append(line)
            line = w
    return lines + [line] if line else lines


def frame(name: str, number: int, kicker: str, title: str, subtitle: str, legend: list[str], diagram: Path) -> Path:
    art = Image.open(diagram).convert("RGBA")
    pad, inset, panel_pad = px(44), px(28), px(36)
    min_w = px(1100)
    width = max(min_w, art.width + 2 * (inset + panel_pad))
    scratch = ImageDraw.Draw(Image.new("RGBA", (10, 10)))

    f_kicker, f_title, f_sub = font("demi", 13), font("bold", 30), font("regular", 17)
    f_leg, f_foot = font("medium", 14), font("regular", 13)

    sub_lines = wrap(scratch, subtitle, f_sub, min(width - 2 * pad, px(980)))
    header_h = px(36) + px(20) + px(12) + px(40) + px(8) + len(sub_lines) * px(26) + px(26)
    panel_h = art.height + 2 * panel_pad
    footer_h = px(74)
    height = header_h + panel_h + footer_h

    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, width - 1, height - 1], radius=px(22), fill=CARD, outline=BORDER, width=px(1))

    # header
    y = px(36)
    spaced(d, (pad, y), f"FIGURE {number:02d}  ·  {kicker.upper()}", f_kicker, ACCENT, 1.4)
    y += px(20) + px(12)
    d.text((pad, y), title, font=f_title, fill=INK)
    y += px(40) + px(8)
    for line in sub_lines:
        d.text((pad, y), line, font=f_sub, fill=SLATE)
        y += px(26)

    # dotted canvas
    top = header_h
    box = [inset, top, width - inset, top + panel_h]
    panel = Image.new("RGBA", (box[2] - box[0], box[3] - box[1]), (0, 0, 0, 0))
    pd = ImageDraw.Draw(panel)
    pd.rounded_rectangle([0, 0, panel.width - 1, panel.height - 1], radius=px(16), fill=PANEL, outline=PANEL_BORDER, width=px(1))
    step, r = px(22), max(1, px(1.1))
    for gx in range(step, panel.width - step // 2, step):
        for gy in range(step, panel.height - step // 2, step):
            pd.ellipse([gx - r, gy - r, gx + r, gy + r], fill=DOT)
    panel.alpha_composite(art, ((panel.width - art.width) // 2, panel_pad))
    img.alpha_composite(panel, (box[0], box[1]))

    # footer: legend left, provenance right
    fy = top + panel_h + px(28)
    x = pad
    for key in legend:
        label, fill, stroke, dashed = LEGEND[key]
        s = px(14)
        d.rounded_rectangle([x, fy + px(3), x + s, fy + px(3) + s], radius=px(4), fill=fill,
                            outline=stroke, width=px(2) if not dashed else px(1.5))
        x += s + px(8)
        d.text((x, fy), label, font=f_leg, fill="#475569")
        x += int(d.textlength(label, font=f_leg)) + px(22)
    foot_w = d.textlength(FOOTER, font=f_foot)
    if x + foot_w < width - pad:
        d.text((width - pad - foot_w, fy + px(1)), FOOTER, font=f_foot, fill=MUTED)

    out = HERE / f"{name}.png"
    img.save(out, optimize=True)
    return out


def main(filters: list[str]) -> None:
    for number, (name, kicker, title, subtitle, legend, width) in enumerate(FIGURES, start=1):
        if filters and not any(name.startswith(f) for f in filters):
            continue
        print(f"[{number:02d}] {name}")
        out = frame(name, number, kicker, title, subtitle, legend, render_mermaid(name, width))
        with Image.open(out) as im:
            print(textwrap.indent(f"-> {out.name}  {im.width}x{im.height}", "     "))


if __name__ == "__main__":
    main(sys.argv[1:])
