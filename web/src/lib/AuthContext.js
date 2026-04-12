"use client";

import { createContext, useContext, useEffect, useState } from "react";
import { onAuthStateChanged, signInWithPopup, signOut } from "firebase/auth";
import { ref, get, set, onValue, serverTimestamp } from "firebase/database";
import { auth, googleProvider, database } from "./firebase";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [role, setRole] = useState(null); // "admin" | "approved" | "pending" | null
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const unsub = onAuthStateChanged(auth, async (firebaseUser) => {
      try {
        if (firebaseUser) {
          setUser(firebaseUser);
          // Check role in RTDB
          const userRef = ref(database, `users/${firebaseUser.uid}`);
          const snap = await get(userRef);

          if (snap.exists()) {
            const data = snap.val();
            setRole(data.role || "pending");
          } else {
            // First-time user — check if any admin exists
            const allUsersSnap = await get(ref(database, "users"));
            let hasAdmin = false;
            if (allUsersSnap.exists()) {
              allUsersSnap.forEach((child) => {
                if (child.val().role === "admin") hasAdmin = true;
              });
            }

            const newRole = hasAdmin ? "pending" : "admin";
            await set(userRef, {
              email: firebaseUser.email,
              displayName: firebaseUser.displayName,
              photoURL: firebaseUser.photoURL,
              role: newRole,
              createdAt: serverTimestamp(),
            });
            setRole(newRole);
          }
        } else {
          setUser(null);
          setRole(null);
        }
      } catch (err) {
        console.error("Auth context error:", err);
        // Still set user even if RTDB failed
        if (firebaseUser) {
          setUser(firebaseUser);
          setRole("pending"); // fallback
        }
      }
      setLoading(false);
    });

    return () => unsub();
  }, []);

  // Subscribe to role changes (admin approval)
  useEffect(() => {
    if (!user) return;
    const roleRef = ref(database, `users/${user.uid}/role`);
    const unsub = onValue(roleRef, (snap) => {
      if (snap.exists()) setRole(snap.val());
    });
    return () => unsub();
  }, [user]);

  const login = () => signInWithPopup(auth, googleProvider);
  const logout = () => signOut(auth);

  return (
    <AuthContext.Provider value={{ user, role, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
