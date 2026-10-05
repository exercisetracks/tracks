// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { Component, lazy, Suspense } from "react";
import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { ThemeProvider } from "./context/ThemeContext";
import Layout from "./components/Layout";
import Dashboard from "./pages/Dashboard";

function Spinner() {
  return (
    <div className="flex items-center justify-center min-h-screen bg-slate-100 dark:bg-slate-950">
      <div className="w-8 h-8 border-2 border-accent-500 border-t-transparent rounded-full animate-spin" />
    </div>
  );
}

// Lazy-load the less-frequent routes so the dashboard ships a leaner bundle.
const Activities     = lazy(() => import("./pages/Activities"));
const ActivityView   = lazy(() => import("./components/activity/layouts/ActivityView"));
const Goals          = lazy(() => import("./pages/Goals"));
const Training       = lazy(() => import("./pages/Training"));
const Flexibility    = lazy(() => import("./pages/Flexibility"));
const Health         = lazy(() => import("./pages/Health"));
const RacePlan       = lazy(() => import("./pages/RacePlan"));
const RacePlans      = lazy(() => import("./pages/RacePlans"));
const Settings       = lazy(() => import("./pages/Settings"));
const Maps           = lazy(() => import("./pages/maps/MapPage"));
const Music          = lazy(() => import("./pages/Music"));
const Login          = lazy(() => import("./pages/Login"));
const Setup          = lazy(() => import("./pages/Setup"));

class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false };
  }
  static getDerivedStateFromError() {
    return { hasError: true };
  }
  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center min-h-screen bg-slate-100 dark:bg-slate-950 gap-4">
          <p className="text-slate-500 text-lg">Something went wrong.</p>
          <button
            onClick={() => {
              this.setState({ hasError: false });
              window.location.reload();
            }}
            className="btn btn-primary"
          >
            Reload
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

function SetupRoute() {
  const { authStatus } = useAuth();
  // Show a spinner during loading — the account was just created and
  // settings are being saved.  Redirecting too early would unmount
  // the Setup component before settings reach the server.
  if (authStatus === "loading") {
    return <Spinner />;
  }
  // Allow both first-time setup (no admin exists) and onboarding
  // (existing user who hasn't completed the setup wizard).
  if (authStatus !== "needs_setup" && authStatus !== "needs_onboarding") {
    return <Navigate to="/" replace />;
  }
  return <Setup />;
}

function ProtectedRoutes() {
  const { authStatus } = useAuth();

  if (authStatus === "loading") {
    return <Spinner />;
  }

  if (authStatus === "needs_setup") {
    return <Navigate to="/setup" replace />;
  }

  if (authStatus === "needs_onboarding") {
    return <Navigate to="/setup" replace />;
  }

  if (authStatus === "unauthenticated") {
    return <Navigate to="/login" replace />;
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="activities" element={<Activities />} />
        <Route path="activities/:id" element={<ActivityView />} />
        <Route path="goals" element={<Goals />} />
        <Route path="training" element={<Training />} />
        <Route path="flexibility" element={<Flexibility />} />
        <Route path="race-plans" element={<RacePlans />} />
        <Route path="race-plans/:id" element={<RacePlan />} />
        <Route path="health" element={<Health />} />
        <Route path="maps" element={<Maps />} />
        <Route path="music" element={<Music />} />
        <Route path="settings" element={<Settings />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <ThemeProvider>
        <AuthProvider>
          <ErrorBoundary>
            <Suspense fallback={<Spinner />}>
              <Routes>
                <Route path="/login" element={<Login />} />
                <Route path="/setup" element={<SetupRoute />} />
                <Route path="/*" element={<ProtectedRoutes />} />
              </Routes>
            </Suspense>
          </ErrorBoundary>
        </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  );
}
