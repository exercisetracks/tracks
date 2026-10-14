// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Esc on the map page puts panels away one at a time, the last one used first.
import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { usePanelStack } from "../pages/maps/hooks/usePanelStack";

const esc = (target = document.body) =>
  act(() => { target.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })); });

describe("usePanelStack", () => {
  it("closes the most recently opened first, then the one before", () => {
    const closeA = vi.fn(), closeB = vi.fn();
    const { rerender } = renderHook((p) => usePanelStack(p), {
      initialProps: { a: { open: true, close: closeA }, b: { open: false, close: closeB } },
    });
    rerender({ a: { open: true, close: closeA }, b: { open: true, close: closeB } });
    esc();
    expect(closeB).toHaveBeenCalledTimes(1);
    expect(closeA).not.toHaveBeenCalled();
    rerender({ a: { open: true, close: closeA }, b: { open: false, close: closeB } });
    esc();
    expect(closeA).toHaveBeenCalledTimes(1);
  });

  it("counts a click inside a panel as using it", () => {
    const closeA = vi.fn(), closeB = vi.fn();
    const { result, rerender } = renderHook((p) => usePanelStack(p), {
      initialProps: { a: { open: true, close: closeA }, b: { open: false, close: closeB } },
    });
    rerender({ a: { open: true, close: closeA }, b: { open: true, close: closeB } });
    act(() => result.current("a"));
    esc();
    expect(closeA).toHaveBeenCalledTimes(1);
    expect(closeB).not.toHaveBeenCalled();
  });

  it("leaves Esc to a text field being typed in", () => {
    const closeA = vi.fn();
    renderHook(() => usePanelStack({ a: { open: true, close: closeA } }));
    const input = document.createElement("input");
    document.body.appendChild(input);
    esc(input);
    expect(closeA).not.toHaveBeenCalled();
    input.remove();
  });
});
