<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# How the plan adapts to training it did not plan

The reasoning behind `backend/app/calculators/plan/adapt.py` (with its phone
port `PlanAdapt.kt`) and `build_ramp` in `calculators/plan/base.py`. Settled
with the maintainer on 2026-10-18:

- fitness and fatigue are the dashboard's own CTL and ATL;
- sport-specific fitness is tracked beside overall fitness;
- the plan regenerates after every import, so it always reflects current
  fitness, and nothing "re-plans" a missed week separately;
- under fatigue a session is shortened first, and moved only when the fatigue
  is severe.

Every number below is a heuristic built on the cited work. None of it is a
validated model, and all of it is a named constant in the code, which can be
tuned.

## Fitness: the sport sets the floor, overall fitness the ceiling

**What the code does.** An event plan starts from the athlete's recent weekly
volume in the sport: the median of the weeks they did it, so one skipped week
does not lower it. That is the floor. Build weeks then grow by 10% a week. When
overall CTL is at least 1.25 × the sport's own CTL, they grow by 12% (running,
hiking) or 15% (low-impact sports) instead.

**Why overall fitness may speed the ramp.** Central aerobic fitness
transfers between endurance sports, partly but meaningfully. Cycling and
swimming raise running VO₂max less than running does, but they do raise it
(Tanaka 1994, *Sports Med* 18:330). Triathletes show the same transfer on
the cardiovascular side (Millet, Vleck & Bentley 2009, *Sports Med* 39:179).
So a fit cyclist starting to run can take more running load each week than a
sedentary beginner.

**Why not much faster.** What transfers is the heart and lungs. What does not
is the tissue a sport loads specifically: bone and tendon for running. They
adapt over months, not weeks (Magnusson & Kjaer 2019, *J Physiol* 597:1283),
and running injuries are overuse injuries of exactly those tissues (Hreljac
2004, *Med Sci Sports Exerc* 36:845). In novice runners, increasing weekly
distance by more than about 30% over two weeks was associated with more
injuries (Nielsen et al. 2014, *JOSPT* 44:739). The classic 10% rule did not
by itself prevent injury (Buist et al. 2008, *Am J Sports Med* 36:33). So for
impact sports the boost is small, 12% a week, which is about 25% over two
weeks and under Nielsen's threshold. Low-impact sports get 15%, because their
limiting tissue load is far lower. The plan's own ACWR cap (Gabbett 2016,
*BJSM* 50:273; acute ≤ 1.3 × chronic) still applies on top.

**Why not a fixed cap from overall CTL.** A ceiling taken from today's CTL
would freeze a months-long plan at today's capacity. The ramp is the honest
lever: overall fitness changes how fast the sport may build, not where it may
end.

## Fatigue: shortened, moved or dropped

Form is TSB = CTL − ATL (Banister's impulse-response model in Coggan's
Performance Manager form; Allen & Coggan, *Training and Racing with a Power
Meter*). The step looks at the next three days of endurance sessions. For each
one it projects CTL and ATL with no added load to the session's day ("if you
rest until then").

| Condition | Action | Reasoning |
|---|---|---|
| TSB < −20 | 70% of the length, easy effort | Past the usual edge of the productive "optimal" band (−10…−30 in Coggan's usage, ≈ −20 in common coaching practice). Quality work done there is done badly, and it deepens the deficit. Keeping the day keeps the habit. |
| TSB < −30 | Move 1–2 days within the week to a free day, re-check there; drop if there is none | −30 is where the high-risk band starts. A few days of rest bring ATL down fast (its 7-day time constant). |
| A session of another sport in the last 48 h with TSS > 2 × CTL | Move the next session as above | CTL is the athlete's average daily load, so twice it in one sitting is a large single dose. TSB lags a single large session, which is why this rule exists beside it. "Another sport" is how a single-sport plan recognises something it did not schedule. Only the first session after it is moved; the ones beyond answer to form, or one ride would push the week back day by day. |

**Low-impact load and a run.** Cycling, swimming, rowing and skiing load the
cardiovascular system as running does, but load the running-specific tissues
far less (as above). For an impact session (run, hike), the last three days
of low-impact load count at 70% toward the ATL the session is judged on. This
is only a decision-time weighting: the ATL the dashboard shows is unchanged,
and nothing new is stored.

**Never touched:** races; strength and stretch sessions (attached after this
step, so a post-run stretch follows its run); sessions already done.

An adjusted session says what happened and why in the first line of its
description.

## Where it runs

Every regeneration: after each import (debounced ten seconds on the server,
and over a whole sync on the phone), on goal and setting edits, and lazily.
The server and the phone run the same step on the same inputs.
`spec/make_plan_adapt_fixtures.py` writes the cases, and `PlanAdaptFixtureTest`
holds the phone to the server's answers.
