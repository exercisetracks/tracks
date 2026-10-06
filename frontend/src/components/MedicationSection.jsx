// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Medication tracker with three tabs: Today (due doses + as-needed), Medications
// (CRUD list with an inline add/edit form) and History (last 30 days of logged
// doses). Owns all data loading and the log/save/delete mutations; the tab UI,
// dose cards, form rows and log rows live in ./medication/*. A tab-open-only
// notification engine (useNotifications) reminds about due doses.

import { useState, useEffect, useCallback } from "react";
import { api } from "../api/client";
import { clsx, BTN_PRIMARY, BTN_GHOST, BTN_TONAL, BTN_DANGER, DAYS } from "./medication/constants";
import { PlusIcon } from "./ui/Button";
import { useNotifications } from "./medication/useNotifications";
import MedForm from "./medication/MedForm";
import DoseCard from "./medication/DoseCard";
import LogRow from "./medication/LogRow";
import Tabs from "./ui/Tabs";

const TABS = [
  { key: "today",    label: "Today" },
  { key: "meds",     label: "Medications" },
  { key: "history",  label: "History" },
];

export default function MedicationSection() {
  const [meds,       setMeds]       = useState([]);
  const [dueMeds,    setDueMeds]    = useState([]);
  const [log,        setLog]        = useState([]);
  const [activeTab,  setActiveTab]  = useState("today");
  const [showAdd,    setShowAdd]    = useState(false);
  const [editMed,    setEditMed]    = useState(null);
  const [saving,     setSaving]     = useState(false);
  const [logging,    setLogging]    = useState(null);
  const [deletingId, setDeletingId] = useState(null);

  const reload = useCallback(() => {
    api.getMedications().then(setMeds).catch(() => {});
    api.getDueMedications().then(setDueMeds).catch(() => {});
    api.getMedicationLog(30).then(setLog).catch(() => {});
  }, []);

  useEffect(() => { reload(); }, [reload]);

  // Re-check due list every 2 minutes
  useEffect(() => {
    const t = setInterval(() => {
      api.getDueMedications().then(setDueMeds).catch(() => {});
    }, 120000);
    return () => clearInterval(t);
  }, []);

  async function logDose(med, status) {
    setLogging(med.schedule_id);
    try {
      await api.logDose({
        medication_id: med.medication_id,
        schedule_id:   med.schedule_id,
        status,
        scheduled_for: med.scheduled_for,
      });
      reload();
    } catch { /* ignore */ }
    finally { setLogging(null); }
  }

  async function handleSaveMed(data) {
    setSaving(true);
    try {
      if (editMed) {
        await api.updateMedication(editMed.id, data);
        setEditMed(null);
      } else {
        await api.createMedication(data);
        setShowAdd(false);
      }
      reload();
    } catch { /* ignore */ }
    finally { setSaving(false); }
  }

  async function handleDelete(id) {
    setDeletingId(id);
    try {
      await api.deleteMedication(id);
      reload();
    } catch { /* ignore */ }
    finally { setDeletingId(null); }
  }

  useNotifications(dueMeds, med => logDose(med, "taken"));

  const medById = Object.fromEntries(meds.map(m => [m.id, m.name]));

  return (
    <div className="space-y-4">
      <Tabs tabs={TABS} value={activeTab} onChange={setActiveTab} size="sm" />

      {/* Today tab */}
      {activeTab === "today" && (
        <div className="space-y-2">
          {dueMeds.length === 0 ? (
            <div className="card p-7 text-center">
              <p className="text-sm text-slate-400">No medications scheduled for today.</p>
              <p className="text-xs text-slate-400 mt-1">Add medications in the Medications tab.</p>
            </div>
          ) : (
            dueMeds.map(med => (
              <DoseCard
                key={`${med.schedule_id}-${med.time_of_day}`}
                med={med}
                logging={logging}
                onTake={m => logDose(m, "taken")}
                onSkip={m => logDose(m, "skipped")}
              />
            ))
          )}

          {/* As-needed button */}
          {meds.filter(m => m.is_active && m.schedules.some(s => s.is_as_needed)).length > 0 && (
            <div className="card">
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-2">As-needed</p>
              <div className="flex flex-wrap gap-2">
                {meds
                  .filter(m => m.is_active && m.schedules.some(s => s.is_as_needed))
                  .map(m => (
                    <button
                      key={m.id}
                      onClick={() => api.logDose({ medication_id: m.id, status: "as_needed" }).then(reload)}
                      className={BTN_TONAL}
                    >
                      {m.name} {m.dose ? `(${m.dose} ${m.dose_unit ?? ""})` : ""}
                    </button>
                  ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Medications tab */}
      {activeTab === "meds" && (
        <div className="space-y-3">
          <button onClick={() => { setShowAdd(true); setEditMed(null); }} className={BTN_PRIMARY}>
            <PlusIcon />Add medication
          </button>

          {showAdd && !editMed && (
            <div className="card">
              <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">New medication</p>
              <MedForm onSave={handleSaveMed} onCancel={() => setShowAdd(false)} loading={saving} />
            </div>
          )}

          {meds.length === 0 && !showAdd ? (
            <div className="card p-7 text-center">
              <p className="text-sm text-slate-400">No medications added yet.</p>
            </div>
          ) : (
            <div className="space-y-2">
              {meds.map(m => (
                <div key={m.id} className="card">
                  {editMed?.id === m.id ? (
                    <>
                      <p className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">Edit medication</p>
                      <MedForm initial={editMed} onSave={handleSaveMed} onCancel={() => setEditMed(null)} loading={saving} />
                    </>
                  ) : (
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <p className="font-medium text-slate-800 dark:text-slate-100">{m.name}</p>
                          {m.dose && <span className="text-xs text-slate-400">{m.dose} {m.dose_unit ?? ""}</span>}
                          {m.form && <span className="text-xs text-slate-400 italic">{m.form}</span>}
                          {!m.is_active && (
                            <span className="badge bg-slate-100 dark:bg-slate-800 text-slate-400">
                              Inactive
                            </span>
                          )}
                        </div>
                        {m.notes && <p className="text-xs text-slate-400 mt-0.5 italic">{m.notes}</p>}
                        {m.schedules.filter(s => !s.is_as_needed).map((s, i) => (
                          <p key={i} className="text-xs text-slate-400 mt-0.5">
                            {s.time_of_day}
                            {s.days_of_week?.length ? ` · ${s.days_of_week.map(d => DAYS[d]).join(", ")}` : " · Every day"}
                            {s.notify ? " · Reminder" : ""}
                          </p>
                        ))}
                        {m.schedules.some(s => s.is_as_needed) && (
                          <p className="text-xs text-blue-400 mt-0.5">+ As-needed</p>
                        )}
                      </div>
                      <div className="flex gap-1.5 shrink-0">
                        <button onClick={() => { setEditMed(m); setShowAdd(false); }} className={BTN_GHOST}>Edit</button>
                        <button
                          onClick={() => handleDelete(m.id)}
                          disabled={deletingId === m.id}
                          className={BTN_DANGER}
                        >
                          {deletingId === m.id ? "…" : "Delete"}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* History tab */}
      {activeTab === "history" && (
        <div className="card">
          <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-3">Last 30 days</p>
          {log.length === 0 ? (
            <p className="text-xs text-slate-400 text-center py-3.5">No medication history yet.</p>
          ) : (
            <div className="max-h-96 overflow-y-auto">
              {log.map(e => (
                <LogRow key={e.id} entry={e} medName={medById[e.medication_id] ?? "Unknown"} />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
