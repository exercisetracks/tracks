// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useCallback } from "react";
import StretchesTab from "../components/StretchesTab";
import FlowsTab from "../components/FlowsTab";
import CustomStretchModal from "../components/CustomStretchModal";
import Tabs from "../components/ui/Tabs";
import PageHeader from "../components/ui/PageHeader";

const TABS = [
  { key: "Stretches", label: "Stretches" },
  { key: "Flows",     label: "Flows" },
];

export default function Flexibility() {
  const [tab, setTab] = useState("Stretches");
  const [customModalStretch, setCustomModalStretch] = useState(undefined);
  const [customModalReload, setCustomModalReload] = useState(null);

  const handleOpenCustomModal = useCallback((stretch, reloadFn) => {
    setCustomModalStretch(stretch ?? null);
    setCustomModalReload(() => reloadFn);
  }, []);

  const handleCloseModal = useCallback(() => {
    setCustomModalStretch(undefined);
  }, []);

  const handleSaved = useCallback(() => {
    if (customModalReload) customModalReload();
  }, [customModalReload]);

  return (
    <div className="p-5 max-w-7xl mx-auto space-y-6">
      <PageHeader title="Flexibility" />
      <Tabs dataTour="flex-tabs" tabs={TABS} value={tab} onChange={setTab} stretch />

      <div data-tour="flex-content">
        {tab === "Stretches" && <StretchesTab onOpenCustomModal={handleOpenCustomModal} />}
        {tab === "Flows"     && <FlowsTab />}
      </div>

      {customModalStretch !== undefined && (
        <CustomStretchModal
          stretch={customModalStretch}
          onClose={handleCloseModal}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
}
