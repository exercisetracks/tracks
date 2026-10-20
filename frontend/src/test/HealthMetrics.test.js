
describe("intensity minutes and floors", () => {
  // The goal is weekly, so the dial reads the calendar week (Monday first), vigorous counting double.
  it("sums the week so far, restarting each Monday, vigorous twice", async () => {
    const { weeklyIntensity } = await import("../components/health/scales");
    const days = [
      { date: "2026-03-01", intensity_moderate_min: 30, intensity_vigorous_min: 0 },
      { date: "2026-03-05", intensity_moderate_min: 10, intensity_vigorous_min: 20 },
      { date: "2026-03-09", intensity_moderate_min: 5, intensity_vigorous_min: null },
    ];
    expect(weeklyIntensity(days).values).toEqual([30, 0, 0, 0, 50, 50, 50, 50, 5]);
  });

  // The chart had gaps: a day with no intensity minutes is zero, not missing.
  it("draws a quiet day as zero rather than a gap", async () => {
    const { weeklyIntensity } = await import("../components/health/scales");
    const out = weeklyIntensity([
      { date: "2026-03-01", intensity_moderate_min: 70 },
      { date: "2026-03-09", intensity_moderate_min: null, intensity_vigorous_min: null },
    ]);
    expect(out.dates).toHaveLength(9);
    expect(out.values.slice(-2)).toEqual([0, 0]);
  });

  // Two more dials made the groups even: six each, two rows of three.
  it("gives Activity and Vitals six dials each", async () => {
    const { healthGroups } = await import("../components/health/metrics");
    const { activity, vitals } = healthGroups({ days: [], imperial: false, start: "2026-03-01", stress: [] });
    expect(activity.metrics.map((m) => m.key)).toEqual(["steps", "calories", "body_battery", "stress", "intensity", "floors"]);
    expect(vitals.metrics).toHaveLength(6);
  });
});

describe("joined readings (blood oxygen)", () => {
  // Readings are a handful a night: the line runs through them, and only a
  // gap of more than two days breaks it.
  it("breaks the line only across a gap of more than two days", async () => {
    const { joinedRuns } = await import("../components/health/StressHistory");
    const at = (day) => ({ x: day / 10, level: 95 });
    const runs = joinedRuns([at(0), at(1.5), at(3.5), at(6.1)], 10, 100, 2);
    expect(runs.map((r) => r.length)).toEqual([3, 1]);
  });
});
