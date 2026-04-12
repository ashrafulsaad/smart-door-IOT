"use client";

import { useAuth } from "@/lib/AuthContext";
import { database } from "@/lib/firebase";
import { ref, onValue, push, serverTimestamp, query, orderByChild, limitToLast } from "firebase/database";
import { useRouter } from "next/navigation";
import { useEffect, useState, useRef, useCallback, useMemo } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import {
  Thermometer, Droplets, Sun, Box, Search, Brain, Settings as SettingsIcon,
  Camera, User, Shield, Zap, Clock, Filter, X, LogOut, ChevronRight,
  Play, Eye, Activity, Users, AlertTriangle, LayoutGrid, List
} from "lucide-react";

// ── Sidebar view IDs ──
const VIEWS = { TIMELINE: "timeline", SEARCH: "search", ML: "ml", SETTINGS: "settings" };

export default function DashboardPage() {
  const { user, role, loading, logout } = useAuth();
  const router = useRouter();

  // Navigation
  const [activeView, setActiveView] = useState(VIEWS.TIMELINE);

  // Telemetry
  const [telemetry, setTelemetry] = useState({ temp: "--", humidity: "--", light: "--" });
  const [phoneOnline, setPhoneOnline] = useState(false);

  // Controls
  const [torchOn, setTorchOn] = useState(false);
  const [livestreamActive, setLivestreamActive] = useState(false);
  const [commandFeedback, setCommandFeedback] = useState(null);
  const [streamRes, setStreamRes] = useState(1); // 0=240p, 1=480p, 2=720p
  const [streamFps, setStreamFps] = useState(3); // 1-10 fps
  const RES_OPTIONS = [{w:320,h:240,label:'240p'},{w:640,h:480,label:'480p'},{w:1280,h:720,label:'720p'}];

  // Video
  const canvasRef = useRef(null);
  const [hasFrame, setHasFrame] = useState(false);

  // Events
  const [events, setEvents] = useState([]);

  // Filters
  const [filterMode, setFilterMode] = useState("detections");
  const [searchQuery, setSearchQuery] = useState("");
  const [dateQuick, setDateQuick] = useState("all");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [viewMode, setViewMode] = useState("list"); // "list" | "clustered"

  // Modals
  const [viewerUrl, setViewerUrl] = useState(null);
  const [viewerAnnotatedUrl, setViewerAnnotatedUrl] = useState(null);
  const [showAnnotated, setShowAnnotated] = useState(true);
  const [taggingData, setTaggingData] = useState(null);
  const [espStatus, setEspStatus] = useState(null);        // last status response from ESP32
  const [espFeedback, setEspFeedback] = useState(null);    // brief button feedback

  // ── Auth guard ──
  useEffect(() => {
    if (!loading && (!user || (role !== "admin" && role !== "approved"))) {
      router.push("/");
    }
  }, [user, role, loading, router]);

  // ── Subscriptions ──
  useEffect(() => {
    const telRef = ref(database, "telemetry/latest");
    const unsub = onValue(telRef, (snap) => {
      const data = snap.val();
      if (data) setTelemetry({ temp: data.temp || "--", humidity: data.humidity || "--", light: data.light || "--" });
    });
    return () => unsub();
  }, []);

  useEffect(() => {
    const statusRef = ref(database, "status/phone");
    const unsub = onValue(statusRef, (snap) => setPhoneOnline(snap.val()?.online === true));
    return () => unsub();
  }, []);

  useEffect(() => {
    const lsRef = ref(database, "livestream/status");
    const unsub = onValue(lsRef, (snap) => setLivestreamActive(snap.val()?.active === true));
    return () => unsub();
  }, []);

  // Watch the last ESP status response written back by the phone proxy
  useEffect(() => {
    const espRef = query(ref(database, "commands"), orderByChild("type"), limitToLast(20));
    const unsub = onValue(espRef, (snap) => {
      if (!snap.exists()) return;
      let latest = null;
      snap.forEach((child) => {
        const d = child.val();
        if (d.type === "esp_status" && d.espResponse) latest = d.espResponse;
      });
      if (latest) setEspStatus(latest);
    });
    return () => unsub();
  }, []);


  useEffect(() => {
    const frameRef = ref(database, "livestream/frame");
    const unsub = onValue(frameRef, (snap) => {
      const data = snap.val();
      if (!data?.data) { setHasFrame(false); return; }
      setHasFrame(true);
      const img = new Image();
      img.onload = () => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ctx = canvas.getContext("2d");
        canvas.width = data.width || 320;
        canvas.height = data.height || 240;
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      };
      img.src = `data:image/jpeg;base64,${data.data}`;
    });
    return () => unsub();
  }, []);

  useEffect(() => {
    const logsRef = query(ref(database, "logs"), orderByChild("timestamp"), limitToLast(50));
    const unsub = onValue(logsRef, (snap) => {
      const data = snap.val();
      if (!data) { setEvents([]); return; }

      const grouped = {};
      Object.entries(data).forEach(([key, log]) => {
        const eid = log.eventId || key;
        if (!grouped[eid]) {
          grouped[eid] = {
            eventId: eid, timestamp: log.timestamp, faceDetected: false, faceCount: 0, photos: [],
            telemetry: log.telemetry || null, mlAnalysis: null, motionDetected: false,
          };
        }
        if (log.faceDetected) {
          grouped[eid].faceDetected = true;
          grouped[eid].faceCount = Math.max(grouped[eid].faceCount, log.faceCount || 0);
        }
        if (log.motionDetected) grouped[eid].motionDetected = true;
        if (log.ml_analysis && !grouped[eid].mlAnalysis) grouped[eid].mlAnalysis = log.ml_analysis;
        grouped[eid].photos.push({ url: log.imageUrl, fileName: log.fileName, burstIndex: log.burstIndex });
      });

      const sorted = Object.values(grouped).sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
      setEvents(sorted);
    });
    return () => unsub();
  }, []);

  // ── Commands ──
  const sendCommand = useCallback((type, extra = {}) => {
    push(ref(database, "commands"), { type, ...extra, by: user?.uid || "unknown", timestamp: serverTimestamp(), status: "pending" });
    // Flash feedback
    const feedbackKey = type === "torch_on" || type === "torch_off" ? "torch" : type === "livestream_start" || type === "livestream_stop" ? "view" : type;
    setCommandFeedback(feedbackKey);
    setTimeout(() => setCommandFeedback(null), 1500);
  }, [user]);

  // ── Helpers ──
  const parseLdr = (val) => {
    if (val === "--" || isNaN(val)) return val;
    let num = Number(val);
    num = Math.max(1900, Math.min(4095, num));
    return `${Math.round(((4095 - num) / (4095 - 1900)) * 100)}%`;
  };

  const formatTime = (ts) => {
    if (!ts) return <span>--</span>;
    const d = new Date(ts);
    return (
      <>
        <span style={{ display: 'block', fontWeight: 600 }}>
          {d.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit", hour12: false })}
        </span>
        <span style={{ display: 'block', opacity: 0.7, fontSize: '0.9em' }}>
          {d.toLocaleDateString("en-US", { month: "short", day: "numeric" })}
        </span>
      </>
    );
  };

  const submitTagging = () => {
    const name = taggingData?.name?.trim();
    if (!name) return;

    // Register the face — ML pipeline will download this image and encode it
    sendCommand("register_face", { name, imageUrl: taggingData.url });

    // Queue a re-process of ALL events so existing "Unknown" entries
    // get updated with the newly registered identity
    setTimeout(() => sendCommand("run_ml_all"), 3000); // small delay so register runs first

    setTaggingData(null);
    setViewerUrl(null);
    setViewerAnnotatedUrl(null);

    // Brief toast
    setCommandFeedback("tagged");
    setTimeout(() => setCommandFeedback(null), 4000);
  };


  // ── Smart Filtering ──
  const filteredEvents = useMemo(() => {
    let result = events;

    // Quick date filter
    if (dateQuick !== "all") {
      const hours = { "1h": 1, "6h": 6, "24h": 24, "7d": 168 }[dateQuick] || 0;
      if (hours > 0) {
        const cutoff = Date.now() - hours * 3600 * 1000;
        result = result.filter(e => (e.timestamp || 0) > cutoff);
      }
    }

    // Calendar date range
    if (dateFrom) {
      const from = new Date(dateFrom).getTime();
      result = result.filter(e => (e.timestamp || 0) >= from);
    }
    if (dateTo) {
      const to = new Date(dateTo).getTime() + 86400000; // end of that day
      result = result.filter(e => (e.timestamp || 0) < to);
    }

    // Content filter
    if (filterMode === "detections") {
      // Hide wall/door only shots — keep anything with a detection, face, motion, or ML object
      result = result.filter(e => {
        if (e.faceDetected) return true;
        if (e.motionDetected) return true;
        const ml = e.mlAnalysis;
        if (ml?.yolo?.person_count > 0) return true;
        if (ml?.yolo?.total_objects > 0) return true;
        if (ml?.faces?.count > 0) return true;
        if (ml?.threat?.level && ml.threat.level !== "none") return true;
        // If no ML analysis yet, still show it (hasn't been processed)
        if (!ml) return true;
        return false;
      });
    } else if (filterMode === "faces") {
      result = result.filter(e => e.faceDetected || (e.mlAnalysis?.faces?.count > 0));
    } else if (filterMode === "threats") {
      result = result.filter(e => e.mlAnalysis?.threat?.level && e.mlAnalysis.threat.level !== "none");
    }

    // Search query
    if (searchQuery.trim()) {
      const q = searchQuery.trim().toLowerCase();
      result = result.filter(e => {
        const identified = e.mlAnalysis?.faces?.identified || [];
        if (identified.some(name => name.toLowerCase().includes(q))) return true;
        const reasons = e.mlAnalysis?.threat?.reasons || [];
        if (reasons.some(r => r.toLowerCase().includes(q))) return true;
        const detections = e.mlAnalysis?.yolo?.detections || [];
        if (detections.some(d => d.class.toLowerCase().includes(q))) return true;
        return false;
      });
    }

    return result;
  }, [events, filterMode, searchQuery, dateQuick, dateFrom, dateTo]);

  // ── Clustered grouping (by date) ──
  const clusteredEvents = useMemo(() => {
    const groups = {};
    filteredEvents.forEach(event => {
      const d = new Date(event.timestamp || 0);
      const key = d.toLocaleDateString("en-US", { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
      if (!groups[key]) groups[key] = [];
      groups[key].push(event);
    });
    return Object.entries(groups);
  }, [filteredEvents]);

  // ── Stats ──
  const stats = useMemo(() => {
    let faces = 0, threats = 0, persons = 0, processed = 0;
    events.forEach(e => {
      if (e.faceDetected || e.mlAnalysis?.faces?.count > 0) faces++;
      if (e.mlAnalysis?.threat?.level && e.mlAnalysis.threat.level !== "none") threats++;
      if (e.mlAnalysis?.yolo?.person_count > 0) persons++;
      if (e.mlAnalysis) processed++;
    });
    return { faces, threats, persons, processed, total: events.length };
  }, [events]);

  // ── Check if event has any detections ──
  const getEventBadgeType = (event) => {
    const ml = event.mlAnalysis;
    // threat badge disabled until ML is properly trained
    if (event.faceDetected || ml?.faces?.count > 0) return "face";
    if (ml?.yolo?.person_count > 0) return "person";
    if (ml?.yolo?.total_objects > 0) return "object";
    if (event.motionDetected) return "motion";
    return "empty";
  };

  if (loading || !user) return (
    <div className="loading-screen">
      <div className="spinner" />
      <div className="loading-text">Authenticating...</div>
    </div>
  );

  // ── Sidebar Nav Items ──
  const navItems = [
    { id: VIEWS.TIMELINE, icon: <Box size={20} />, label: "Dashboard" },
    { id: VIEWS.SEARCH, icon: <Search size={20} />, label: "Search" },
    { id: VIEWS.ML, icon: <Brain size={20} />, label: "ML Controls" },
    { id: VIEWS.SETTINGS, icon: <SettingsIcon size={20} />, label: "Settings" },
  ];

  return (
    <div className="app-container">
      {/* ── Sidebar ── */}
      <aside className="sidebar">
        <div className="sidebar-logo" title="Door Sentinel">
          <svg viewBox="0 0 36 36" fill="none" xmlns="http://www.w3.org/2000/svg" width="28" height="28">
            <circle cx="18" cy="18" r="16" stroke="#ffffff" strokeWidth="2"/>
            <circle cx="18" cy="18" r="7" fill="#ffffff" opacity="0.15"/>
            <circle cx="18" cy="18" r="4" fill="#ffffff"/>
            <line x1="18" y1="2" x2="18" y2="8" stroke="#ffffff" strokeWidth="2" strokeLinecap="round"/>
            <line x1="18" y1="28" x2="18" y2="34" stroke="#ffffff" strokeWidth="2" strokeLinecap="round"/>
            <line x1="2" y1="18" x2="8" y2="18" stroke="#ffffff" strokeWidth="2" strokeLinecap="round"/>
            <line x1="28" y1="18" x2="34" y2="18" stroke="#ffffff" strokeWidth="2" strokeLinecap="round"/>
          </svg>
        </div>
        <div className="sidebar-nav">
          {navItems.map((item) => (
            <div
              key={item.id}
              className={`nav-item ${activeView === item.id ? 'active' : ''}`}
              onClick={() => setActiveView(item.id)}
              title={item.label}
            >
              {item.icon}
            </div>
          ))}
        </div>
        <div className="sidebar-bottom">
          <div className="nav-item" onClick={logout} title="Sign Out">
            <LogOut size={20} />
          </div>
        </div>
      </aside>

      {/* ── Main content ── */}
      <main className="dashboard-wrapper">

        {/* ── Top Header ── */}
        <header className="dash-header">
          <div className="header-left">
            <h1 className="dash-title">
              {activeView === VIEWS.TIMELINE && "Security Log"}
              {activeView === VIEWS.SEARCH && "Search & Filter"}
              {activeView === VIEWS.ML && "ML Pipeline"}
              {activeView === VIEWS.SETTINGS && "Settings"}
            </h1>
          </div>
          <div className="header-right">
            <div className="header-metrics">
              <div className="metric-pill" title="Temperature">
                <Thermometer size={14} color="var(--accent-blue)" /> {telemetry.temp === "--" ? "--" : `${telemetry.temp}°`}
              </div>
              <div className="metric-pill" title="Humidity">
                <Droplets size={14} color="var(--accent-blue)" /> {telemetry.humidity === "--" ? "--" : `${telemetry.humidity}%`}
              </div>
              <div className="metric-pill" title="Light Level">
                <Sun size={14} color="var(--accent-orange)" /> {parseLdr(telemetry.light)}
              </div>
            </div>

            {role === "admin" && (
              <Link href="/admin" style={{ textDecoration: 'none' }}>
                <button className="btn-outline"><Users size={14} /> Users</button>
              </Link>
            )}

            <div className="user-profile">
              <div className="user-avatar-wrap">
                <img src={user.photoURL} alt="" referrerPolicy="no-referrer" />
                <div className={`user-status-dot ${phoneOnline ? 'online' : 'offline'}`} title="Edge Device Status" />
              </div>
              <div className="user-info">
                <span className="user-name">{user.displayName || "Admin"}</span>
                <span className="user-role">{role}</span>
              </div>
            </div>
          </div>
        </header>

        {/* ══════════════════════════════════════════════════ */}
        {/* ── VIEW: TIMELINE ── */}
        {/* ══════════════════════════════════════════════════ */}
        {activeView === VIEWS.TIMELINE && (
          <div className="dashboard-grid">
            <section className="card" style={{ minHeight: '80vh' }}>
              <div className="card-title">
                <span>EVENT TIMELINE</span>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div className="view-toggle">
                    <button className={`view-toggle-btn ${viewMode === 'list' ? 'active' : ''}`} onClick={() => setViewMode('list')}><List size={12} /> List</button>
                    <button className={`view-toggle-btn ${viewMode === 'clustered' ? 'active' : ''}`} onClick={() => setViewMode('clustered')}><LayoutGrid size={12} /> Grid</button>
                  </div>
                  <span style={{ fontWeight: 500, fontSize: 11, letterSpacing: 0 }}>
                    {filteredEvents.length} of {events.length}
                  </span>
                </div>
              </div>

              {/* Filter bar */}
              <div className="filter-bar">
                {[
                  { key: "all", label: "Show All" },
                  { key: "detections", label: "Detections" },
                  { key: "faces", label: "Faces Only" },
                  /* { key: "threats", label: "Threats" }, // hidden until ML trained */
                ].map(f => (
                  <span key={f.key} className={`filter-chip ${filterMode === f.key ? 'active' : ''}`} onClick={() => setFilterMode(f.key)}>
                    {f.label}
                  </span>
                ))}
                <input
                  type="text"
                  className="filter-search"
                  placeholder="Search person, object..."
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                />
              </div>

              {/* Quick date buttons + date picker */}
              <div className="filter-bar" style={{ marginBottom: 16 }}>
                {[
                  { key: "all", label: "All Time" },
                  { key: "1h", label: "1h" },
                  { key: "6h", label: "6h" },
                  { key: "24h", label: "24h" },
                  { key: "7d", label: "7d" },
                ].map(d => (
                  <span key={d.key} className={`filter-chip ${dateQuick === d.key ? 'active' : ''}`} onClick={() => { setDateQuick(d.key); setDateFrom(""); setDateTo(""); }}>
                    {d.label}
                  </span>
                ))}
                <input type="date" className="filter-date-input" value={dateFrom} onChange={e => { setDateFrom(e.target.value); setDateQuick("all"); }} title="From date" />
                <span style={{ color: 'var(--text-secondary)', fontSize: 10 }}>to</span>
                <input type="date" className="filter-date-input" value={dateTo} onChange={e => { setDateTo(e.target.value); setDateQuick("all"); }} title="To date" />
              </div>

              {filteredEvents.length === 0 ? (
                <div className="empty-state">
                  <div className="empty-state-icon">📭</div>
                  <div className="empty-state-text">
                    {events.length > 0 ? "No events match your filters" : "No events recorded yet"}
                  </div>
                </div>
              ) : (
                <div className="timeline-list">
                  {viewMode === 'list' ? (
                    <AnimatePresence>
                      {filteredEvents.map((event) => {
                        const badge = getEventBadgeType(event);
                        return (
                          <motion.div key={event.eventId} layout initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="event-row">
                            <div className="event-time-col">{formatTime(event.timestamp)}</div>
                            <div className="event-content-col">
                              <div className="event-tags-bar">
                                {badge === "face" && <span className="tag detected-face">✓ Face {event.faceCount > 1 ? `(${event.faceCount})` : ""}</span>}
                                {badge === "person" && <span className="tag detected-obj">👤 Person</span>}
                                {badge === "object" && <span className="tag detected-obj">📦 Object</span>}
                                {badge === "motion" && <span className="tag detected-motion">⚡ Motion</span>}
                                {/* threat badge hidden until ML trained */}
                                {badge === "empty" && <span className="tag ml-stat">📷 Capture</span>}


                                {event.mlAnalysis?.faces?.identified?.length > 0 && (
                                  <span className="tag ml-stat">🏷️ {event.mlAnalysis.faces.identified.join(", ")}</span>
                                )}

                                {event.mlAnalysis?.yolo?.detections?.length > 0 && (
                                  <span className="tag ml-stat">
                                    {event.mlAnalysis.yolo.detections.map(d => d.class).filter((v,i,a) => a.indexOf(v)===i).join(", ")}
                                  </span>
                                )}
                              </div>

                              <div className="event-gallery">
                                {event.photos.sort((a,b)=>(a.burstIndex||0)-(b.burstIndex||0)).map((p, i) => p.url && (
                                  <img key={i} src={p.url} className="gallery-thumb"
                                    onClick={() => {
                                      setViewerUrl(p.url);
                                      setViewerAnnotatedUrl(event.mlAnalysis?.annotatedImageUrl || null);
                                      setShowAnnotated(true);
                                    }}
                                    alt="" loading="lazy" />
                                ))}
                              </div>

                              {/* ── Face identity strip ── */}
                              {event.mlAnalysis?.faces?.details?.length > 0 && (
                                <div className="face-strip">
                                  {event.mlAnalysis.faces.details.map((face, fi) => (
                                    <div
                                      key={fi}
                                      className="face-card"
                                      title={`${face.name} (≈${Math.round((face.confidence||0)*100)}% match)`}
                                      onClick={() => face.cropUrl && (setViewerUrl(face.cropUrl), setViewerAnnotatedUrl(null))}
                                      style={{ cursor: face.cropUrl ? 'pointer' : 'default' }}
                                    >
                                      {face.cropUrl
                                        ? <img src={face.cropUrl} alt={face.name} className="face-crop-img" />
                                        : <div className="face-crop-placeholder">{face.name === 'Unknown' ? '?' : face.name[0].toUpperCase()}</div>
                                      }
                                      <div className={`face-name-label ${face.name === 'Unknown' ? 'unknown' : 'known'}`}>
                                        {face.name}
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              )}

                            </div>
                          </motion.div>
                        );
                      })}
                    </AnimatePresence>
                  ) : (
                    /* Clustered (Google Photos) view */
                    clusteredEvents.map(([dateLabel, dateEvents]) => (
                      <div key={dateLabel} className="date-cluster">
                        <div className="date-cluster-header">
                          <span className="date-cluster-label">{dateLabel}</span>
                          <span className="date-cluster-count">{dateEvents.length} event{dateEvents.length !== 1 ? 's' : ''}</span>
                          <span className="date-cluster-line" />
                        </div>
                        <div className="cluster-grid">
                          {dateEvents.map(event => {
                            const badge = getEventBadgeType(event);
                            const time = new Date(event.timestamp).toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit", hour12: false });
                            return (
                              <div key={event.eventId} className="cluster-card">
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                  <span className="cluster-card-time">{time}</span>
                                  <div className="event-tags-bar">
                                    {badge === "face" && <span className="tag detected-face">✓ Face</span>}
                                    {badge === "person" && <span className="tag detected-obj">👤</span>}
                                    {badge === "object" && <span className="tag detected-obj">📦</span>}
                                    {badge === "motion" && <span className="tag detected-motion">⚡</span>}
                                    {/* threat badge hidden */}
                                    {badge === "empty" && <span className="tag ml-stat">📷</span>}
                                  </div>
                                </div>
                                <div className="cluster-card-gallery">
                                  {event.photos.sort((a,b)=>(a.burstIndex||0)-(b.burstIndex||0)).map((p, i) => p.url && (
                                    <img key={i} src={p.url}
                                      onClick={() => {
                                        setViewerUrl(p.url);
                                        const annotated = event.mlAnalysis?.annotatedImageUrl || null;
                                        setViewerAnnotatedUrl(annotated);
                                        setShowAnnotated(true);
                                      }}
                                      alt="" loading="lazy" />
                                  ))}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    ))
                  )}
                </div>
              )}
            </section>

            {/* R: Side Panel */}
            <aside className="sidebar-panel">
              <div className="card">
                <div className="card-title">LIVE FEED</div>
                <div className="video-frame">
                  <canvas ref={canvasRef} style={{ display: hasFrame ? "block" : "none" }} />
                  {!hasFrame && (
                    <div className="video-overlay-text">
                      {livestreamActive ? "Waiting for stream..." : "Stream Paused"}
                    </div>
                  )}
                </div>

                <div className="ctrl-grid">
                  <button className={`btn btn-warning ${torchOn ? 'active' : ''}`} onClick={() => { setTorchOn(!torchOn); sendCommand(torchOn ? "torch_off" : "torch_on"); }}>
                    {commandFeedback === "torch" ? "✓ Sent!" : "🔦 Torch"}
                  </button>
                  <button className={`btn btn-danger ${livestreamActive ? 'active' : ''}`} onClick={() => {
                    const res = RES_OPTIONS[streamRes];
                    sendCommand(livestreamActive ? "livestream_stop" : "livestream_start", {fps: streamFps, width: res.w, height: res.h});
                  }}>
                    {commandFeedback === "view" ? "✓ Sent!" : <><Camera size={14} /> View</>}
                  </button>
                  <button className="btn btn-primary" style={{ gridColumn: '1 / -1' }} onClick={() => sendCommand("capture")}>
                    {commandFeedback === "capture" ? "✓ Command Sent!" : "Force Capture"}
                  </button>
                  {!phoneOnline && (
                    <div style={{ gridColumn: '1 / -1', fontSize: 10, color: '#ff5c5c', textAlign: 'center', marginTop: 2 }}>
                      ⚠ Phone offline — commands queued
                    </div>
                  )}
                </div>

                <div className="res-slider-wrap">
                  <label>Resolution</label>
                  <input type="range" min={0} max={2} step={1} value={streamRes} onChange={e => setStreamRes(Number(e.target.value))} />
                  <span className="res-slider-val">{RES_OPTIONS[streamRes].label}</span>
                </div>
                <div className="res-slider-wrap">
                  <label>FPS</label>
                  <input type="range" min={1} max={10} step={1} value={streamFps} onChange={e => setStreamFps(Number(e.target.value))} />
                  <span className="res-slider-val">{streamFps} fps</span>
                </div>

                {/* ESP32 controls inline */}
                <div style={{ borderTop: '1px solid rgba(255,255,255,0.06)', marginTop: 16, paddingTop: 16 }}>
                  <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: 1.5, color: 'var(--text-secondary)', marginBottom: 12, display: 'flex', justifyContent: 'space-between' }}>
                    <span>ESP32</span>
                    <span style={{ fontWeight: 500, textTransform: 'none', letterSpacing: 0 }}>via phone proxy</span>
                  </div>
                  <div className="ctrl-grid">
                    <button className="btn btn-warning" onClick={() => {
                      sendCommand("esp_toggle");
                      setEspFeedback('toggle');
                      setTimeout(() => setEspFeedback(null), 2000);
                    }}>
                      {espFeedback === 'toggle' ? '✓ Sent!' : '💡 Light'}
                    </button>
                    <button className="btn btn-secondary" onClick={() => {
                      sendCommand("esp_toggle_auto");
                      setEspFeedback('auto');
                      setTimeout(() => setEspFeedback(null), 2000);
                    }}>
                      {espFeedback === 'auto' ? '✓ Sent!' : '🤖 Auto'}
                    </button>
                  </div>
                  {espStatus && (
                    <div style={{ marginTop: 8, fontSize: 11, color: 'var(--text-secondary)', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                      {espStatus}
                    </div>
                  )}
                </div>

                {/* Edge device status */}
                <div style={{ borderTop: '1px solid rgba(255,255,255,0.06)', marginTop: 16, paddingTop: 12, display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                  <span style={{ color: 'var(--text-secondary)' }}>Edge Device</span>
                  <span style={{ fontWeight: 700, color: phoneOnline ? 'var(--accent-green)' : '#ff5c5c' }}>{phoneOnline ? 'Armed' : 'Offline'}</span>
                </div>
              </div>
            </aside>
          </div>
        )}

        {/* ══════════════════════════════════════════════════ */}
        {/* ── VIEW: SEARCH ── */}
        {/* ══════════════════════════════════════════════════ */}
        {activeView === VIEWS.SEARCH && (
          <div className="dashboard-grid">
            <section className="card" style={{ minHeight: '80vh' }}>
              <div className="card-title">SEARCH & FILTER</div>

              <div className="filter-bar">
                <input
                  type="text"
                  className="filter-search"
                  style={{ flex: 2 }}
                  placeholder="Search by person name, object class, or keyword..."
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  autoFocus
                />
                {searchQuery && (
                  <span className="filter-chip" onClick={() => setSearchQuery("")}>
                    <X size={12} /> Clear
                  </span>
                )}
              </div>

              <div className="filter-bar">
                {[
                  { key: "all", label: "All Events" },
                  { key: "detections", label: "With Detections" },
                  { key: "faces", label: "Faces Only" },
                  /* { key: "threats", label: "Threats Only" }, // hidden until ML trained */
                ].map(f => (
                  <span key={f.key} className={`filter-chip ${filterMode === f.key ? 'active' : ''}`} onClick={() => setFilterMode(f.key)}>
                    {f.label}
                  </span>
                ))}
              </div>

              <div className="filter-bar" style={{ marginBottom: 20 }}>
                <span style={{ fontSize: 10, color: 'var(--text-secondary)', fontWeight: 600 }}>TIME:</span>
                {[
                  { key: "all", label: "All" },
                  { key: "1h", label: "1h" },
                  { key: "6h", label: "6h" },
                  { key: "24h", label: "24h" },
                  { key: "7d", label: "7d" },
                ].map(d => (
                  <span key={d.key} className={`filter-chip ${dateQuick === d.key ? 'active' : ''}`} onClick={() => { setDateQuick(d.key); setDateFrom(""); setDateTo(""); }}>
                    {d.label}
                  </span>
                ))}
                <input type="date" className="filter-date-input" value={dateFrom} onChange={e => { setDateFrom(e.target.value); setDateQuick("all"); }} />
                <span style={{ color: 'var(--text-secondary)', fontSize: 10 }}>to</span>
                <input type="date" className="filter-date-input" value={dateTo} onChange={e => { setDateTo(e.target.value); setDateQuick("all"); }} />
              </div>

              <div style={{ fontSize: 11, color: 'var(--text-secondary)', marginBottom: 12 }}>
                {filteredEvents.length} result{filteredEvents.length !== 1 ? "s" : ""}
                {searchQuery && <> for &quot;<span style={{ color: 'white' }}>{searchQuery}</span>&quot;</>}
              </div>

              {filteredEvents.length === 0 ? (
                <div className="empty-state">
                  <div className="empty-state-icon">🔍</div>
                  <div className="empty-state-text">No events match your search criteria</div>
                </div>
              ) : (
                <div className="timeline-list">
                  {filteredEvents.map((event) => {
                    const badge = getEventBadgeType(event);
                    return (
                      <div key={event.eventId} className="event-row">
                        <div className="event-time-col">{formatTime(event.timestamp)}</div>
                        <div className="event-content-col">
                          <div className="event-tags-bar">
                            {badge === "face" && <span className="tag detected-face">✓ Face</span>}
                            {badge === "person" && <span className="tag detected-obj">👤 Person</span>}
                            {badge === "object" && <span className="tag detected-obj">📦 Object</span>}
                            {badge === "motion" && <span className="tag detected-motion">⚡ Motion</span>}
                            {/* threat badge hidden */}
                            {event.mlAnalysis?.faces?.identified?.length > 0 && (
                              <span className="tag ml-stat">🏷️ {event.mlAnalysis.faces.identified.join(", ")}</span>
                            )}
                            {event.mlAnalysis?.yolo?.detections?.length > 0 && (
                              <span className="tag ml-stat">
                                {event.mlAnalysis.yolo.detections.map(d => d.class).filter((v,i,a) => a.indexOf(v)===i).join(", ")}
                              </span>
                            )}
                          </div>
                          <div className="event-gallery">
                            {event.photos.sort((a,b)=>(a.burstIndex||0)-(b.burstIndex||0)).map((p, i) => p.url && (
                              <img key={i} src={p.url} className="gallery-thumb"
                                onClick={() => {
                                  setViewerUrl(p.url);
                                  const annotated = event.mlAnalysis?.annotatedImageUrl || null;
                                  setViewerAnnotatedUrl(annotated);
                                  setShowAnnotated(true);
                                }}
                                alt="" loading="lazy" />
                            ))}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </section>

            {/* Search sidebar: stats */}
            <aside>
              <div className="card">
                <div className="card-title">SEARCH STATS</div>
                <div className="insight-row"><span>Showing</span><span className="insight-val">{filteredEvents.length}</span></div>
                <div className="insight-row"><span>Total Events</span><span className="insight-val">{stats.total}</span></div>
                <div className="insight-row"><span>With Faces</span><span className="insight-val">{stats.faces}</span></div>
                {/* Threats row hidden until ML trained */}
              </div>
            </aside>
          </div>
        )}

        {/* ══════════════════════════════════════════════════ */}
        {/* ── VIEW: ML CONTROLS ── */}
        {/* ══════════════════════════════════════════════════ */}
        {activeView === VIEWS.ML && (
          <div className="dashboard-grid">
            <section className="card" style={{ minHeight: '80vh' }}>
              <div className="card-title">ML PIPELINE CONTROLS</div>

              <div className="ml-controls-panel">
                <div className="ml-stat-grid">
                  <div className="ml-stat-card">
                    <div className="ml-stat-val">{stats.total}</div>
                    <div className="ml-stat-label">Total Events</div>
                  </div>
                  <div className="ml-stat-card">
                    <div className="ml-stat-val">{stats.processed}</div>
                    <div className="ml-stat-label">ML Processed</div>
                  </div>
                  <div className="ml-stat-card">
                    <div className="ml-stat-val">{stats.persons}</div>
                    <div className="ml-stat-label">Persons Found</div>
                  </div>
                  {/* Threats stat hidden until ML trained */}
                </div>

                <div style={{ marginTop: 8 }}>
                  <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 12 }}>Actions</div>
                  <div className="ctrl-grid">
                    <button className="btn btn-accent" onClick={() => sendCommand("run_ml_all")}>
                      <Play size={14} /> Process All
                    </button>
                    <button className="btn btn-accent" onClick={() => sendCommand("ml_watch_start")}>
                      <Eye size={14} /> Watch Mode
                    </button>
                    <button className="btn btn-secondary" onClick={() => sendCommand("ml_watch_stop")}>
                      <X size={14} /> Stop Watch
                    </button>
                    <button className="btn btn-secondary" onClick={() => sendCommand("ml_stats")}>
                      <Activity size={14} /> Refresh Stats
                    </button>
                  </div>
                </div>

                <div style={{ marginTop: 16 }}>
                  <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 12 }}>Pipeline Info</div>
                  <div className="insight-row"><span>Model</span><span className="insight-val">YOLOv8n</span></div>
                  <div className="insight-row"><span>Classes</span><span className="insight-val">80 (COCO)</span></div>
                  <div className="insight-row"><span>Face Engine</span><span className="insight-val">dlib HOG</span></div>
                  <div className="insight-row"><span>Confidence</span><span className="insight-val">40%</span></div>
                  <div className="insight-row"><span>Face Tolerance</span><span className="insight-val">0.6</span></div>
                  <div className="insight-row"><span>Unprocessed</span><span className="insight-val">{stats.total - stats.processed}</span></div>
                </div>
              </div>
            </section>

            <aside>
              {/* THREAT SCORING card hidden until ML is properly trained */}
              <div className="card">
                <div className="card-title">SYSTEM</div>
                <div className="insight-row"><span>Dashboard</span><span className="insight-val">Next.js 15</span></div>
                <div className="insight-row"><span>ML Engine</span><span className="insight-val">Python 3</span></div>
                <div className="insight-row"><span>Edge Device</span><span className="insight-val" style={{ color: phoneOnline ? 'var(--accent-green)' : '#ff5c5c' }}>{phoneOnline ? 'Online' : 'Offline'}</span></div>
                <div className="insight-row"><span>Backend</span><span className="insight-val">Firebase RTDB</span></div>
              </div>

            </aside>
          </div>
        )}

        {/* ══════════════════════════════════════════════════ */}
        {/* ── VIEW: SETTINGS ── */}
        {/* ══════════════════════════════════════════════════ */}
        {activeView === VIEWS.SETTINGS && (
          <div className="dashboard-grid">
            <section className="card" style={{ minHeight: '80vh' }}>
              <div className="card-title">SETTINGS</div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div className="insight-row"><span>Signed in as</span><span className="insight-val">{user.email}</span></div>
                <div className="insight-row"><span>Role</span><span className="insight-val" style={{ textTransform: 'uppercase' }}>{role}</span></div>
                <div className="insight-row"><span>UID</span><span className="insight-val" style={{ fontSize: 9, fontFamily: 'monospace' }}>{user.uid}</span></div>

                <div style={{ marginTop: 24, display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {role === "admin" && (
                    <Link href="/admin" style={{ textDecoration: 'none' }}>
                      <button className="btn btn-accent" style={{ width: '100%' }}>
                        <Users size={14} /> User Management <ChevronRight size={14} />
                      </button>
                    </Link>
                  )}
                  <button className="btn btn-danger" onClick={logout} style={{ width: '100%' }}>
                    <LogOut size={14} /> Sign Out
                  </button>
                </div>
              </div>
            </section>

            <aside>
              <div className="card">
                <div className="card-title">SYSTEM</div>
                <div className="insight-row"><span>Dashboard</span><span className="insight-val">Next.js 16</span></div>
                <div className="insight-row"><span>ML Engine</span><span className="insight-val">Python 3</span></div>
                <div className="insight-row"><span>Edge Device</span><span className="insight-val" style={{ color: phoneOnline ? 'var(--accent-green)' : '#ff5c5c' }}>{phoneOnline ? 'Online' : 'Offline'}</span></div>
                <div className="insight-row"><span>Backend</span><span className="insight-val">Firebase RTDB</span></div>
              </div>
            </aside>
          </div>
        )}
      </main>

      {/* ── Image Viewer Modal ── */}
      {viewerUrl && !taggingData && (
        <div className="modal-overlay" onClick={() => { setViewerUrl(null); setViewerAnnotatedUrl(null); }}>
          <div className="modal-dialog modal-iv" onClick={e => e.stopPropagation()}>
            <img
              src={(showAnnotated && viewerAnnotatedUrl) ? viewerAnnotatedUrl : viewerUrl}
              alt=""
              style={{ maxHeight: '72vh', width: '100%', objectFit: 'contain', borderRadius: 8 }}
            />
            <div className="modal-actions" style={{ width: '100%', justifyContent: 'center', flexWrap: 'wrap', gap: 8 }}>
              {viewerAnnotatedUrl && (
                <button
                  className={`btn ${showAnnotated ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setShowAnnotated(!showAnnotated)}
                  style={{ flex: '0 0 auto' }}
                >
                  {showAnnotated ? '🔬 Annotated' : '📷 Original'}
                </button>
              )}
              <button className="btn btn-secondary" onClick={() => { setViewerUrl(null); setViewerAnnotatedUrl(null); }}>Close</button>
              <button className="btn btn-primary" onClick={() => setTaggingData({ url: viewerUrl, name: "" })}>Teach ML Recognition</button>
            </div>
          </div>
        </div>
      )}

      {/* ── Tagging Modal ── */}
      {taggingData && (
        <div className="modal-overlay" onClick={() => setTaggingData(null)}>
          <div className="modal-dialog" onClick={e => e.stopPropagation()}>
            <div className="modal-title">Register Identity</div>

            {/* Face preview */}
            {taggingData.url && (
              <img
                src={taggingData.url}
                alt="face"
                style={{ width: 100, height: 100, borderRadius: '50%', objectFit: 'cover',
                         border: '3px solid rgba(59,130,246,0.5)', margin: '0 auto 12px',
                         display: 'block' }}
              />
            )}

            <div className="modal-desc">
              Type this person's name. The ML pipeline will:
              <ol style={{ marginTop: 6, marginLeft: 16, lineHeight: 1.8 }}>
                <li>Learn their face from this image</li>
                <li>Re-scan <strong>all past events</strong> and tag any matches</li>
                <li>Automatically identify them in future captures</li>
              </ol>
            </div>
            <input
              autoFocus
              className="modal-input"
              placeholder="e.g. John Doe"
              value={taggingData.name}
              onChange={e => setTaggingData({ ...taggingData, name: e.target.value })}
              onKeyDown={e => e.key === 'Enter' && submitTagging()}
            />
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setTaggingData(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={submitTagging}>Save &amp; Re-scan All</button>
            </div>
          </div>
        </div>
      )}

      {/* ── Registration Toast ── */}
      {commandFeedback === 'tagged' && (
        <div style={{
          position: 'fixed', bottom: 24, left: '50%', transform: 'translateX(-50%)',
          background: 'rgba(144,215,105,0.15)', border: '1px solid var(--accent-green)',
          color: 'var(--accent-green)', padding: '12px 24px', borderRadius: 12,
          fontSize: 14, fontWeight: 600, zIndex: 9999, backdropFilter: 'blur(12px)',
          boxShadow: '0 8px 32px rgba(0,0,0,0.5)'
        }}>
          ✅ Face registered — re-scanning all events (this may take a few minutes)
        </div>
      )}
    </div>
  );
}
