"use client";

import { useAuth } from "@/lib/AuthContext";
import { database } from "@/lib/firebase";
import { ref, onValue, update } from "firebase/database";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import Link from "next/link";

export default function AdminPage() {
  const { user, role, loading } = useAuth();
  const router = useRouter();
  const [users, setUsers] = useState([]);

  // Auth guard — admin only
  useEffect(() => {
    if (!loading && (!user || role !== "admin")) {
      router.push("/");
    }
  }, [user, role, loading, router]);

  // Subscribe to all users
  useEffect(() => {
    const usersRef = ref(database, "users");
    const unsub = onValue(usersRef, (snap) => {
      const data = snap.val();
      if (!data) { setUsers([]); return; }
      const list = Object.entries(data).map(([uid, info]) => ({
        uid,
        ...info,
      }));
      // Sort: pending first, then by creation date
      list.sort((a, b) => {
        if (a.role === "pending" && b.role !== "pending") return -1;
        if (b.role === "pending" && a.role !== "pending") return 1;
        return (b.createdAt || 0) - (a.createdAt || 0);
      });
      setUsers(list);
    });
    return () => unsub();
  }, []);

  const setUserRole = (uid, newRole) => {
    update(ref(database, `users/${uid}`), { role: newRole });
  };

  if (loading || !user) {
    return (
      <div className="loading-screen">
        <div className="spinner" />
        <div className="loading-text">Loading...</div>
      </div>
    );
  }

  return (
    <div className="app-container">
      <div className="admin-page">
        <Link href="/dashboard" className="admin-back">← Back to Dashboard</Link>

        <div className="dash-header">
          <div className="dash-header-left">
            <span className="dash-label">Administration</span>
            <h1 className="dash-title">User Management</h1>
          </div>
          <div style={{ fontSize: 12, color: "var(--text-secondary)" }}>
            {users.filter((u) => u.role === "pending").length} pending
          </div>
        </div>

        <div className="user-list">
          {users.length === 0 ? (
            <div className="empty-state">
              <div className="empty-state-icon">👥</div>
              <div className="empty-state-text">No users yet</div>
            </div>
          ) : (
            users.map((u) => (
              <div key={u.uid} className="user-row">
                {u.photoURL ? (
                  <img src={u.photoURL} alt="" className="user-row-avatar" referrerPolicy="no-referrer" />
                ) : (
                  <div className="user-row-avatar" style={{
                    background: "var(--bg-elevated)",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: 18,
                  }}>
                    👤
                  </div>
                )}

                <div className="user-row-info">
                  <div className="user-row-name">
                    {u.displayName || "Unknown"}
                    {u.uid === user.uid && (
                      <span style={{ fontSize: 10, color: "var(--text-tertiary)", marginLeft: 8 }}>(you)</span>
                    )}
                  </div>
                  <div className="user-row-email">{u.email}</div>
                </div>

                <span className={`user-row-role ${u.role}`}>{u.role}</span>

                {u.uid !== user.uid && (
                  <div className="user-row-actions">
                    {u.role === "pending" && (
                      <>
                        <button className="approve-btn" onClick={() => setUserRole(u.uid, "approved")}>
                          ✓ Approve
                        </button>
                        <button className="reject-btn" onClick={() => setUserRole(u.uid, "rejected")}>
                          ✕ Reject
                        </button>
                      </>
                    )}
                    {u.role === "approved" && (
                      <>
                        <button className="approve-btn" onClick={() => setUserRole(u.uid, "admin")}>
                          Make Admin
                        </button>
                        <button className="reject-btn" onClick={() => setUserRole(u.uid, "rejected")}>
                          Revoke
                        </button>
                      </>
                    )}
                    {u.role === "rejected" && (
                      <button className="approve-btn" onClick={() => setUserRole(u.uid, "approved")}>
                        ✓ Approve
                      </button>
                    )}
                    {u.role === "admin" && (
                      <button className="reject-btn" onClick={() => setUserRole(u.uid, "approved")}>
                        Remove Admin
                      </button>
                    )}
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
