
describe("intensity minutes and floors", () => {
  // The goal is weekly, so the dial reads a rolling week, vigorous counting double.
  it("sums the seven days ending each day, vigorous twice", async () => {
    const { weeklyIntensity } = await import("../components/health/scales");
    const days = [
      { date: "2026-03-01", intensity_moderate_min: 30, intensity_vigorous_min: 0 },
      { date: "2026-03-05", intensity_moderate_min: 10, intensity_vigorous_min: 20 },
      { date: "2026-03-09", intensity_moderate_min: 5, intensity_vigorous_min: null },
    ];
    expect(weeklyIntensity(days).values).toEqual([30, 30, 30, 30, 80, 80, 80, 50, 55]);
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
