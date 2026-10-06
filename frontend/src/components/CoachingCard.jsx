// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Coaching card: the no-goal dashboard recommendation. Renders one hero
// suggestion (any modality: cardio / strength / mobility / rest) plus alternates
// that the recommender deliberately picks from *other* modalities, so the card
// never shows three variations of the same workout.
//
// Modality badge colours are kept distinct from (and complementary to) the
// PlannedWorkoutCard sport badges so the two dashboard cards read as one system.

const MODALITY_BADGE = {
  cardio:   "bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300",
  strength: "bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300",
  mobility: "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300",
  rest:     "bg-slate-200 text-slate-600 dark:bg-slate-800 dark:text-slate-300",
};

const MODALITY_LABEL = {
  cardio:   "Cardio",
  strength: "Strength",
  mobility: "Mobility",
  rest:     "Rest",
};

function badgeClass(modality) {
  return MODALITY_BADGE[modality] ?? MODALITY_BADGE.cardio;
}

function modalityLabel(modality) {
  return MODALITY_LABEL[modality] ?? (modality ?? "Cardio");
}

function Hero({ rec }) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${badgeClass(rec.modality)}`}>
          {modalityLabel(rec.modality)}
        </span>
        {rec.duration_minutes > 0 && (
          <span className="text-xs text-slate-500 dark:text-slate-400">{rec.duration_minutes} min</span>
        )}
        {rec.modality === "strength" && rec.focus && (
          <span className="text-xs text-slate-500 dark:text-slate-400 capitalize">{rec.focus}</span>
        )}
      </div>

      {rec.title && (
        <p className="text-sm font-semibold text-slate-900 dark:text-white leading-snug">{rec.title}</p>
      )}
      {rec.description && (
        <p className="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">{rec.description}</p>
      )}
    </div>
  );
}

function Alternate({ rec }) {
  return (
    <div className="py-1 space-y-1">
      <div className="flex items-center gap-2 flex-wrap">
        <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${badgeClass(rec.modality)}`}>
          {modalityLabel(rec.modality)}
        </span>
        {rec.title && (
          <span className="text-xs font-medium text-slate-600 dark:text-slate-300">{rec.title}</span>
        )}
        {rec.duration_minutes > 0 && (
          <span className="text-xs text-slate-400 dark:text-slate-500">{rec.duration_minutes} min</span>
        )}
      </div>
      {rec.description && (
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-snug">{rec.description}</p>
      )}
    </div>
  );
}

export default function CoachingCard({ recommendations = [] }) {
  if (!recommendations.length) {
    return (
      <div className="card text-sm text-slate-400 dark:text-slate-500 h-full">
        No coaching recommendation available.
      </div>
    );
  }

  const [primary, ...rest] = recommendations;

  return (
    <div className="card flex flex-col h-full">
      <p className="section-title mb-2">
        Suggested today
      </p>

      <Hero rec={primary} />

      {rest.length > 0 && (
        <div className="mt-auto pt-2.5 border-t border-slate-100 dark:border-slate-800">
          <p className="section-title mb-1">
            Other options
          </p>
          <div className="divide-y divide-slate-100 dark:divide-slate-800">
            {rest.map((rec, i) => (
              <Alternate key={i} rec={rec} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
