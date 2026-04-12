/*
 * ============================================================
 * DOOR SENTINEL — ESP32 MASTER CONTROLLER (ULTRA-FAST ISR)
 * ============================================================
 * Features:
 * - Hardware Interrupt (ISR) on IR Pin for microsecond response.
 * - High-Priority FreeRTOS Worker guarantees instant HTTP dispatch.
 * - Web Server runs gracefully in the background (lower priority).
 * - Auto-Light Toggle included to prevent nuisance triggering.
 * ============================================================
 */

#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <WebServer.h>
#include <DHT.h>
#include <ESP32Servo.h>
#include <WiFiUdp.h>
#include <ESPmDNS.h>

// --- PIN DEFINITIONS ---
#define IR_PIN        33
#define TRIG_PIN      26
#define ECHO_PIN      27
#define DHT_PIN       25
#define LDR_ANALOG_PIN 32
#define SERVO_PIN     14

// --- NETWORK SETTINGS ---
const char* SSID = "Vexil";
const char* PASS = "00000000";
const char* MDNS_NAME = "sentinel";

String phoneTriggerUrl = "";
String phoneStopUrl    = "";
IPAddress phoneIP;

// --- UDP TELEMETRY SETTINGS ---
const int UDP_PORT = 8081;
WiFiUDP udp;
unsigned long lastTelemetryTime = 0;
const int TELEMETRY_INTERVAL = 2000;

// --- SYSTEM THRESHOLDS & SERVO ANGLES ---
const int DARK_THRESHOLD = 2800;
const int ANGLE_STANDBY  = 155;
const int ANGLE_ON       = 120;
const int ANGLE_OFF      = 175;
const unsigned long CAPTURE_WINDOW = 5000;

float cachedTemp = 0.0;
float cachedHum = 0.0;
int cachedLight = 0;

WebServer server(80); 
Servo lightServo;
DHT dht(DHT_PIN, DHT11);

bool systemTurnedLightOn = false; 
bool manualLightStatus = false;   
bool autoLightEnabled = true;     // NEW: Toggle to prevent annoying auto-flicks

enum SystemState { IDLE, CAPTURING };
SystemState currentState = IDLE;
unsigned long lastTriggerTime = 0; 

// --- NON-BLOCKING SERVO VARIABLES ---
bool isServoMoving = false;
unsigned long servoStartTime = 0;
const int SERVO_MOVE_TIME = 600;

// --- HARDWARE INTERRUPT (ISR) VARIABLES ---
// 'volatile' is strictly required for variables changed inside an interrupt
volatile bool intruderDetected = false;

// --- FREERTOS NETWORK WORKER VARIABLES ---
volatile bool pendingHttpRequest = false;
String pendingUrl = "";
String pendingLabel = "";

// ============================================================
// HARDWARE INTERRUPT SERVICE ROUTINE (ISR)
// ============================================================
// This function lives in the ESP32's fastest RAM (IRAM). 
// It pauses the CPU instantly when the IR sensor goes LOW.
void IRAM_ATTR irSensorISR() {
  if (currentState == IDLE) {
    intruderDetected = true;
  }
}

// ============================================================
// HELPER FUNCTIONS
// ============================================================

// Start the servo movement without blocking the code
void triggerServo(int angle) {
  lightServo.write(angle);
  servoStartTime = millis();
  isServoMoving = true;
  Serial.printf("[SERVO] Flicking to angle %d...\n", angle);
}

// Check if it's time to pull the servo arm back
void updateServo() {
  if (isServoMoving && (millis() - servoStartTime >= SERVO_MOVE_TIME)) {
    lightServo.write(ANGLE_STANDBY);
    isServoMoving = false;
    Serial.println("[SERVO] Returned to standby.");
  }
}

// Temporary bypass for ultrasonic sensor
bool confirmPerson() {
  return 1; // Bypassed for max speed
}

// Pass the URL to the background worker so the main loop doesn't freeze
void dispatchBackgroundRequest(String url, const char* label) {
  if (url == "") return;
  pendingUrl = url;
  pendingLabel = String(label);
  pendingHttpRequest = true; // Signals the high-priority worker to wake up instantly
}

