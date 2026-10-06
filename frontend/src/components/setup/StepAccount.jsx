// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Setup step 1 — Account. Collects username, display name, and password with
// local validation. Reads/writes only the `account` draft slice via onChange,
// and advances the wizard via onNext once the form validates.
import { useState } from "react";
import { INPUT, FieldRow } from "./primitives";

export default function StepAccount({ data, onChange, onNext }) {
  const [showPass, setShowPass]    = useState(false);
  const [showConf, setShowConf]    = useState(false);
  const [error, setError]          = useState("");

  function validate() {
    if (!data.username.trim()) return "Please choose a username.";
    if (data.username.trim().length < 3) return "Username must be at least 3 characters.";
    if (!/^[a-zA-Z0-9_-]+$/.test(data.username.trim())) return "Username may only contain letters, numbers, hyphens, and underscores.";
    if (!data.name.trim()) return "Please enter your display name.";
    if (data.password.length < 8) return "Password must be at least 8 characters.";
    if (data.password !== data.confirm) return "Passwords do not match.";
    return "";
  }

  function handleNext(e) {
    e.preventDefault();
    const err = validate();
    if (err) { setError(err); return; }
    setError("");
    onNext();
  }

  return (
    <form onSubmit={handleNext} className="space-y-4">
      {error && (
        <p className="alert-error">{error}</p>
      )}
      <FieldRow label="Username" hint="(used to sign in)">
        <input
          className={INPUT}
          type="text"
          placeholder="e.g. alex"
          value={data.username}
          onChange={e => onChange("username", e.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, ""))}
          autoFocus
          autoComplete="username"
          required
        />
      </FieldRow>
      <FieldRow label="Display name">
        <input
          className={INPUT}
          type="text"
          placeholder="e.g. Alex"
          value={data.name}
          onChange={e => onChange("name", e.target.value)}
          autoComplete="name"
          required
        />
      </FieldRow>
      <FieldRow label="Password" hint="(8 characters minimum)">
        <div className="relative">
          <input
            className={INPUT + " pr-10"}
            type={showPass ? "text" : "password"}
            placeholder="Choose a password"
            value={data.password}
            onChange={e => onChange("password", e.target.value)}
            required
            minLength={8}
          />
          <button type="button" tabIndex={-1}
            onClick={() => setShowPass(v => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
            {showPass
              ? <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" /></svg>
              : <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
            }
          </button>
        </div>
      </FieldRow>
      <FieldRow label="Confirm password">
        <div className="relative">
          <input
            className={INPUT + " pr-10"}
            type={showConf ? "text" : "password"}
            placeholder="Repeat your password"
            value={data.confirm}
            onChange={e => onChange("confirm", e.target.value)}
            required
          />
          <button type="button" tabIndex={-1}
            onClick={() => setShowConf(v => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
            {showConf
              ? <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" /></svg>
              : <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
            }
          </button>
        </div>
      </FieldRow>
      <button type="submit"
        className="btn btn-primary w-full mt-2">
        Continue
      </button>
    </form>
  );
}
