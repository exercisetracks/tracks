// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step 7 (final) — AI coaching. Optionally connect an AI provider
// (Anthropic / OpenAI / Ollama) for enhanced coaching, with provider, model,
// endpoint (Ollama), and API key fields plus a data-residency disclosure.
// Reads/writes the `ai` draft slice; onFinish submits the whole wizard.
// `loading`/`error` reflect the in-flight submit owned by the parent.
import { useState } from "react";
import { INPUT, SELECT, FieldRow } from "./primitives";

const AI_PROVIDERS = [
  { value: "anthropic", label: "Anthropic (Claude)", needsKey: true,  needsEndpoint: false, defaultModel: "claude-haiku-4-5" },
  { value: "openai",    label: "OpenAI (GPT)",       needsKey: true,  needsEndpoint: false, defaultModel: "gpt-4o-mini" },
  { value: "ollama",    label: "Ollama (local)",     needsKey: false, needsEndpoint: true,  defaultModel: "llama3" },
];

export default function StepAI({ data, onChange, onFinish, onBack, loading, error }) {
  const provider = AI_PROVIDERS.find(p => p.value === data.aiProvider);
  const [showKey, setShowKey] = useState(false);

  return (
    <div className="space-y-4">
      {error && (
        <p className="alert-error">{error}</p>
      )}

      <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
        Connect an AI provider to enable enhanced coaching recommendations. You can skip this and configure it later in Settings.
      </p>

      <FieldRow label="Provider">
        <select className={SELECT} value={data.aiProvider ?? ""}
          onChange={e => {
            const p = AI_PROVIDERS.find(x => x.value === e.target.value);
            onChange("aiProvider", e.target.value || null);
            if (p) onChange("aiModel", p.defaultModel);
          }}>
          <option value="">None — skip for now</option>
          {AI_PROVIDERS.map(p => <option key={p.value} value={p.value}>{p.label}</option>)}
        </select>
      </FieldRow>

      {provider && (
        <>
          {provider.value === "ollama" ? (
            <div className="rounded-lg border border-emerald-200 dark:border-emerald-800 bg-emerald-50 dark:bg-emerald-900/20 p-2.5">
              <div className="flex items-start gap-2">
                <span className="text-emerald-500 font-bold shrink-0 text-xs mt-0.5">+</span>
                <div>
                  <p className="text-xs font-medium text-emerald-700 dark:text-emerald-400">Fully local</p>
                  <p className="text-xs text-emerald-600 dark:text-emerald-500 mt-0.5 leading-relaxed">
                    Your training data (HRV, sleep, resting HR, training load, workout recommendations)
                    stays on your server — nothing is sent to an external service.
                  </p>
                </div>
              </div>
            </div>
          ) : provider.needsKey ? (
            <div className="rounded-lg border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 p-2.5">
              <div className="flex items-start gap-2">
                <span className="text-amber-500 font-bold shrink-0 text-xs mt-0.5">−</span>
                <div>
                  <p className="text-xs font-medium text-amber-700 dark:text-amber-400">
                    Sends data to {provider.label}
                  </p>
                  <p className="text-xs text-amber-600 dark:text-amber-500 mt-0.5 leading-relaxed">
                    Your training and physiological data (HRV, sleep, resting HR, training load,
                    workout recommendations) is sent to {provider.label}'s servers.
                    No name, email, or GPS location is included.
                  </p>
                </div>
              </div>
            </div>
          ) : null}
          <FieldRow label="Model">
            <input
              className={INPUT}
              type="text"
              placeholder={provider.defaultModel}
              value={data.aiModel ?? ""}
              onChange={e => onChange("aiModel", e.target.value)}
            />
          </FieldRow>
          {provider.needsEndpoint && (
            <FieldRow label="Endpoint URL">
              <input
                className={INPUT}
                type="url"
                placeholder="http://localhost:11434"
                value={data.aiEndpoint ?? ""}
                onChange={e => onChange("aiEndpoint", e.target.value)}
              />
            </FieldRow>
          )}
          {provider.needsKey && (
            <FieldRow label="API Key">
              <div className="relative">
                <input
                  className={INPUT + " pr-10"}
                  type={showKey ? "text" : "password"}
                  placeholder="Paste your API key"
                  value={data.aiKey ?? ""}
                  onChange={e => onChange("aiKey", e.target.value)}
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

      <div className="flex gap-3 pt-1">
        <button type="button" onClick={onBack}
          className="btn btn-neutral flex-1">
          Back
        </button>
        <button type="button" onClick={onFinish} disabled={loading}
          className="btn btn-primary flex-1">
          {loading ? "Setting up…" : "Finish setup"}
        </button>
      </div>

    </div>
  );
}
