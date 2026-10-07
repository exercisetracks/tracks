// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Settings page: loads the user's settings once, then renders a stack of
// independent, self-contained sections. Each section owns its own state and
// only receives `settings` (+ `user` where needed) plus an `onSaved` callback
// that re-fetches the shared settings object so other sections stay in sync.
//
// The section components live in ../components/settings/ — see primitives.jsx
// for the shared Section/FieldRow/useSaveStatus building blocks.
import { useEffect, useState } from "react";
import { api } from "../api/client";
import { useAuth } from "../auth/AuthContext";

import ProfileSection from "../components/settings/ProfileSection";
import AppearanceSection from "../components/settings/AppearanceSection";
import TutorialSection from "../components/settings/TutorialSection";
import BodySection from "../components/settings/BodySection";
import HRSection from "../components/settings/HRSection";
import PowerSection from "../components/settings/PowerSection";
import SportsSection from "../components/settings/SportsSection";
import ChartResolutionSection from "../components/settings/ChartResolutionSection";
import DevicesSection from "../components/settings/DevicesSection";
import GarminCoachingSection from "../components/settings/GarminCoachingSection";
import PrivacySummarySection from "../components/settings/PrivacySummarySection";
import EquipmentSection from "../components/settings/EquipmentSection";
import SecuritySection from "../components/settings/SecuritySection";
import UserManagementSection from "../components/settings/UserManagementSection";
import DangerZoneSection from "../components/settings/DangerZoneSection";
import VersionSection from "../components/settings/VersionSection";
import BackupSection from "../components/settings/BackupSection";
import PageHeader from "../components/ui/PageHeader";

export default function Settings() {
  const { user } = useAuth();
  const [settings, setSettings] = useState(null);
  const [loading,  setLoading]  = useState(true);

  async function loadSettings() {
    try {
      const s = await api.getSettings();
      setSettings(s);
    } catch {
      // sections degrade gracefully with null settings
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { loadSettings(); }, []);

  async function handleSaved() {
    try {
      const s = await api.getSettings();
      setSettings(s);
    } catch { /* ignore */ }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="spinner w-6 h-6" />
      </div>
    );
  }

  return (
    <div className="p-5 max-w-2xl mx-auto space-y-6">
      <PageHeader title="Settings" subtitle="Manage your profile, training zones, and preferences." />

      <ProfileSection  user={user}     settings={settings} onSaved={handleSaved} />
      <AppearanceSection />
      <TutorialSection />
      <BodySection                     settings={settings} onSaved={handleSaved} />
      <HRSection                       settings={settings} onSaved={handleSaved} />
      <PowerSection                    settings={settings} onSaved={handleSaved} />
      <SportsSection                   settings={settings} onSaved={handleSaved} />
      <ChartResolutionSection          settings={settings} onSaved={handleSaved} />
      <DevicesSection />
      <GarminCoachingSection           settings={settings} onSaved={handleSaved} />
      <PrivacySummarySection          settings={settings} onSaved={handleSaved} />
      <EquipmentSection                settings={settings} onSaved={handleSaved} />
      <SecuritySection />
      <BackupSection />
      {user?.is_admin && <UserManagementSection />}
      <VersionSection />
      <DangerZoneSection />
    </div>
  );
}
