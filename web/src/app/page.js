"use client";

import { useAuth } from "@/lib/AuthContext";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

export default function LoginPage() {
  const { user, role, loading, login } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && user && (role === "admin" || role === "approved")) {
      router.push("/dashboard");
    }
  }, [user, role, loading, router]);

  // Show spinner while auth is loading OR user is authenticated and we're fetching role OR redirect is pending
  if (loading || (user && !role) || (user && (role === "admin" || role === "approved"))) {
    return (
      <div className="loading-screen">
        <div className="spinner" />
        <div className="loading-text">{user ? "Redirecting..." : "Initializing..."}</div>
      </div>
    );
  }

  // Logged in but pending approval
  if (user && role === "pending") {
    return (
      <div className="login-page">
        <div className="login-card">
          <div className="pending-emoji">⏳</div>
          <h2 className="pending-title">Pending Approval</h2>
          <p className="pending-text">
            Your account <strong>{user.email}</strong> is waiting for admin approval.
            You&apos;ll get access once an administrator verifies your identity.
          </p>
          <button className="logout-btn" onClick={() => window.location.reload()} style={{ width: "100%" }}>
            Refresh Status
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-badge">
          <svg viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg" width="22" height="22">
            <circle cx="18" cy="18" r="16" stroke="currentColor" strokeWidth="2"/>
            <circle cx="18" cy="18" r="7" fill="currentColor" opacity="0.15"/>
            <circle cx="18" cy="18" r="4" fill="currentColor"/>
            <line x1="18" y1="2" x2="18" y2="8" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            <line x1="18" y1="28" x2="18" y2="34" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            <line x1="2" y1="18" x2="8" y2="18" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            <line x1="28" y1="18" x2="34" y2="18" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
          </svg>
          DOOR SENTINEL
        </div>
        <h1 className="login-title">Security Monitor</h1>
        <p className="login-subtitle">
          Sign in to access your real-time security dashboard,
          camera controls, and event history.
        </p>
        <button className="google-btn" onClick={login}>
          <svg viewBox="0 0 24 24">
            <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"/>
            <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
            <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
            <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
          </svg>
          Sign in with Google
        </button>
      </div>
    </div>
  );
}
