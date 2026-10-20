// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// An activity's notes on the web: the snippet beside the pencil, and the edit
// behind it. The snippet cases mirror the phone's NoteSnippetTest, so the two
// apps show the same words for the same note.
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";

const api = vi.hoisted(() => ({ patchActivity: vi.fn() }));
vi.mock("../api/client", () => ({ api }));

import { ActivityNotes, noteSnippet } from "../components/activity/cards/ActivityNotes";

describe("noteSnippet", () => {
  it("shows nothing for no note, including one cleared to whitespace", () => {
    expect(noteSnippet(null)).toBeNull();
    expect(noteSnippet("")).toBeNull();
    expect(noteSnippet("  \n ")).toBeNull();
  });

  it("shows a short note whole", () => {
    expect(noteSnippet("Felt flat today")).toBe("Felt flat today");
  });

  it("cuts a long note at a word and says there is more", () => {
    expect(noteSnippet("Legs heavy from Saturday's long run, kept it easy")).toBe("Legs heavy from Saturday's…");
  });

  it("uses the first line only, and marks the rest as more", () => {
    expect(noteSnippet("Windy\nGusts on the ridge to 60 km/h")).toBe("Windy…");
  });
});

describe("ActivityNotes", () => {
  beforeEach(() => api.patchActivity.mockReset().mockResolvedValue({}));

  it("saves the note and shows its first words beside the pencil", async () => {
    render(<ActivityNotes activity={{ id: 7, notes: null }} />);
    fireEvent.click(screen.getByRole("button", { name: "Notes" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Legs heavy from Saturday's long run" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.patchActivity).toHaveBeenCalledWith(7, { notes: "Legs heavy from Saturday's long run" }));
    expect(await screen.findByText("Legs heavy from Saturday's…")).toBeInTheDocument();
  });

  it("does not write a note that did not change", async () => {
    // A needless write stamps the field and could outrank a phone's newer note.
    render(<ActivityNotes activity={{ id: 7, notes: "Windy" }} />);
    fireEvent.click(screen.getByRole("button", { name: "Notes" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(api.patchActivity).not.toHaveBeenCalled();
  });
});
