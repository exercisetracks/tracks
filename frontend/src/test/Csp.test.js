// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The released app's Content-Security-Policy, checked against what it serves.
 *
 * The production policy (caddy/Caddyfile, csp-static) allows exactly one
 * inline script — the theme snippet in index.html — by its hash. Edit the
 * snippet without updating the hash and the browser silently refuses it: the
 * page loads, in the wrong theme, with nothing to say why. This fails first.
 *
 * It also pins what made the policy worth having: up to 1.2.1 the released
 * image shipped the development policy, with 'unsafe-inline' and 'unsafe-eval',
 * and nothing noticed.
 */
import { describe, expect, it } from "vitest";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(__dirname, "..", "..");
const caddyfile = readFileSync(join(ROOT, "..", "caddy", "Caddyfile"), "utf8");
const indexHtml = readFileSync(join(ROOT, "index.html"), "utf8");

function policy(snippet) {
  const block = caddyfile.match(new RegExp(`\\(${snippet}\\) \\{([\\s\\S]*?)\\n\\}`));
  expect(block, `snippet (${snippet}) in the Caddyfile`).not.toBeNull();
  return block[1].match(/Content-Security-Policy "([^"]+)"/)[1];
}

function directive(csp, name) {
  const found = csp.split(";").map((d) => d.trim()).find((d) => d.startsWith(`${name} `));
  return found ? found.split(/\s+/).slice(1) : [];
}

describe("the released app's CSP", () => {
  const csp = policy("csp-static");

  it("allows every inline script in index.html by its hash", () => {
    const inline = [...indexHtml.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
    expect(inline.length).toBeGreaterThan(0);
    for (const body of inline) {
      const hash = `'sha256-${createHash("sha256").update(body).digest("base64")}'`;
      expect(directive(csp, "script-src"), "update the hash in caddy/Caddyfile").toContain(hash);
    }
  });

  it("allows no other inline script and no eval", () => {
    const scripts = directive(csp, "script-src");
    expect(scripts).not.toContain("'unsafe-inline'");
    expect(scripts).not.toContain("'unsafe-eval'");
  });

  it("connects to this origin only, so a token cannot be sent elsewhere", () => {
    const connect = directive(csp, "connect-src");
    expect(connect).not.toContain("ws:");
    expect(connect).not.toContain("wss:");
    expect(connect.filter((s) => /^https?:/.test(s))).toEqual([]);
  });

  it("is the one the static image uses", () => {
    expect(caddyfile).toContain("import csp-{$TRACKS_FRONTEND:proxy}");
    const dockerfile = readFileSync(join(ROOT, "..", "caddy", "Dockerfile"), "utf8");
    expect(dockerfile).toMatch(/ENV TRACKS_FRONTEND=static/);
  });
});
