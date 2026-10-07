// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The web's half of "which side is behind", and who is told how to update.
// The comparison cases mirror the server's and the phone's
// (backend test_version_status.py, mobile ReleaseVersionTest): three clients
// disagreeing about whether an update exists is worse than any one being wrong.
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { isNewer, versionOf } from "../lib/versions";
import { deviceStatus } from "../components/settings/VersionSection";

const auth = vi.hoisted(() => ({ user: null }));
vi.mock("../auth/AuthContext", () => ({ useAuth: () => auth }));
const api = vi.hoisted(() => ({ getVersionStatus: vi.fn() }));
vi.mock("../api/client", () => ({ api }));

import ServerUpdateBanner from "../components/ServerUpdateBanner";

describe("isNewer", () => {
  it.each([
    ["1.2.0", "1.1.9"],
    ["1.10.0", "1.9.0"],
    ["v2.0.0", "1.99.99"],
    ["1.2.0", "1.2.0-beta.1"],
    ["1.2.0-beta.2", "1.2.0-beta.1"],
  ])("%s is newer than %s, by number not by text", (newer, older) => {
    expect(isNewer(newer, older)).toBe(true);
    expect(isNewer(older, newer)).toBe(false);
  });

  it("never calls an unparseable version an update", () => {
    expect(isNewer("nightly", "1.0.0")).toBe(false);
    expect(isNewer("2.0.0", undefined)).toBe(false);
  });
});

describe("a phone's status", () => {
  it("reads the version out of what the phone reported, debug suffix and all", () => {
    expect(versionOf("android/1.1.3 (10103)")).toBe("1.1.3");
    expect(versionOf("android/1.1.3-debug (10103)")).toBe("1.1.3");
  });

  it("puts drift from its own server ahead of the newest release", () => {
    // A phone behind its server is the thing to fix, even when a newer
    // release also exists: the matching APK is what keeps sync working.
    expect(deviceStatus("1.1.0", "1.1.3", "1.2.0").text).toBe("Behind server");
    expect(deviceStatus("1.2.0", "1.1.3", "1.2.0").text).toBe("Ahead of server");
    expect(deviceStatus("1.1.3", "1.1.3", "1.2.0").text).toBe("1.2.0 available");
    expect(deviceStatus("1.2.0", "1.2.0", "1.2.0").text).toBe("Up to date");
  });
});

describe("the server update banner", () => {
  const behind = {
    server_version: "1.1.3",
    server_update_available: true,
    latest: { version: "1.2.0", url: "https://github.com/exercisetracks/tracks/releases/tag/v1.2.0" },
  };

  beforeEach(() => {
    api.getVersionStatus.mockReset().mockResolvedValue(behind);
    localStorage.clear();
  });

  it("is not shown to someone who cannot run the update", async () => {
    auth.user = { is_admin: false };
    const { container } = render(<ServerUpdateBanner />);
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(api.getVersionStatus).not.toHaveBeenCalled();
  });

  it("gives an admin the commands, backup first", async () => {
    auth.user = { is_admin: true };
    render(<ServerUpdateBanner />);
    await screen.findByText("1.2.0");
    fireEvent.click(screen.getByText("How to update"));
    const commands = screen.getAllByText(/docker /).map(n => n.textContent);
    expect(commands[0]).toMatch(/tracks-backup/);
    expect(commands.some(c => c.includes("docker compose pull"))).toBe(true);
  });

  it("stays dismissed for that release only", async () => {
    auth.user = { is_admin: true };
    const first = render(<ServerUpdateBanner />);
    fireEvent.click(await screen.findByText("Dismiss"));
    expect(first.container).toBeEmptyDOMElement();
    first.unmount();

    api.getVersionStatus.mockResolvedValue({ ...behind, latest: { ...behind.latest, version: "1.3.0" } });
    render(<ServerUpdateBanner />);
    await screen.findByText("1.3.0");
  });

  it("says nothing when the server is current", async () => {
    auth.user = { is_admin: true };
    api.getVersionStatus.mockResolvedValue({ ...behind, server_update_available: false });
    const { container } = render(<ServerUpdateBanner />);
    await waitFor(() => expect(api.getVersionStatus).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
