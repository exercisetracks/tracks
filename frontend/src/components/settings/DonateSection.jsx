// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A standing, undismissable "support this project" card at the bottom of
// Settings — unlike DonateBanner, which is a gentle periodic nudge, this
// one is just always here for anyone who comes looking for it.
import { Section } from "./primitives";

export default function DonateSection() {
  return (
    <Section title="Support Tracks">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-slate-600 dark:text-slate-300">
          Tracks is free, open source, and ad-free. If it's useful to you, a donation helps keep it going.
        </p>
        <a href="https://ko-fi.com/hawkf" target="_blank" rel="noreferrer" className="btn btn-primary btn-sm shrink-0">
          Donate
        </a>
      </div>
    </Section>
  );
}
