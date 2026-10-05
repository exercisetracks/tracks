// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/design.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.

/** Tailwind `theme.extend`, from spec/design.yaml. */
export const designTheme = {
  "fontFamily": {
    "sans": [
      "Inter",
      "ui-sans-serif",
      "system-ui",
      "-apple-system",
      "Segoe UI",
      "Roboto",
      "sans-serif"
    ]
  },
  "borderRadius": {
    "none": "0px",
    "sm": "0.125rem",
    "DEFAULT": "0.25rem",
    "md": "0.375rem",
    "lg": "0.5rem",
    "xl": "0.75rem",
    "2xl": "1rem",
    "full": "9999px"
  },
  "spacing": {
    "0": "0px",
    "0.5": "0.125rem",
    "1": "0.25rem",
    "1.5": "0.375rem",
    "2": "0.5rem",
    "2.5": "0.625rem",
    "3": "0.75rem",
    "3.5": "0.875rem",
    "4": "1rem",
    "5": "1.25rem",
    "6": "1.5rem",
    "8": "2rem",
    "10": "2.5rem",
    "12": "3rem",
    "16": "4rem"
  },
  "fontSize": {
    "2xs": [
      "0.625rem",
      {
        "lineHeight": "0.875rem"
      }
    ],
    "xs": [
      "0.75rem",
      {
        "lineHeight": "1rem"
      }
    ],
    "sm": [
      "0.875rem",
      {
        "lineHeight": "1.25rem"
      }
    ],
    "base": [
      "1rem",
      {
        "lineHeight": "1.5rem"
      }
    ],
    "lg": [
      "1.125rem",
      {
        "lineHeight": "1.75rem"
      }
    ],
    "xl": [
      "1.25rem",
      {
        "lineHeight": "1.75rem"
      }
    ],
    "2xl": [
      "1.5rem",
      {
        "lineHeight": "2rem"
      }
    ],
    "3xl": [
      "1.875rem",
      {
        "lineHeight": "2.25rem"
      }
    ],
    "4xl": [
      "2.25rem",
      {
        "lineHeight": "2.5rem"
      }
    ]
  },
  "letterSpacing": {
    "tight": "-0.025em",
    "normal": "0em",
    "wide": "0.025em",
    "wider": "0.05em"
  },
  "fontWeight": {
    "normal": "400",
    "medium": "500",
    "semibold": "600",
    "bold": "700"
  }
};

/** Component specs, for code that styles outside Tailwind (charts, canvases). */
export const designComponents = {
  "card": {
    "radius": "xl",
    "padding": "3.5",
    "border": 1
  },
  "section_header": {
    "type": "xs",
    "weight": "semibold",
    "tracking": "wider",
    "uppercase": true
  },
  "stat_value": {
    "type": "2xl",
    "weight": "bold",
    "tabular": true
  },
  "stat_label": {
    "type": "xs",
    "weight": "medium",
    "tracking": "wide",
    "uppercase": true
  },
  "chip": {
    "type": "xs",
    "radius": "DEFAULT",
    "padding_x": "1.5",
    "padding_y": "0.5"
  },
  "pill": {
    "type": "2xs",
    "weight": "semibold",
    "radius": "full",
    "padding_x": "1.5",
    "padding_y": "0.5"
  },
  "button": {
    "type": "sm",
    "weight": "medium",
    "radius": "full",
    "height": "10",
    "height_sm": "8",
    "padding_x": "4",
    "padding_x_sm": "3",
    "padding_y": "0",
    "tonal_alpha": 0.12,
    "neutral_alpha": 0.08,
    "variants": [
      "primary",
      "tonal",
      "neutral",
      "danger"
    ]
  }
};
