// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// What each Health metric means, shown behind the "?" in its history popup.
// The same words as the phone's Explain (MetricInfo.kt): one explanation per
// metric, so the two clients cannot describe the same number differently.

const info = (title, ...body) => ({ title, body });

export const EXPLAIN = {
  steps: info("Steps",
    "Everything you walked, training or not. It is a measure of how active the day was rather than how hard you trained.",
    "Worth watching on rest days: a 20,000-step rest day is not a rest day."),
  caloriesBurned: info("Calories burned",
    "Everything the day has cost so far: the resting burn your body spends simply existing, plus the active calories you earned on top of it. The dial shows the two as separate colours.",
    "Resting burn is the watch's metabolic-rate estimate, scaled to how much of the day has actually happened — so at breakfast it is a fraction of the figure, not the whole one."),
  bodyBattery: info("Body Battery",
    "The watch's estimate of how much you have left, from 0 to 100. It charges with rest and sleep and drains with activity and stress.",
    "The shape of the day is the signal: waking below 50 repeatedly, or never charging past 60, says more than any single reading.",
    "This is the device's own model — Garmin has not published it, so it is stored exactly as reported and never recomputed here."),
  sleep: info("Sleep",
    "The dial's figure is time asleep; the arc is that time split into deep, REM and light, so a short night still reads as short and a night of almost all light sleep looks like one.",
    "The score is the watch's own 0–100 verdict on the night, from duration, stage balance, restlessness and overnight heart rate. Useful as a trend against your own history; comparing it with someone else's is comparing two devices' opinions.",
    "The dashed line is the eight-hour goal the dial's arc runs to."),
  sleepStages: info("Sleep stages",
    "Deep sleep is when physical recovery happens; REM is when the day is consolidated; light sleep is most of the night and is where the transitions live.",
    "Each night is drawn as the block of time it occupied — top at lights out, bottom at waking — with the stages stacked inside it in a fixed order, so the depth of the indigo band is the same measurement from night to night. The order is not a claim about when each stage happened; the hypnogram above shows that.",
    "The dashed lines are your average bedtime and waking across the window. Consistency is the thing to read for, and the eye judges it far better against a reference.",
    "Awake time is measured separately and never counted as sleep. A night with an hour of waking in it is a different night from a short one slept straight through."),
  restingHr: info("Resting heart rate",
    "Your lowest sustained heart rate, usually measured overnight. It falls over months as aerobic fitness improves.",
    "A jump of five or more beats above your own normal, for more than a day, is a reliable early sign of illness, poor sleep or too much training."),
  hrv: info("HRV",
    "The variation between consecutive heartbeats, in milliseconds, measured overnight. Higher generally means better recovered.",
    "Absolute values vary hugely between people — yours is only comparable with your own, which is why the dial compares you to your own range for the window. A multi-day decline is the useful signal; one low night usually is not."),
  spo2: info("Blood oxygen",
    "The percentage of oxygen your blood is carrying, measured at the wrist. Typically 95–100% at sea level.",
    "Wrist readings are noisy and drop with a cold sensor or a loose strap. It is most interesting at altitude, where a falling overnight average tracks how acclimatised you are."),
  respiration: info("Respiration",
    "Breaths per minute, averaged across the day. Adults at rest are typically 12–20.",
    "A raised overnight average alongside a raised resting heart rate is one of the earlier signs that something is coming on."),
  stress: info("Stress",
    "A 0–100 score the watch derives from heart rate variability during the day. Low is calm, high is a body under load — physical or not.",
    "Exercise reads as high stress, which is correct and worth remembering when reading a training day.",
    "On windows up to a month the chart is the watch's own readings — one every few minutes — with the line coloured by the band it is passing through. Longer windows draw one point per day, the daily average; the footer says which is on screen.",
    "The dashed line is your average across the window. Higher or lower than your own usual is the comparison worth making."),
  weight: info("Weight",
    "Entered by hand or synced from a scale. It feeds power-to-weight and the calorie targets.",
    "Weigh at the same time of day — first thing, before eating — or the daily noise will be larger than the trend.",
    "There are no good or bad bands here, and there will not be: the dial only places today within your own range for the window."),
  hydration: info("Hydration",
    "What you have logged drinking that day. Sweat losses run roughly half a litre to a litre an hour of hard exercise, more in heat."),
  caloriesIn: info("Calories eaten",
    "Everything you logged eating that day, added up. Compare it with the day's burn to see roughly where the balance landed.",
    "Days from before food was logged meal by meal show the daily total that was typed in instead."),
  // The group-level note behind Body's "?". The rule it describes is isFresh
  // in scales.js — one day for water and food, never for weight — and the
  // wording has to change if that does.
  body: info("Body",
    "These are the numbers you log yourself, with the Log today button.",
    "Water and calories eaten are daily totals, so they expire: a dial shows its newest entry for one day after the day it was logged, then goes to a dash until you log again. Yesterday's water is still on the dial today; by tomorrow it is history.",
    "Weight is the exception. It stays on the dial until you log a new one, because a body weight is still true the morning after you stood on the scales.",
    "Nothing is deleted when a dial expires — click it to see every entry in the window."),
};
