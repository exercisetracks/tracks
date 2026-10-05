// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI coaching configuration (provider / model / endpoint / API key), expanded
// inline under the AI Coaching row of Privacy & Connectivity. Each field saves
// on change/blur. Shows a data-residency disclosure based on the provider.
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import { INPUT, SELECT, FieldRow, InlineError, SaveStatusText, useSaveStatus } from "./primitives";

const AI_PROVIDERS = [
  { value: "anthropic", label: "Anthropic (Claude)", needsKey: true,  needsEndpoint: false },
  { value: "openai",    label: "OpenAI (GPT)",       needsKey: true,  needsEndpoint: false },
  { value: "ollama",    label: "Ollama (local)",     needsKey: false, needsEndpoint: true  },
];

export default function AiConfigPanel({ settings, onSaved }) {
  const [provider, setProvider] = useState(settings?.ai_provider  ?? "");
  const [endpoint, setEndpoint] = useState(settings?.ai_endpoint  ?? "");
  const [model,    setModel]    = useState(settings?.ai_model     ?? "");
  const [apiKey,   setApiKey]   = useState("");
  const [showKey,  setShowKey]  = useState(false);
  const [error,    setError]    = useState("");
  const { status, startSave, markSaved, markError } = useSaveStatus();
  const configured = settings?.ai_configured ?? false;
  const pInfo = AI_PROVIDERS.find(p => p.value === provider);

  useEffect(() => {
    setProvider(settings?.ai_provider ?? "");
    setEndpoint(settings?.ai_endpoint ?? "");
    setModel(settings?.ai_model ?? "");
    setApiKey("");
  }, [settings]);

  async function saveField(payload) {
    startSave();
    setError("");
    try {
      await api.updateSettings(payload);
      onSaved();
      markSaved();
    } catch (e) {
      setError(e.message ?? "Save failed.");
      markError();
    }
  }

  function handleProviderChange(v) {
    setProvider(v);
    if (!v) {
      saveField({ ai_provider: null, ai_model: null, ai_endpoint: null });
    } else {
      saveField({ ai_provider: v });
    }
  }

  return (
    <div className="px-2.5 py-2.5 space-y-3 bg-slate-50/70 dark:bg-slate-800/30">
      <FieldRow label="Provider">
        <select className={SELECT} value={provider}
          onChange={e => handleProviderChange(e.target.value)}>
          <option value="">None — disable AI coaching</option>
          {AI_PROVIDERS.map(p => <option key={p.value} value={p.value}>{p.label}</option>)}
        </select>
      </FieldRow>

      {pInfo && (
        <>
          {provider === "ollama" ? (
            <div className="rounded-lg border border-emerald-200 dark:border-emerald-800 bg-emerald-50 dark:bg-emerald-900/20 p-2.5">
              <div className="flex items-start gap-2">
                <span className="text-emerald-500 font-bold shrink-0 text-xs mt-0.5">+</span>
                <div>
                  <p className="text-xs font-medium text-emerald-700 dark:text-emerald-400">Fully local</p>
                  <p className="text-xs text-emerald-600 dark:text-emerald-500 mt-0.5 leading-relaxed">
                    Your training data stays on your server — nothing is sent to an external service.
                  </p>
                </div>
              </div>
            </div>
          ) : pInfo.needsKey ? (
            <div className="rounded-lg border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 p-2.5">
              <div className="flex items-start gap-2">
                <span className="text-amber-500 font-bold shrink-0 text-xs mt-0.5">−</span>
                <div>
                  <p className="text-xs font-medium text-amber-700 dark:text-amber-400">
                    Sends data to {pInfo.label}
                  </p>
                  <p className="text-xs text-amber-600 dark:text-amber-500 mt-0.5 leading-relaxed">
                    Your training and physiological data (HRV, sleep, resting HR, training load,
                    workout recommendations) is sent to {pInfo.label}'s servers.
                    No name, email, or GPS location is included.
                  </p>
                </div>
              </div>
            </div>
          ) : null}
          <FieldRow label="Model">
            <input className={INPUT} type="text"
              placeholder={provider === "anthropic" ? "claude-haiku-4-5" : provider === "openai" ? "gpt-4o-mini" : "llama3"}
              value={model}
              onChange={e => setModel(e.target.value)}
              onBlur={() => saveField({ ai_model: model || null })} />
          </FieldRow>
          {pInfo.needsEndpoint && (
            <FieldRow label="Endpoint URL">
              <input className={INPUT} type="url"
                placeholder="http://localhost:11434"
                value={endpoint}
                onChange={e => setEndpoint(e.target.value)}
                onBlur={() => saveField({ ai_endpoint: endpoint || null })} />
            </FieldRow>
          )}
          {pInfo.needsKey && (
            <FieldRow label="API Key" hint={configured ? "(leave blank to keep existing)" : ""}>
              <div className="relative">
                <input
                  className={INPUT + " pr-10"}
                  type={showKey ? "text" : "password"}
                  placeholder={configured ? "••••••••••••" : "Paste your API key"}
                  value={apiKey}
                  onChange={e => setApiKey(e.target.value)}
                  onBlur={() => { if (apiKey) saveField({ ai_api_key: apiKey }); }}
                />
                <button type="button" tabIndex={-1}
                  onClick={() => setShowKey(v => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600">
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                    {showKey
                      ? <path strokeLinecap="round" strokeLinejoin="round" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" />
                      : <><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></>
                    }
                  </svg>
                </button>
              </div>
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">Stored encrypted — never exposed after saving.</p>
            </FieldRow>
          )}
        </>
      )}

      <div className="flex items-center justify-end">
        <SaveStatusText status={status} />
      </div>
      <InlineError msg={error} />
    </div>
  );
}
