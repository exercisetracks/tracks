// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// First-run onboarding wizard. A 7-step flow (Account → Body → Zones → GPS →
// Data → Look → AI) where each step owns a slice of the draft and reports
// changes up. This page is the orchestrator: it holds all draft state, the
// step index, and the final submit (`finish`), which creates the account (or
// completes onboarding for an SSO/seeded user), saves settings, applies the
// theme, and kicks off optional background downloads. The individual step UIs
// live in components/setup/.
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { api, TOKEN_KEY } from "../api/client";
import { StepIndicator } from "../components/setup/primitives";
import StepAccount from "../components/setup/StepAccount";
import StepBody from "../components/setup/StepBody";
import StepZones from "../components/setup/StepZones";
import StepStrength from "../components/setup/StepStrength";
import StepAGPS from "../components/setup/StepAGPS";
import StepData from "../components/setup/StepData";
import StepAppearance from "../components/setup/StepAppearance";
import StepAI from "../components/setup/StepAI";
import RecoveryKeyReveal from "../components/setup/RecoveryKeyReveal";

export default function Setup() {
  const { completeOnboarding, authStatus, user } = useAuth();
  const navigate = useNavigate();
  const isOnboarding = authStatus === "needs_onboarding";

  const [step, setStep] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState("");
  const [account, setAccount] = useState(
    isOnboarding && user
      ? { username: user.username || "", name: user.name || "", password: "", confirm: "" }
      : { username: "", name: "", password: "", confirm: "" }
  );
  const [body, setBody]       = useState({ units: "metric", weight: "", height: "", birthYear: "", sex: "", timezone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? "UTC" });
  const [zones, setZones]     = useState({ maxHrMode: "auto", maxHrManual: "", thresholdHrMode: "auto", thresholdHrManual: "", ftpMode: "auto", ftpManual: "" });
  const [strength, setStrength] = useState({ equipment_available: ["bodyweight", "dumbbell"], strength_experience: null });
  const [agps, setAgps]       = useState({ agpsEnabled: false, weatherEnabled: false, mapEnabled: false, wildfireEnabled: false, gnisEnabled: false, garminSyncEnabled: true });
  const [look, setLook]       = useState({ colorScheme: "system", accent: "emerald" });
  const [ai, setAI]           = useState({ aiProvider: null, aiModel: "", aiEndpoint: "", aiKey: "" });
  // Set once /auth/setup returns — withholds completeOnboarding()/navigate
  // until the user confirms they've saved it (see RecoveryKeyReveal).
  const [recoveryKey, setRecoveryKey] = useState(null);

  function patchAccount(k, v) { setAccount(d => ({ ...d, [k]: v })); }
  function patchBody(k, v)    { setBody(d => ({ ...d, [k]: v })); }
  function patchZones(k, v)   { setZones(d => ({ ...d, [k]: v })); }
  function patchStrength(k, v) { setStrength(d => ({ ...d, [k]: v })); }
  function patchAgps(k, v)    { setAgps(d => ({ ...d, [k]: v })); }
  function patchLook(k, v)    { setLook(d => ({ ...d, [k]: v })); }
  function patchAI(k, v)      { setAI(d => ({ ...d, [k]: v })); }

  async function finish() {
    setLoading(true);
    setError("");
    try {
      const imperial = body.units === "imperial";
      const settings = {
        units: body.units,
        timezone: body.timezone,
      };
      if (body.weight)  settings.weight_kg  = imperial ? parseFloat(body.weight) / 2.20462 : parseFloat(body.weight);
      if (body.height)  settings.height_cm  = imperial ? parseFloat(body.height) * 2.54    : parseFloat(body.height);
      if (body.sex)     settings.sex        = body.sex;
      const birthYear = parseInt(body.birthYear, 10);
      if (birthYear >= 1900 && birthYear <= new Date().getFullYear()) settings.birth_year = birthYear;
      settings.max_hr_mode       = zones.maxHrMode;
      settings.threshold_hr_mode = zones.thresholdHrMode;
      settings.ftp_mode          = zones.ftpMode;
      if (zones.maxHrMode       === "manual" && zones.maxHrManual)       settings.max_hr_manual       = parseInt(zones.maxHrManual);
      if (zones.thresholdHrMode === "manual" && zones.thresholdHrManual) settings.threshold_hr_manual = parseInt(zones.thresholdHrManual);
      if (zones.ftpMode         === "manual" && zones.ftpManual)         settings.ftp_manual          = parseInt(zones.ftpManual);
      settings.equipment_available = strength.equipment_available;
      if (strength.strength_experience) settings.strength_experience = strength.strength_experience;
      settings.agps_enabled = agps.agpsEnabled;
      settings.weather_enabled = agps.weatherEnabled;
      settings.map_enabled = agps.mapEnabled;
      settings.wildfire_enabled = agps.wildfireEnabled;
      // Appearance — saved to localStorage since theme is client-side
      if (look.colorScheme) localStorage.setItem("tracks_color_scheme", look.colorScheme);
      if (look.accent) localStorage.setItem("tracks_accent", look.accent);
      if (ai.aiProvider) {
        settings.ai_provider = ai.aiProvider;
        if (ai.aiModel)    settings.ai_model    = ai.aiModel;
        if (ai.aiEndpoint) settings.ai_endpoint = ai.aiEndpoint;
        if (ai.aiKey)      settings.ai_api_key  = ai.aiKey;
      }

      if (isOnboarding) {
        if (account.name && account.name !== user?.name) {
          await api.updateMe({ name: account.name }).catch(() => {});
        }
        settings.setup_complete = true;
        await api.updateSettings(settings);
        if (agps.gnisEnabled) {
          fetch("/api/maps/gnis/download", { method: "POST" }).catch(() => {});
        }
        await completeOnboarding();
        navigate("/", { replace: true });
      } else {
        const data = await api.setup(account.username.trim().toLowerCase(), account.name, account.password, agps.garminSyncEnabled);
        localStorage.setItem(TOKEN_KEY, data.access_token);
        settings.setup_complete = true;
        await api.updateSettings(settings);
        if (agps.gnisEnabled) {
          fetch("/api/maps/gnis/download", { method: "POST" }).catch(() => {});
        }
        // Hold here — the recovery key is shown exactly once (see
        // RecoveryKeyReveal) and completeOnboarding()/navigate only fire
        // once the user confirms they've saved it.
        setLoading(false);
        setRecoveryKey(data.recovery_key);
        return;
      }
    } catch (err) {
      setError(err.message ?? "Setup failed — please try again.");
      setLoading(false);
    }
  }

  async function finishAfterRecoveryKey() {
    await completeOnboarding();
    navigate("/", { replace: true });
  }

  const CONTENT = [
    <StepAccount    key={0} data={account}  onChange={patchAccount}  onNext={() => setStep(1)} />,
    <StepBody       key={1} data={body}     onChange={patchBody}     onNext={() => setStep(2)} onBack={() => setStep(0)} />,
    <StepZones      key={2} data={zones}    onChange={patchZones}    onNext={() => setStep(3)} onBack={() => setStep(1)} />,
    <StepStrength   key={3} data={strength} onChange={patchStrength} onNext={() => setStep(4)} onBack={() => setStep(2)} />,
    <StepAGPS       key={4} data={agps}     onChange={patchAgps}     onNext={() => setStep(5)} onBack={() => setStep(3)} />,
    <StepData       key={5} data={agps}     onChange={patchAgps}     onNext={() => setStep(6)} onBack={() => setStep(4)} />,
    <StepAppearance key={6} data={look}     onChange={patchLook}     onNext={() => setStep(7)} onBack={() => setStep(5)} />,
    <StepAI         key={7} data={ai}       onChange={patchAI}       onFinish={finish}         onBack={() => setStep(6)} loading={loading} error={error} />,
  ];

  const TITLES = [
    "Create your account",
    "About you",
    "Training zones",
    "Strength training",
    "Garmin watch sync",
    "Map & weather data",
    "Appearance",
    "AI coaching",
  ];
  const SUBTITLES = [
    "Choose a username and password for your admin account. · Offline",
    "Used for calorie estimates and zone boundaries. Both optional. · Offline",
    "Tracks derives these automatically — override if you know your numbers. · Offline",
    "Equipment and experience shape your generated strength plans. Both optional. · Offline",
    "USB plug-in sync, and optionally pre-loaded satellite data for faster GPS lock. · Online",
    "Download map tiles and enable weather forecasts. Both optional. · Online",
    "Pick a color scheme and accent color. · Offline",
    "Optional — AI-powered workout recommendations. Data may leave your server based on provider choice. · Conditional",
  ];

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 dark:bg-slate-950 px-3.5 py-8">
      <div className="w-full max-w-md">
        <div className="text-center mb-4">
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">Tracks</h1>
          <p className="mt-0.5 text-slate-500 dark:text-slate-400 text-sm">Your personal fitness tracker</p>
        </div>

        <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-800 p-4">
          {recoveryKey ? (
            <>
              <div className="mb-4">
                <h2 className="text-lg font-semibold text-slate-900 dark:text-white">Save your recovery key</h2>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">One-time — it won't be shown again</p>
              </div>
              <RecoveryKeyReveal recoveryKey={recoveryKey} onContinue={finishAfterRecoveryKey} />
            </>
          ) : (
            <>
              <StepIndicator current={step} />

              <div className="mb-4">
                <h2 className="text-lg font-semibold text-slate-900 dark:text-white">{TITLES[step]}</h2>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{SUBTITLES[step]}</p>
              </div>

              {CONTENT[step]}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