// ============================================================
// FREERTOS BACKGROUND WORKER (High Priority on Core 0)
// ============================================================
// This task waits in the background and fires HTTP requests
void networkWorkerTask(void *pvParameters) {
  for(;;) {
    if (pendingHttpRequest) {
      String urlToCall = pendingUrl;
      String labelToUse = pendingLabel;
      pendingHttpRequest = false; 
      
      HTTPClient http;
      http.begin(urlToCall);
      http.setTimeout(3000); 
      
      Serial.printf("\n[WORKER] %s - Contacting: %s\n", labelToUse.c_str(), urlToCall.c_str());
      
      int code = http.GET(); 
      
      if (code > 0) {
        Serial.printf("[WORKER] %s - SUCCESS! HTTP %d\n", labelToUse.c_str(), code);
      } else {
        Serial.printf("[WORKER] %s - FAILED! Error: %s\n", labelToUse.c_str(), http.errorToString(code).c_str());
      }
      
      http.end();
    }
    vTaskDelay(10 / portTICK_PERIOD_MS); // Very short yield to keep response time blistering fast
  }
}

// ============================================================
// WEB SERVER HANDLERS
// ============================================================

void handleRoot() {
  // We use R"=====( ... )=====" here to guarantee the compiler never gets confused by the HTML/JS characters
  String html = R"=====(
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Door Sentinel — ESP32</title>
  <style>
    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Inter', -apple-system, sans-serif;
      background: #000; color: #e4e4e7;
      min-height: 100vh; padding: 20px;
      display: flex; justify-content: center; align-items: flex-start;
    }
    .container { max-width: 460px; width: 100%; }

    .header {
      text-align: center; margin-bottom: 28px;
    }
    .logo-ring {
      width: 48px; height: 48px; margin: 0 auto 14px;
      border: 2px solid rgba(255,255,255,0.7); border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
    }
    .logo-dot { width: 12px; height: 12px; border-radius: 50%; background: #fff; }
    .header h1 {
      font-size: 18px; font-weight: 700; letter-spacing: 2px;
      text-transform: uppercase; color: #fff;
    }
    .header .sub { font-size: 11px; color: #71717a; margin-top: 4px; letter-spacing: 1px; }

    .card {
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.06);
      border-radius: 16px; padding: 24px; margin-bottom: 16px;
      backdrop-filter: blur(12px);
    }
    .card-title {
      font-size: 10px; font-weight: 700; text-transform: uppercase;
      letter-spacing: 2px; color: #71717a; margin-bottom: 18px;
    }

    .light-status {
      text-align: center; font-size: 28px; font-weight: 700;
      margin: 12px 0 20px; transition: color 0.3s;
    }
    .light-on { color: #90d769; }
    .light-off { color: #3f3f46; }

    .auto-status {
      text-align: center; font-size: 13px; font-weight: 600;
      margin-bottom: 18px; transition: color 0.3s;
    }

    .btn-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

    button {
      padding: 14px; font-size: 13px; font-weight: 600;
      border: 1px solid rgba(255,255,255,0.08); border-radius: 12px;
      cursor: pointer; transition: all 0.2s;
      font-family: 'Inter', sans-serif; letter-spacing: 0.3px;
    }
    button:active { transform: scale(0.97); }

    .btn-primary {
      background: rgba(144,215,105,0.12); color: #90d769;
      border-color: rgba(144,215,105,0.2);
    }
    .btn-primary:hover { background: rgba(144,215,105,0.2); }

    .btn-secondary {
      background: rgba(255,255,255,0.04); color: #a1a1aa;
      border-color: rgba(255,255,255,0.08);
    }
    .btn-secondary:hover { background: rgba(255,255,255,0.08); }

    .telemetry-grid {
      display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px;
    }
    .tel-card {
      background: rgba(255,255,255,0.02);
      border: 1px solid rgba(255,255,255,0.04);
      border-radius: 12px; padding: 16px; text-align: center;
    }
    .tel-label {
      font-size: 9px; font-weight: 700; text-transform: uppercase;
      letter-spacing: 1.5px; color: #52525b; margin-bottom: 6px;
    }
    .tel-value { font-size: 22px; font-weight: 700; color: #fff; }
    .tel-unit { font-size: 11px; color: #71717a; }

    .status-bar {
      display: flex; align-items: center; gap: 6px;
      font-size: 10px; color: #52525b; margin-top: 20px;
      justify-content: center;
    }
    .pulse-dot {
      width: 6px; height: 6px; border-radius: 50%;
      background: #90d769; animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.3; }
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <div class="logo-ring"><div class="logo-dot"></div></div>
      <h1>Door Sentinel</h1>
      <div class="sub">ESP32 Hardware Controller</div>
    </div>

    <div class="card">
      <div class="card-title">Lighting Control</div>
      <div class="light-status" id="lightStatus">Loading...</div>
      <div class="btn-grid">
        <button class="btn-primary" onclick="toggleLight()">Toggle Light</button>
        <button class="btn-secondary" onclick="toggleAutoLight()">Toggle Auto</button>
      </div>
      <div class="auto-status" id="autoLightStatus" style="margin-top: 16px;">Auto: ...</div>
    </div>

    <div class="card">
      <div class="card-title">Telemetry</div>
      <div class="telemetry-grid">
        <div class="tel-card">
          <div class="tel-label">Temp</div>
          <div class="tel-value" id="tempValue">--</div>
          <div class="tel-unit">&deg;C</div>
        </div>
        <div class="tel-card">
          <div class="tel-label">Humidity</div>
          <div class="tel-value" id="humValue">--</div>
          <div class="tel-unit">%</div>
        </div>
        <div class="tel-card">
          <div class="tel-label">Light</div>
          <div class="tel-value" id="lightValue">--</div>
          <div class="tel-unit">lux</div>
        </div>
      </div>
    </div>

    <div class="status-bar">
      <div class="pulse-dot"></div>
      Live &mdash; updates every 2s
    </div>
  </div>

  <script>
    function toggleLight() {
      fetch('/toggle', { method: 'POST' })
        .then(r => r.json())
        .then(() => setTimeout(refreshStatus, 300))
        .catch(() => alert('Failed'));
    }
    function toggleAutoLight() {
      fetch('/toggleAuto', { method: 'POST' })
        .then(r => r.json())
        .then(() => setTimeout(refreshStatus, 300))
        .catch(() => alert('Failed'));
    }
    function refreshStatus() {
      fetch('/status').then(r => r.json()).then(data => {
        const s = document.getElementById('lightStatus');
        s.className = 'light-status ' + (data.light ? 'light-on' : 'light-off');
        s.textContent = data.light ? 'LIGHT ON' : 'LIGHT OFF';

        const a = document.getElementById('autoLightStatus');
        a.textContent = data.auto_light ? 'Auto-Trigger: ON' : 'Auto-Trigger: OFF';
        a.style.color = data.auto_light ? '#90d769' : '#ef4444';

        document.getElementById('tempValue').textContent = data.temp.toFixed(1);
        document.getElementById('humValue').textContent = data.humidity.toFixed(1);
        document.getElementById('lightValue').textContent = data.light_level;
      });
    }
    refreshStatus();
    setInterval(refreshStatus, 2000);
  </script>
</body>
</html>
)====="; // The delimiter must match exactly here to close the string


  server.send(200, "text/html; charset=utf-8", html);
}

void handleToggle() {
  // Flip the light switch virtually and physically
  if (!manualLightStatus) {
    triggerServo(ANGLE_ON);
    manualLightStatus = true;
  } else {
    triggerServo(ANGLE_OFF);
    manualLightStatus = false;
  }
  server.send(200, "application/json", "{\"status\":\"OK\",\"light\":" + String(manualLightStatus ? "true" : "false") + "}");
}

void handleToggleAuto() {
  // Flip the setting that allows the servo to trigger on intrusion
  autoLightEnabled = !autoLightEnabled;
  server.send(200, "application/json", "{\"status\":\"OK\",\"auto_light\":" + String(autoLightEnabled ? "true" : "false") + "}");
}

void handleStatus() {
  // Package the sensor values into a JSON string for the web dashboard
  String json = "{";
  json += "\"light\":" + String(manualLightStatus ? "true" : "false") + ",";
  json += "\"auto_light\":" + String(autoLightEnabled ? "true" : "false") + ",";
  json += "\"temp\":" + String(cachedTemp, 1) + ",";
  json += "\"humidity\":" + String(cachedHum, 1) + ",";
  json += "\"light_level\":" + String(cachedLight);
  json += "}";
  server.send(200, "application/json", json);
}

// ============================================================
// MAIN SETUP
// ============================================================

void setup() {
  Serial.begin(115200);
  
  // Set up IR PIN with internal Pullup to prevent floating triggers
  pinMode(IR_PIN, INPUT_PULLUP);
  pinMode(LDR_ANALOG_PIN, INPUT);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  
  // Attach the Hardware Interrupt! FALLING means it triggers the instant it goes from HIGH to LOW
  attachInterrupt(digitalPinToInterrupt(IR_PIN), irSensorISR, FALLING);

  // Configure and center servo
  lightServo.setPeriodHertz(50);
  lightServo.attach(SERVO_PIN, 500, 2400);
  lightServo.write(ANGLE_STANDBY);

  // Boot up temperature sensor
  dht.begin();

  // Connect to the hotspot network
  Serial.print("\n[WIFI] Connecting to ");
  Serial.println(SSID);
  WiFi.begin(SSID, PASS);
  while (WiFi.status() != WL_CONNECTED) { 
    delay(500); 
    Serial.print("."); 
  }
  Serial.println("\n[WIFI] Connected!");

  // Start MDNS so you don't have to type the IP address
  if (MDNS.begin(MDNS_NAME)) {
    Serial.printf("[mDNS] Access dashboard via: http://%s.local\n", MDNS_NAME);
  }

  // Build the URLs for the phone app
  // Phone is the hotspot, so its IP is the gateway of this network
  phoneIP = WiFi.gatewayIP();
  phoneTriggerUrl = "http://" + phoneIP.toString() + ":8080/trigger";
  phoneStopUrl    = "http://" + phoneIP.toString() + ":8080/stop";
  Serial.printf("[HTTP] Phone (gateway) IP: %s\n", phoneIP.toString().c_str());

  // Start listening for UDP traffic
  udp.begin(UDP_PORT);

  // Link up the web routes
  server.on("/", handleRoot);
  server.on("/toggle", HTTP_POST, handleToggle);
  server.on("/toggleAuto", HTTP_POST, handleToggleAuto); // NEW route
  server.on("/status", handleStatus);
  server.begin();
  
  // Create worker with HIGH Priority (5). The default loop runs at priority 1.
  // This means the network worker can shove the web server aside to send the alarm.
  xTaskCreatePinnedToCore(
    networkWorkerTask,   
    "NetWorker",         
    4096,                
    NULL,                
    5,                   // INCREASED PRIORITY: Absolute max priority for HTTP dispatches
    NULL,                
    0                    
  );

  Serial.println("[SYSTEM] Sentinel Armed and Ready.");
}

// ============================================================
// MAIN LOOP
// ============================================================

void loop() {
  unsigned long now = millis();

  // ---------------------------------------------------------
  // 1. ABSOLUTE HIGHEST PRIORITY: Check the Hardware Interrupt Flag
  // ---------------------------------------------------------
  if (intruderDetected) {
    intruderDetected = false; // Immediately clear the flag so it doesn't loop
    
    if (confirmPerson()) {
      int lightVal = analogRead(LDR_ANALOG_PIN);
      
      // If dark, tell phone to use flashlight, but ONLY flick servo if autoLightEnabled is true
      if (lightVal > DARK_THRESHOLD) {
        if (autoLightEnabled) {
          Serial.println("[AUTO] Intruder + Dark: Light ON");
          triggerServo(ANGLE_ON);           
          systemTurnedLightOn = true;       
          manualLightStatus = true;         
        } else {
          Serial.println("[AUTO] Intruder + Dark: Servo Skipped (Auto-Light OFF)");
          systemTurnedLightOn = false; 
        }
        dispatchBackgroundRequest(phoneTriggerUrl + "?dark=true", "START");
      } else {
        // If bright, just trigger the camera normally
        Serial.println("[AUTO] Intruder + Bright: Servo Skipped");
        systemTurnedLightOn = false;      
        dispatchBackgroundRequest(phoneTriggerUrl + "?dark=false", "START");
      }

      // Mark the time so the capture window begins
      lastTriggerTime = now;
      currentState = CAPTURING;
    }
  }

  // ---------------------------------------------------------
  // 2. CAPTURE WINDOW RESET LOGIC
  // ---------------------------------------------------------
  // Once the 5 seconds are up, clean everything up and re-arm
  if (currentState == CAPTURING && (now - lastTriggerTime >= CAPTURE_WINDOW)) {
    if (systemTurnedLightOn) {
      Serial.println("[AUTO] Timer End: Light OFF");
      triggerServo(ANGLE_OFF);
      manualLightStatus = false; 
    }
    
    // Stop recording and reset the state machine
    dispatchBackgroundRequest(phoneStopUrl, "STOP");
    Serial.println("[SYSTEM] Re-armed immediately.");
    currentState = IDLE; 
  }

  // ---------------------------------------------------------
  // 3. SECONDARY TASKS (Web Server & Telemetry)
  // ---------------------------------------------------------
  // Handle web clients AFTER checking the alarm logic so we don't slow down the trigger
  server.handleClient(); 
  
  // Tick the clock to retract the servo arm safely
  updateServo(); 

  // Collect background telemetry and blast it out over UDP
  if (now - lastTelemetryTime >= TELEMETRY_INTERVAL) {
    cachedTemp = dht.readTemperature();
    cachedHum = dht.readHumidity();
    cachedLight = analogRead(LDR_ANALOG_PIN);

    String payload = "T:" + String(cachedTemp, 1) + "|H:" + String(cachedHum, 1) + "|L:" + String(cachedLight);
    udp.beginPacket(phoneIP, UDP_PORT);
    udp.print(payload);
    udp.endPacket();

    lastTelemetryTime = now;
  }
}