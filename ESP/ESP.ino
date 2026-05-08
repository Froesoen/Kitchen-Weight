// ============================================================
//  Kitchen Weight — ESP32 Firmware
//  Waage mit 3× HX711, SSD1306, Bluetooth, FFT
// ============================================================
//
//  Kanäle:
//    scaleRear   – 2 Zellen hinten  (Vollbrücke)
//    scaleMid    – 2 Zellen mitte   (Vollbrücke)
//    scaleFront  – 1 Zelle + Dummy  (Vollbrücke)
//
//  Feste Parameter (nicht konfigurierbar):
//    SAMPLE_RATE_HZ = 20 Hz  → Nyquist 10 Hz, KitchenAid-Stufen auflösbar
//    FFT_SIZE       = 128    → 0.156 Hz/Bin, 6.4 s Frame
//    FFT_BARS       = 32     → 4 px/Balken auf 128px OLED
//    FFT-Quelle     = scaleFront (Frontkanal)
//
//  Konfigurierbar (App / NVS):
//    publishRateHz         1–20 Hz   BT-Senderate
//    avgSamples            1–4       HX711-Mittelung
//    offlineBufferSeconds  10–180 s  Puffergröße
//    displayHz             1–10 Hz   OLED-Refresh
//
//  Kalibrierung:
//    Drei individuelle Faktoren (factorRear / factorMid / factorFront).
//    Jeder Kanal wird separat kalibriert: Tara → Gewicht auflegen → Faktor berechnen.
//    NVS-Keys: kalfaktorR / kalfaktorM / kalfaktorF
//
//  Benötigte Libraries:
//    HX711 (bogde), U8g2, ArduinoJson, arduinoFFT (kosme1 v2.x)
// ============================================================

// ============================================================
//  BLOCK 1 — Importe und Definitionen
// ============================================================

#include <Arduino.h>
#include <HX711.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <BluetoothSerial.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <arduinoFFT.h>

// ── Hardware-Pins ─────────────────────────────────────────────────────────────
//  HX711 #1 – hinten
#define HX711_REAR_DOUT   25
#define HX711_REAR_SCK    26
//  HX711 #2 – mitte
#define HX711_MID_DOUT    27
#define HX711_MID_SCK     14
//  HX711 #3 – vorne
#define HX711_FRONT_DOUT  18
#define HX711_FRONT_SCK   19
//  Sonstiges
#define BUTTON_PIN        32
// Alle drei HX711: Planmäßig RATE-Pin auf HIGH (3.3V) → 80 SPS Modus
// Bisher nicht umgesetzt

// ── NVS-Schlüssel ─────────────────────────────────────────────────────────────
#define BT_DEVICE_NAME  "Waage_ESP32"
#define NVS_NAMESPACE   "waage"
#define NVS_KEY_INIT    "cfginit"
#define NVS_KEY_FACTOR_R "kalfaktorR"
#define NVS_KEY_FACTOR_M "kalfaktorM"
#define NVS_KEY_FACTOR_F "kalfaktorF"
#define NVS_KEY_PRATE   "prate"
#define NVS_KEY_AVG     "avg"
#define NVS_KEY_BUFSEC  "bufsec"
#define NVS_KEY_DISPHZ  "disphz"
#define NVS_KEY_DISPMOD "dispmode"

// ── Feste Sampling-Konstanten ─────────────────────────────────────────────────
constexpr uint8_t  SAMPLE_RATE_HZ   = 20;
constexpr uint32_t SAMPLE_PERIOD_MS = 1000UL / SAMPLE_RATE_HZ;  // 50 ms

// ── Defaults ─────────────────────────────────────────────────────────────────
constexpr uint16_t DEFAULT_PUBLISH_RATE_HZ        = 2;
constexpr uint8_t  DEFAULT_AVG_SAMPLES            = 2;
constexpr uint16_t DEFAULT_OFFLINE_BUFFER_SECONDS = 60;
constexpr uint8_t  DEFAULT_DISPLAY_HZ             = 2;

// ── Puffer-Limits ─────────────────────────────────────────────────────────────
// Max. offlineBufferSeconds = 90 s → 20 × 90 = 1800 Samples
// (OfflineSample hat 24 Byte durch 3 Kanäle, RAM-Budget bleibt gleich wie vorher)
constexpr uint16_t MAX_OFFLINE_BUFFER_SECONDS = 90;
constexpr uint16_t MAX_OFFLINE_BUFFER         = 1800;

// ── Display-Layout ────────────────────────────────────────────────────────────
constexpr uint8_t DISPLAY_WIDTH    = 128;
constexpr uint8_t DISPLAY_HEIGHT   = 64;
constexpr uint8_t VALUE_HEIGHT     = 20;
constexpr uint8_t PLOT_HEIGHT      = DISPLAY_HEIGHT - VALUE_HEIGHT;
constexpr uint8_t PLOT_Y_TOP       = VALUE_HEIGHT;
constexpr uint8_t PLOT_Y_BOTTOM    = DISPLAY_HEIGHT - 1;
constexpr uint8_t DISP_HIST        = 128;

// ── FFT – fest, nicht konfigurierbar ─────────────────────────────────────────
constexpr uint16_t FFT_SIZE       = 128;   // Samples/Frame → 6.4 s bei 20 Hz
constexpr float    FFT_BIN_RES    = (float)SAMPLE_RATE_HZ / (float)FFT_SIZE;
                                           // = 0.156 Hz/Bin
constexpr uint8_t  FFT_BAR_PX    = 4;
constexpr uint8_t  FFT_BARS      = DISPLAY_WIDTH / FFT_BAR_PX;  // 32
constexpr uint8_t  FFT_LABEL_H   = 10;
constexpr uint8_t  FFT_AXIS_H    = 8;
constexpr uint8_t  FFT_MAX_BAR_H = DISPLAY_HEIGHT - FFT_LABEL_H - FFT_AXIS_H;

// KitchenAid Stufen-Frequenzen (planetare Umlauffrequenz des Frontkanals)
constexpr float   KA_SPEEDS[]    = { 1.00f, 1.58f, 2.25f, 3.00f, 3.75f, 4.67f };
constexpr uint8_t KA_SPEED_COUNT = 6;

// ── Structs ───────────────────────────────────────────────────────────────────
struct DeviceConfig {
    uint16_t publishRateHz;
    uint8_t  avgSamples;
    uint16_t offlineBufferSeconds;
    uint8_t  displayHz;
};

struct SampleSnapshot {
    float    weightTotal;   // Summe aller drei Kanäle → geht in Puffer/Display/BT
    float    weightRear;    // Kanal hinten
    float    weightMid;     // Kanal mitte
    float    weightFront;   // Kanal vorne → FFT-Quelle
    uint32_t sampleMs;
    bool     timeSynced;
};

enum class ScaleCommandType : uint8_t {
    None, Tare, Calibrate, ApplyConfig,
    TareChannel,        // Tara für einzelnen Kanal
    CalibrateChannel    // Kalibrierung für einzelnen Kanal
};

struct ScaleCommand {
    ScaleCommandType type;
    float            knownWeightG;
    char             channel;       // 'R', 'M', 'F' — für Kanal-Befehle
    DeviceConfig     newConfig;
};

struct ScaleResult {
    bool  ok;
    char  type[16];
    char  channel;      // 'R', 'M', 'F', oder '\0' für alle
    float value;
    char  msg[48];
};

struct OfflineSample {
    int64_t ts;
    float   w;      // Gesamtgewicht
    float   wR;     // Kanal hinten
    float   wM;     // Kanal mitte
    float   wF;     // Kanal vorne
    bool    synced;
};

// ── Hardware-Objekte ──────────────────────────────────────────────────────────
U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
HX711           scaleRear;
HX711           scaleMid;
HX711           scaleFront;
BluetoothSerial BT;
Preferences     prefs;

// ── Konfiguration & abgeleitete Werte ─────────────────────────────────────────
DeviceConfig      config;
float factorRear  = 1.0f;   // individueller Kalibrierfaktor Kanal hinten
float factorMid   = 1.0f;   // individueller Kalibrierfaktor Kanal mitte
float factorFront = 1.0f;   // individueller Kalibrierfaktor Kanal vorne
volatile uint32_t publishPeriodMs     = 500;
volatile uint32_t displayPeriodMs     = 500;
uint8_t           displayMode         = 1;     // 0=groß, 1=Verlauf, 2=FFT
uint16_t          offlineBufferCapacity = 1200;

// ── Queues ────────────────────────────────────────────────────────────────────
QueueHandle_t sampleQueue      = nullptr;
QueueHandle_t scaleCmdQueue    = nullptr;
QueueHandle_t scaleResultQueue = nullptr;

// ── Aktuelle Messwerte ────────────────────────────────────────────────────────
float    currentWeight       = 0.0f;    // Gesamtgewicht
float    currentWeightRear   = 0.0f;
float    currentWeightMid    = 0.0f;
float    currentWeightFront  = 0.0f;
int64_t  currentWeightTs     = 0;
bool     currentWeightSynced = false;

// ── BT & Zeit ────────────────────────────────────────────────────────────────
volatile bool    btConnected   = false;
volatile bool    btReadyToSend = false;
volatile int64_t timeOffset    = 0;

// ── Offline-Ringpuffer ────────────────────────────────────────────────────────
OfflineSample offlineBuffer[MAX_OFFLINE_BUFFER];
uint16_t      offlineWriteIdx = 0;
uint16_t      offlineSendIdx  = 0;

// ── Display-History ───────────────────────────────────────────────────────────
float   dispHist[DISP_HIST] = {};
uint8_t dispHistIdx  = 0;
uint8_t dispHistFill = 0;

// ── FFT ───────────────────────────────────────────────────────────────────────
float    fftReal[FFT_SIZE] = {};
float    fftImag[FFT_SIZE] = {};
uint16_t fftSampleCount    = 0;

// FFT-Ergebnisse (nur in btDisplayTask geschrieben und gelesen)
float   fftPeakHz          = 0.f;
float   fftPeakAmp         = 0.f;
float   fftPeakHold        = 1.f;
float   fftBarHeights[FFT_BARS] = {};  // normiert 0..1 für OLED
bool    fftResultReady      = false;

// ── Button ────────────────────────────────────────────────────────────────────
bool     lastBtnState = HIGH;
uint32_t btnPressedAt = 0;

bool displayTaskStarted = false;
bool measureTaskStarted = false;

// ============================================================
//  BLOCK 2 — Hilfsfunktionen
//    loadFactor / saveFactor
//    applyDerivedConfig / validateConfig / loadConfig / saveConfig
//    btSend / btSendJson / sendError / sendConfig
//    toPixel
//    handleCommand
// ============================================================

// ── NVS: Kalibrierfaktoren ────────────────────────────────────────────────────
void loadFactors() {
    prefs.begin(NVS_NAMESPACE, true);
    // Migration: falls noch kein Einzelfaktor vorhanden, Legacy-Key als Startwert
    float legacy = prefs.getFloat("kalfaktor", 1.0f);
    factorRear   = prefs.getFloat(NVS_KEY_FACTOR_R, legacy);
    factorMid    = prefs.getFloat(NVS_KEY_FACTOR_M, legacy);
    factorFront  = prefs.getFloat(NVS_KEY_FACTOR_F, legacy);
    prefs.end();
}

void saveFactor(char ch, float f) {
    prefs.begin(NVS_NAMESPACE, false);
    if      (ch == 'R') prefs.putFloat(NVS_KEY_FACTOR_R, f);
    else if (ch == 'M') prefs.putFloat(NVS_KEY_FACTOR_M, f);
    else if (ch == 'F') prefs.putFloat(NVS_KEY_FACTOR_F, f);
    prefs.end();
}

// ── Config ────────────────────────────────────────────────────────────────────
void applyDerivedConfig() {
    if (config.publishRateHz < 1)              config.publishRateHz = 1;
    if (config.publishRateHz > SAMPLE_RATE_HZ) config.publishRateHz = SAMPLE_RATE_HZ;
    publishPeriodMs = 1000UL / (uint32_t)config.publishRateHz;

    uint8_t dHz = config.displayHz;
    if (dHz < 1)  dHz = 1;
    if (dHz > 10) dHz = 10;
    config.displayHz = dHz;
    displayPeriodMs  = 1000UL / dHz;

    uint32_t cap = (uint32_t)SAMPLE_RATE_HZ * (uint32_t)config.offlineBufferSeconds;
    if (cap < SAMPLE_RATE_HZ)     cap = SAMPLE_RATE_HZ;   // mind. 1 Sekunde
    if (cap > MAX_OFFLINE_BUFFER) cap = MAX_OFFLINE_BUFFER;
    offlineBufferCapacity = (uint16_t)cap;
    if (offlineBufferCapacity == 0) offlineBufferCapacity = SAMPLE_RATE_HZ; // Notfall-Absicherung

    if (offlineWriteIdx >= offlineBufferCapacity ||
        offlineSendIdx  >= offlineBufferCapacity) {
        offlineWriteIdx = 0;
        offlineSendIdx  = 0;
    }

    fftSampleCount = 0;
    fftResultReady = false;
    fftPeakHold    = 1.f;
}

bool validateConfig(const DeviceConfig& c, String& err) {
    if (c.publishRateHz < 1 || c.publishRateHz > SAMPLE_RATE_HZ)
        { err = "publishRateHz out of range (1-20)";      return false; }
    if (c.avgSamples < 1 || c.avgSamples > 4)
        { err = "avgSamples out of range (1-4)";          return false; }
    if (c.offlineBufferSeconds < 10 ||
        c.offlineBufferSeconds > MAX_OFFLINE_BUFFER_SECONDS)
        { err = "offlineBufferSeconds out of range (10-180)"; return false; }
    if (c.displayHz < 1 || c.displayHz > 10)
        { err = "displayHz out of range (1-10)";          return false; }
    return true;
}

void loadConfig() {
    prefs.begin(NVS_NAMESPACE, false);
    if (!prefs.isKey(NVS_KEY_INIT)) {
        prefs.putBool  (NVS_KEY_INIT,    true);
        prefs.putUShort(NVS_KEY_PRATE,   DEFAULT_PUBLISH_RATE_HZ);
        prefs.putUChar (NVS_KEY_AVG,     DEFAULT_AVG_SAMPLES);
        prefs.putUShort(NVS_KEY_BUFSEC,  DEFAULT_OFFLINE_BUFFER_SECONDS);
        prefs.putUChar (NVS_KEY_DISPHZ,  DEFAULT_DISPLAY_HZ);
        prefs.putUChar (NVS_KEY_DISPMOD, 1);
    }
    config.publishRateHz        = prefs.getUShort(NVS_KEY_PRATE,  DEFAULT_PUBLISH_RATE_HZ);
    config.avgSamples           = prefs.getUChar (NVS_KEY_AVG,    DEFAULT_AVG_SAMPLES);
    config.offlineBufferSeconds = prefs.getUShort(NVS_KEY_BUFSEC, DEFAULT_OFFLINE_BUFFER_SECONDS);
    config.displayHz            = prefs.getUChar (NVS_KEY_DISPHZ, DEFAULT_DISPLAY_HZ);
    displayMode                 = prefs.getUChar (NVS_KEY_DISPMOD, 1);
    prefs.end();
    applyDerivedConfig();
}

bool saveConfig(const DeviceConfig& c) {
    prefs.begin(NVS_NAMESPACE, false);
    bool ok = true;
    ok &= prefs.putUShort(NVS_KEY_PRATE,  c.publishRateHz)        > 0;
    ok &= prefs.putUChar (NVS_KEY_AVG,    c.avgSamples)           > 0;
    ok &= prefs.putUShort(NVS_KEY_BUFSEC, c.offlineBufferSeconds) > 0;
    ok &= prefs.putUChar (NVS_KEY_DISPHZ, c.displayHz)            > 0;
    prefs.end();
    return ok;
}

// ── BT-Helfer ─────────────────────────────────────────────────────────────────
void btSend(const String& s) {
    if (!btConnected) return;
    size_t written = BT.print(s);
    Serial.printf("[btSend] len=%d written=%d btConn=%d\n",
                  s.length(), (int)written, (int)btConnected);
}

void btSendJson(JsonDocument& doc) {
    String s;
    serializeJson(doc, s);
    s += "\n";
    btSend(s);
}

void sendError(const char* msg) {
    StaticJsonDocument<128> d;
    d["type"] = "error";
    d["msg"]  = msg;
    btSendJson(d);
}

void sendConfig(const char* typeName = "config") {
    StaticJsonDocument<320> d;
    d["type"]                  = typeName;
    d["sampleRateHz"]          = SAMPLE_RATE_HZ;
    d["publishRateHz"]         = config.publishRateHz;
    d["avgSamples"]            = config.avgSamples;
    d["offlineBufferSeconds"]  = config.offlineBufferSeconds;
    d["offlineBufferCapacity"] = offlineBufferCapacity;
    d["displayHz"]             = config.displayHz;
    d["factorRear"]            = factorRear;
    d["factorMid"]             = factorMid;
    d["factorFront"]           = factorFront;
    btSendJson(d);
}

// ── Display-Hilfsfunktion ─────────────────────────────────────────────────────
uint8_t toPixel(float v, float lo, float hi) {
    if (hi <= lo) return PLOT_Y_BOTTOM;
    float r = (v - lo) / (hi - lo);
    return (uint8_t)constrain(
        (int)(PLOT_Y_BOTTOM - r * PLOT_HEIGHT),
        (int)PLOT_Y_TOP, (int)PLOT_Y_BOTTOM);
}

// ── BT-Kommandoverarbeitung ───────────────────────────────────────────────────
void handleCommand(const String& json) {
    StaticJsonDocument<256> doc;
    if (deserializeJson(doc, json) != DeserializationError::Ok) return;
    const char* type = doc["type"] | "";

    if (strcmp(type, "tare") == 0) {
        ScaleCommand cmd{};
        cmd.type = ScaleCommandType::Tare;
        xQueueSend(scaleCmdQueue, &cmd, 0);
        return;
    }
    
    if (strcmp(type, "tare_channel") == 0) {
        const char* ch = doc["ch"] | "R";
        ScaleCommand cmd{};
        cmd.type    = ScaleCommandType::TareChannel;
        cmd.channel = ch[0];
        xQueueSend(scaleCmdQueue, &cmd, 0);
        return;
    }

    if (strcmp(type, "calibrate_channel") == 0) {
        const char* ch = doc["ch"] | "R";
        float known    = doc["weight"] | 0.0f;
        if (known <= 0.0f) { sendError("Gewicht ungueltig"); return; }
        ScaleCommand cmd{};
        cmd.type         = ScaleCommandType::CalibrateChannel;
        cmd.channel      = ch[0];
        cmd.knownWeightG = known;
        xQueueSend(scaleCmdQueue, &cmd, 0);
        return;
    }
    
    if (strcmp(type, "get_factor") == 0 || strcmp(type, "getfactor") == 0) {
        StaticJsonDocument<128> r;
        r["type"]        = "factor";
        r["ch"]          = "ALL";
        r["factorRear"]  = factorRear;
        r["factorMid"]   = factorMid;
        r["factorFront"] = factorFront;
        btSendJson(r);
        return;
    }

    if (strcmp(type, "set_factor") == 0) {
        const char* ch = doc["ch"] | "";
        float f = doc["factor"] | -1.0f;
        if (strlen(ch) != 1 || f <= 0.0f) { sendError("Ungültiger Faktor"); return; }
        if      (ch[0] == 'R') { factorRear   = f; scaleRear.set_scale(f); }
        else if (ch[0] == 'M') { factorMid    = f; scaleMid.set_scale(f);  }
        else if (ch[0] == 'F') { factorFront  = f; scaleFront.set_scale(f);}
        else { sendError("Unbekannter Kanal"); return; }
        saveFactor(ch[0], f);
        StaticJsonDocument<128> r;
        r["type"]    = "factor";
        r["ch"]      = ch;
        r["value"]   = f;
        btSendJson(r);
        return;
    }

    if (strcmp(type, "getconfig") == 0 || strcmp(type, "get_config") == 0) {
        sendConfig("config");
        return;
    }

    if (strcmp(type, "setconfig") == 0) {
        DeviceConfig next         = config;
        next.publishRateHz        = doc["publishRateHz"]        | config.publishRateHz;
        next.avgSamples           = doc["avgSamples"]           | config.avgSamples;
        next.offlineBufferSeconds = doc["offlineBufferSeconds"] | config.offlineBufferSeconds;
        next.displayHz            = doc["displayHz"]            | config.displayHz;
        String err;
        if (!validateConfig(next, err)) { sendError(err.c_str()); return; }
        ScaleCommand cmd{};
        cmd.type      = ScaleCommandType::ApplyConfig;
        cmd.newConfig = next;
        xQueueSend(scaleCmdQueue, &cmd, 0);
        return;
    }

    if (strcmp(type, "resetconfig") == 0) {
        ScaleCommand cmd{};
        cmd.type      = ScaleCommandType::ApplyConfig;
        cmd.newConfig = { DEFAULT_PUBLISH_RATE_HZ, DEFAULT_AVG_SAMPLES,
                          DEFAULT_OFFLINE_BUFFER_SECONDS, DEFAULT_DISPLAY_HZ };
        xQueueSend(scaleCmdQueue, &cmd, 0);
        return;
    }

    if (strcmp(type, "sync") == 0) {
        int64_t unixTs = doc["unix"] | 0LL;
        timeOffset = unixTs - (int64_t)millis();

        // Puffer-Samples mit millis()-Timestamps nachträglich auf Unix-Zeit stempeln
        for (uint16_t i = 0; i < offlineBufferCapacity; i++) {
            if (offlineBuffer[i].ts > 0 && offlineBuffer[i].ts < 946684800000LL) {
                offlineBuffer[i].ts += timeOffset;
            }
        }

        // Puffer auf max. 3 Batches à 59 Samples begrenzen → nach ~2s live
        const uint16_t MAX_CATCHUP_SAMPLES = 177;  // 3 × 59
        uint16_t pending = (offlineWriteIdx + offlineBufferCapacity - offlineSendIdx) % offlineBufferCapacity;
        if (pending > MAX_CATCHUP_SAMPLES) {
            offlineSendIdx = (offlineWriteIdx + offlineBufferCapacity - MAX_CATCHUP_SAMPLES) % offlineBufferCapacity;
        }

        // sync_done bestätigen und Sendefreigabe erteilen
        StaticJsonDocument<64> r;
        r["type"] = "sync_done";
        btSendJson(r);
        btReadyToSend = true;

        return;
    }

    sendError("unknown command");
}

// ============================================================
//  BLOCK 3 — measureTask (Core 1)
//
//  Liest alle drei HX711-Kanäle mit 20 Hz, bildet das Gesamtgewicht
//  und schreibt einen SampleSnapshot in die sampleQueue.
//  Verarbeitet außerdem Tare- und Kalibrierkommandos von btDisplayTask.
// ============================================================

void measureTask(void* param) {
    measureTaskStarted = true;

    uint32_t lastSampleAt = millis();
    ScaleCommand cmd;

    while (true) {

        // ── Kommandos verarbeiten ─────────────────────────────────────────────
        while (xQueueReceive(scaleCmdQueue, &cmd, 0) == pdTRUE) {

            // ── Tare: alle drei Kanäle gleichzeitig ──────────────────────────
            if (cmd.type == ScaleCommandType::Tare) {
                scaleRear.tare();
                scaleMid.tare();
                scaleFront.tare();

                ScaleResult r{};
                r.ok    = true;
                strlcpy(r.type, "tare_done", sizeof(r.type));
                // Offset des Frontkanals als Referenzwert zurückmelden
                r.value = (float)scaleFront.get_offset();
                xQueueSend(scaleResultQueue, &r, 0);

            // ── Kalibrierung: gemeinsamer Faktor aus Summe aller Rohwerte ────
            } else if (cmd.type == ScaleCommandType::Calibrate) {
                scaleRear.set_scale(1.0f);
                scaleMid.set_scale(1.0f);
                scaleFront.set_scale(1.0f);

                float rawRear  = scaleRear.get_value(config.avgSamples * 4);
                float rawMid   = scaleMid.get_value(config.avgSamples * 4);
                float rawFront = scaleFront.get_value(config.avgSamples * 4);
                float rawSum   = rawRear + rawMid + rawFront;

                ScaleResult r{};
                if (rawSum == 0.0f || cmd.knownWeightG <= 0.0f) {
                    scaleRear.set_scale(factorRear);
                    scaleMid.set_scale(factorMid);
                    scaleFront.set_scale(factorFront);
                    r.ok = false;
                    strlcpy(r.type, "error",     sizeof(r.type));
                    strlcpy(r.msg,  "Rohwert 0", sizeof(r.msg));
                } else {
                    float newFactor = rawSum / cmd.knownWeightG;
                    factorRear  = newFactor;
                    factorMid   = newFactor;
                    factorFront = newFactor;
                    scaleRear.set_scale(factorRear);
                    scaleMid.set_scale(factorMid);
                    scaleFront.set_scale(factorFront);
                    saveFactor('R', factorRear);
                    saveFactor('M', factorMid);
                    saveFactor('F', factorFront);
                    r.ok    = true;
                    strlcpy(r.type, "factor", sizeof(r.type));
                    r.value = newFactor;
                }
                xQueueSend(scaleResultQueue, &r, 0);

            // ── Konfiguration übernehmen ──────────────────────────────────────
            } else if (cmd.type == ScaleCommandType::ApplyConfig) {
                config = cmd.newConfig;
                applyDerivedConfig();
                saveConfig(config);
                ScaleResult r{};
                r.ok = true;
                strlcpy(r.type, "config_saved", sizeof(r.type));
                xQueueSend(scaleResultQueue, &r, 0);

            // ── TareChannel: einzelnen Kanal tarieren ─────────────────────────
            } else if (cmd.type == ScaleCommandType::TareChannel) {
                if      (cmd.channel == 'R') scaleRear.tare();
                else if (cmd.channel == 'M') scaleMid.tare();
                else if (cmd.channel == 'F') scaleFront.tare();

                ScaleResult r{};
                r.ok      = true;
                r.channel = cmd.channel;
                strlcpy(r.type, "tare_done", sizeof(r.type));
                r.value = (cmd.channel == 'R') ? (float)scaleRear.get_offset()
                        : (cmd.channel == 'M') ? (float)scaleMid.get_offset()
                                               : (float)scaleFront.get_offset();
                xQueueSend(scaleResultQueue, &r, 0);

            // ── CalibrateChannel: einzelnen Kanal kalibrieren ─────────────────
            } else if (cmd.type == ScaleCommandType::CalibrateChannel) {
                HX711* scale = (cmd.channel == 'R') ? &scaleRear
                             : (cmd.channel == 'M') ? &scaleMid
                                                    : &scaleFront;
                scale->set_scale(1.0f);
                float raw = scale->get_value(config.avgSamples * 4);

                ScaleResult r{};
                r.channel = cmd.channel;
                if (raw == 0.0f || cmd.knownWeightG <= 0.0f) {
                    float oldFactor = (cmd.channel == 'R') ? factorRear
                                    : (cmd.channel == 'M') ? factorMid
                                                           : factorFront;
                    scale->set_scale(oldFactor);
                    r.ok = false;
                    strlcpy(r.type, "error",     sizeof(r.type));
                    strlcpy(r.msg,  "Rohwert 0", sizeof(r.msg));
                } else {
                    float newFactor = raw / cmd.knownWeightG;
                    scale->set_scale(newFactor);
                    if      (cmd.channel == 'R') factorRear  = newFactor;
                    else if (cmd.channel == 'M') factorMid   = newFactor;
                    else                         factorFront = newFactor;
                    saveFactor(cmd.channel, newFactor);
                    r.ok    = true;
                    strlcpy(r.type, "factor", sizeof(r.type));
                    r.value = newFactor;
                }
                xQueueSend(scaleResultQueue, &r, 0);
            }   // ← schließt das if/else if – war vorher fehlend
        }       // ← schließt das while(xQueueReceive)

        // ── 20 Hz Messung ─────────────────────────────────────────────────────
        uint32_t now = millis();
        if ((uint32_t)(now - lastSampleAt) >= SAMPLE_PERIOD_MS) {
            lastSampleAt += SAMPLE_PERIOD_MS;

            float wRear  = scaleRear.get_units(config.avgSamples);
            float wMid   = scaleMid.get_units(config.avgSamples);
            float wFront = scaleFront.get_units(config.avgSamples);
            float wTotal = wRear + wMid + wFront;

            SampleSnapshot s{
                wTotal, wRear, wMid, wFront,
                millis(), timeOffset != 0
            };
            xQueueSend(sampleQueue, &s, 0);
        }

        taskYIELD();
    }
}

// ============================================================
//  BLOCK 4 — btDisplayTask (Core 0)
//
//  Verarbeitet Snapshots aus der Queue:
//    - Gesamtgewicht  → Display-History, Offline-Puffer, BT-Batch
//    - Frontkanal     → FFT-Eingangsdaten
//  Außerdem: BT-Empfang/Senden, Display-Update, Button-Debounce
// ============================================================

void btDisplayTask(void* param) {
    displayTaskStarted = true;

    String   inBuf       = "";
    uint32_t lastDisplay = 0;
    uint32_t lastPublish = 0;

    while (true) {
        uint32_t now = millis();

        // ── Button ────────────────────────────────────────────────────────────
        bool btn = digitalRead(BUTTON_PIN);
        if (lastBtnState == HIGH && btn == LOW) {
            btnPressedAt = now;
        }
        if (lastBtnState == LOW && btn == HIGH) {
            uint32_t held = now - btnPressedAt;
            if (held >= 1000) {
                // Langer Druck: Display-Modus weiterschalten
                displayMode = (displayMode + 1) % 3;
                prefs.begin(NVS_NAMESPACE, false);
                prefs.putUChar(NVS_KEY_DISPMOD, displayMode);
                prefs.end();
            } else if (held > 50) {
                // Kurzer Druck: Tara
                ScaleCommand cmd{};
                cmd.type = ScaleCommandType::Tare;
                xQueueSend(scaleCmdQueue, &cmd, 0);
            }
        }
        lastBtnState = btn;

        // ── Snapshots verarbeiten ─────────────────────────────────────────────
        SampleSnapshot snap;
        while (xQueueReceive(sampleQueue, &snap, 0) == pdTRUE) {

            // Laufzeitwerte aktualisieren (alle Kanäle + Gesamtgewicht)
            currentWeight        = snap.weightTotal;
            currentWeightRear    = snap.weightRear;
            currentWeightMid     = snap.weightMid;
            currentWeightFront   = snap.weightFront;
            currentWeightTs      = timeOffset + (int64_t)snap.sampleMs;
            currentWeightSynced  = snap.timeSynced;

            // [1] Display-History: Gesamtgewicht
            dispHist[dispHistIdx] = snap.weightTotal;
            dispHistIdx = (dispHistIdx + 1) % DISP_HIST;
            if (dispHistFill < DISP_HIST) dispHistFill++;

            // [2] Offline-Ringpuffer: Gesamtgewicht
            offlineBuffer[offlineWriteIdx] = {
                    currentWeightTs,
                    snap.weightTotal,
                    snap.weightRear,
                    snap.weightMid,
                    snap.weightFront,
                    snap.timeSynced
            };
            if (offlineBufferCapacity > 0) {
                offlineWriteIdx = (offlineWriteIdx + 1) % offlineBufferCapacity;
                if (offlineWriteIdx == offlineSendIdx)
                    offlineSendIdx = (offlineSendIdx + 1) % offlineBufferCapacity;
            }

            // [3] FFT-Eingabe: nur Frontkanal
            if (fftSampleCount < FFT_SIZE) {
                fftReal[fftSampleCount] = snap.weightFront;
                fftImag[fftSampleCount] = 0.0;
                fftSampleCount++;
            }
        }

        // ── FFT berechnen wenn Frame voll ─────────────────────────────────────
        // ── FFT berechnen wenn Frame voll ─────────────────────────────────────
        if (fftSampleCount >= FFT_SIZE) {
            fftSampleCount = 0;

            // Span-Prüfung: FFT nur auswerten wenn Signal ≥ 5 g Spanne
            double fftMin = fftReal[0], fftMax = fftReal[0];
            for (uint16_t i = 1; i < FFT_SIZE; i++) {
                if (fftReal[i] < fftMin) fftMin = fftReal[i];
                if (fftReal[i] > fftMax) fftMax = fftReal[i];
            }

            if ((fftMax - fftMin) < 5.0) {
                // Kein ausreichendes Signal → Maschine aus
                fftPeakHz   = 0.0f;
                fftPeakAmp  = 0.0f;
                fftPeakHold = 1.0f;
                fftResultReady = true;
                memset(fftReal, 0, sizeof(fftReal));
                memset(fftImag, 0, sizeof(fftImag));
                memset(fftBarHeights, 0, sizeof(fftBarHeights));

                if (btConnected) {
                    btSend("{\"type\":\"fft_result\",\"peakHz\":0.0,"
                           "\"peakAmp\":0.0,\"machineOff\":true}\n");
                }
                Serial.println("[FFT] Span < 5g → Maschine aus");

            } else {
                // Hamming-Fensterung + FFT
                ArduinoFFT<float> fft(fftReal, fftImag, FFT_SIZE, (double)SAMPLE_RATE_HZ);
                fft.windowing(FFTWindow::Hamming, FFTDirection::Forward);
                fft.compute(FFTDirection::Forward);
                fft.complexToMagnitude();

                // Maximale Amplitude (Bin 0 = DC, Bin 1 = 0.156 Hz verwerfen)
                float maxAmp = 0.001;
                for (uint16_t i = 2; i < FFT_SIZE / 2; i++)
                    if (fftReal[i] > maxAmp) maxAmp = fftReal[i];

                // Peak-Frequenz bestimmen
                float    peakAmp = 0.0;
                uint16_t peakBin = 2;
                for (uint16_t i = 2; i < FFT_SIZE / 2; i++) {
                    if (fftReal[i] > peakAmp) {
                        peakAmp = fftReal[i];
                        peakBin = i;
                    }
                }

                // Peak-Hold (langsames Abklingen)
                if (peakAmp > (double)fftPeakHold) fftPeakHold = (float)peakAmp;
                else                                fftPeakHold *= 0.98f;
                if (fftPeakHold < 1.f)              fftPeakHold = 1.f;

                fftPeakHz  = peakBin * FFT_BIN_RES;
                fftPeakAmp = (float)peakAmp;

                // Bins auf 32 Display-Balken verteilen
                float nyquist = SAMPLE_RATE_HZ / 2.0f;
                for (uint8_t bar = 0; bar < FFT_BARS; bar++) {
                    float    fLow    =  bar      * nyquist / FFT_BARS;
                    float    fHigh   = (bar + 1) * nyquist / FFT_BARS;
                    uint16_t binLow  = max(2, (int)(fLow  * FFT_SIZE / SAMPLE_RATE_HZ));
                    uint16_t binHigh = max(binLow + 1,
                                           (int)(fHigh * FFT_SIZE / SAMPLE_RATE_HZ));
                    if (binHigh > FFT_SIZE / 2) binHigh = FFT_SIZE / 2;

                    float barMax = 0.0;
                    for (uint16_t bin = binLow; bin < binHigh; bin++)
                        if (fftReal[bin] > barMax) barMax = fftReal[bin];

                    fftBarHeights[bar] = (float)(barMax / fftPeakHold);
                }
                fftResultReady = true;

                // FFT-Ergebnis per BT senden
                if (btConnected) {
                    String msg;
                    msg.reserve(300);
                    msg  = "{\"type\":\"fft_result\"";
                    msg += ",\"peakHz\":";  msg += String(fftPeakHz, 3);
                    msg += ",\"peakAmp\":"; msg += String(fftPeakAmp, 1);
                    msg += ",\"binRes\":";  msg += String(FFT_BIN_RES, 4);
                    msg += ",\"fs\":";      msg += SAMPLE_RATE_HZ;
                    msg += ",\"bins\":[0";
                    for (uint16_t i = 1; i < FFT_SIZE / 2; i++) {
                        uint8_t val = (uint8_t)constrain(
                                (int)(fftReal[i] / maxAmp * 255.0), 0, 255);
                        msg += ",";
                        msg += val;
                    }
                    msg += "]}\n";
                    btSend(msg);
                }

                Serial.printf("[FFT] Peak: %.3f Hz | Amp: %.1f | Hold: %.1f\n",
                              fftPeakHz, fftPeakAmp, fftPeakHold);
            }
        }

        // ── ScaleResults verarbeiten ──────────────────────────────────────────
        ScaleResult res;
        while (xQueueReceive(scaleResultQueue, &res, 0) == pdTRUE) {
            if (strcmp(res.type, "tare_done") == 0) {
                StaticJsonDocument<96> d;
                d["type"]   = "tare_done";
                d["ch"]     = String(res.channel);   // NEU
                d["offset"] = res.value;
                btSendJson(d);
            } else if (strcmp(res.type, "factor") == 0) {
                StaticJsonDocument<96> d;
                d["type"]  = "factor";
                d["ch"]    = String(res.channel);    // NEU
                d["value"] = res.value;
                btSendJson(d);
            } else if (strcmp(res.type, "config_saved") == 0) {
                sendConfig("config_saved");
            } else if (strcmp(res.type, "error") == 0) {
                sendError(res.msg);
            }
        }

        // ── BT: Messwert-Batch senden (Gesamtgewicht) ─────────────────────────
    if (btConnected && btReadyToSend && (uint32_t)(now - lastPublish) >= publishPeriodMs) {
            lastPublish = now;

            const uint16_t MAX_SAMPLES_PER_BATCH = 59;  // ~3720 Bytes, sicher < 4096

            uint16_t snapHead = offlineWriteIdx;
            if (snapHead != offlineSendIdx) {
                uint16_t total = (snapHead + offlineBufferCapacity - offlineSendIdx) % offlineBufferCapacity;
                uint16_t count = total < MAX_SAMPLES_PER_BATCH ? total : MAX_SAMPLES_PER_BATCH;

                String msg;
                msg.reserve(count * 65 + 48);
                msg = "{\"type\":\"measurement_batch\",\"samples\":[";
                uint16_t idx = offlineSendIdx;
                for (uint16_t i = 0; i < count; i++) {
                    if (i > 0) msg += ",";
                    const OfflineSample& s = offlineBuffer[idx];
                    msg += "{\"w\":";   msg += String(s.w,  2);
                    msg += ",\"wR\":";  msg += String(s.wR, 2);
                    msg += ",\"wM\":";  msg += String(s.wM, 2);
                    msg += ",\"wF\":";  msg += String(s.wF, 2);
                    msg += ",\"ts\":";  msg += String((long long)s.ts);
                    msg += "}";
                    if (offlineBufferCapacity > 0) idx = (idx + 1) % offlineBufferCapacity;
                }
                msg += "]}\n";

                btSend(msg);
                if (btConnected) {
                    offlineSendIdx = (offlineSendIdx + count) % offlineBufferCapacity;
                }
            }
        }

        // ── BT: Empfangen ─────────────────────────────────────────────────────
        if (btConnected) {
            while (BT.available()) {
                char c = BT.read();
                if (c == '\n') {
                    if (inBuf.length() > 0) {
                        handleCommand(inBuf);
                        inBuf = "";
                    }
                } else if (c != '\r') {
                    inBuf += c;
                    if (inBuf.length() > 512) inBuf = "";
                }
            }
        }

        // ── Display ───────────────────────────────────────────────────────────
        if ((uint32_t)(now - lastDisplay) >= displayPeriodMs) {
            lastDisplay = now;
            u8g2.clearBuffer();

            // Gesamtgewicht formatieren
            char wBuf[24];
            if (currentWeight >= 1000.0f || currentWeight <= -1000.0f)
                snprintf(wBuf, sizeof(wBuf), "%.3f kg", currentWeight / 1000.0f);
            else
                snprintf(wBuf, sizeof(wBuf), "%.1f g", currentWeight);

            // BT-Statusindikator (in allen Modi)
            auto drawBtIndicator = [&]() {
                if      (btConnected && timeOffset != 0) u8g2.drawDisc  (124, 4, 3);
                else if (btConnected)                    u8g2.drawCircle(124, 4, 3);
                else                                     u8g2.drawFrame (120, 1, 7, 7);
            };

            // ── Modus 0: Großes Gewicht ───────────────────────────────────────
            if (displayMode == 0) {
                u8g2.setFont(u8g2_font_10x20_tf);
                uint8_t tw = u8g2.getStrWidth(wBuf);
                u8g2.drawStr((DISPLAY_WIDTH - tw) / 2, 42, wBuf);
                drawBtIndicator();

            // ── Modus 1: Gewicht + Verlaufsgraph ─────────────────────────────
            } else if (displayMode == 1) {
                u8g2.setFont(u8g2_font_9x15B_tf);
                uint8_t tw = u8g2.getStrWidth(wBuf);
                u8g2.drawStr((DISPLAY_WIDTH - tw) / 2, VALUE_HEIGHT - 3, wBuf);
                drawBtIndicator();
                u8g2.drawHLine(0, VALUE_HEIGHT, DISPLAY_WIDTH);

                if (dispHistFill >= 2) {
                    float lo = 1e9f, hi = -1e9f;
                    for (uint8_t i = 0; i < dispHistFill; i++) {
                        uint8_t ii = (dispHistIdx + DISP_HIST - dispHistFill + i) % DISP_HIST;
                        lo = min(lo, dispHist[ii]);
                        hi = max(hi, dispHist[ii]);
                    }
                    float mg = max((hi - lo) * 0.1f, 1.0f);
                    lo -= mg; hi += mg;

                    uint8_t dc = (uint8_t)min((int)DISPLAY_WIDTH, (int)dispHistFill);
                    for (uint8_t x = 1; x < dc; x++) {
                        uint8_t i0 = (dispHistIdx + DISP_HIST - dc + x - 1) % DISP_HIST;
                        uint8_t i1 = (dispHistIdx + DISP_HIST - dc + x)     % DISP_HIST;
                        u8g2.drawLine(x - 1, toPixel(dispHist[i0], lo, hi),
                                      x,     toPixel(dispHist[i1], lo, hi));
                    }
                }

            // ── Modus 2: FFT-Spektrum (Frontkanal) ───────────────────────────
            } else {
                u8g2.setFont(u8g2_font_5x8_tf);

                if (fftResultReady) {
                    // Balken
                    for (uint8_t bar = 0; bar < FFT_BARS; bar++) {
                        uint8_t barH = (uint8_t)(fftBarHeights[bar] * FFT_MAX_BAR_H);
                        uint8_t x    = bar * FFT_BAR_PX;
                        uint8_t bw   = FFT_BAR_PX > 1 ? FFT_BAR_PX - 1 : 1;
                        u8g2.drawBox(x,
                                     DISPLAY_HEIGHT - FFT_AXIS_H - barH,
                                     bw, barH);
                    }

                    // KitchenAid-Stufen als Markierungslinien
                    for (uint8_t k = 0; k < KA_SPEED_COUNT; k++) {
                        float    hz  = KA_SPEEDS[k];
                        uint16_t bin = (uint16_t)(hz / FFT_BIN_RES);
                        uint8_t  x   = (uint8_t)((float)bin / (FFT_SIZE / 2) * DISPLAY_WIDTH);
                        if (x < DISPLAY_WIDTH)
                            u8g2.drawVLine(x, FFT_LABEL_H,
                                           DISPLAY_HEIGHT - FFT_LABEL_H - FFT_AXIS_H);
                    }

                    // Peak-Frequenz als Text
                    char fBuf[16];
                    snprintf(fBuf, sizeof(fBuf), "%.2fHz", fftPeakHz);
                    u8g2.drawStr(0, FFT_LABEL_H - 1, fBuf);

                    // Achsenbeschriftung: Nyquist
                    char nyBuf[8];
                    snprintf(nyBuf, sizeof(nyBuf), "%dHz", SAMPLE_RATE_HZ / 2);
                    uint8_t nyW = u8g2.getStrWidth(nyBuf);
                    u8g2.drawStr(DISPLAY_WIDTH - nyW, DISPLAY_HEIGHT - 1, nyBuf);
                    u8g2.drawStr(0, DISPLAY_HEIGHT - 1, "0");

                } else {
                    // Noch kein FFT-Ergebnis: Füllstand anzeigen
                    char pBuf[16];
                    snprintf(pBuf, sizeof(pBuf), "FFT %d/%d",
                             fftSampleCount, FFT_SIZE);
                    u8g2.drawStr(0, 36, pBuf);
                    u8g2.drawStr(0, 52, "(Front)");
                }
                drawBtIndicator();
            }

            u8g2.sendBuffer();
        }

        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

// ============================================================
//  BLOCK 5 — setup / loop
// ============================================================

void setup() {
    Serial.begin(115200);
    delay(500);

    pinMode(BUTTON_PIN, INPUT_PULLUP);
    Wire.begin();

    // OLED initialisieren
    if (!u8g2.begin()) {
        Serial.println("OLED init failed");
        while (true) delay(1000);
    }

    // Startscreen
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_9x15B_tf);
    u8g2.drawStr(8, 28, "Kitchen");
    u8g2.drawStr(8, 46, "Weight");
    u8g2.sendBuffer();

    // Konfiguration und Kalibrierfaktor aus NVS laden
    loadFactors();
    loadConfig();    // Konfiguration + applyDerivedConfig()
    Serial.printf("[setup] factorRear=%.4f  factorMid=%.4f  factorFront=%.4f\n",
                factorRear, factorMid, factorFront);

    // ── HX711 initialisieren ─────────────────────────────────────────────────
    scaleRear.begin (HX711_REAR_DOUT,  HX711_REAR_SCK);
    scaleMid.begin  (HX711_MID_DOUT,   HX711_MID_SCK);
    scaleFront.begin(HX711_FRONT_DOUT, HX711_FRONT_SCK);

    // Auf alle drei HX711 warten (max. 3 s)
    uint32_t t = millis() + 3000;
    while (!(scaleRear.is_ready() && scaleMid.is_ready() && scaleFront.is_ready())
           && millis() < t) {
        delay(200);
    }
    if (!(scaleRear.is_ready() && scaleMid.is_ready() && scaleFront.is_ready())) {
        Serial.println("[setup] Mindestens ein HX711 nicht bereit!");
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_9x15B_tf);
        u8g2.drawStr(0, 24, "HX711 fehlt!");
        u8g2.setFont(u8g2_font_5x8_tf);
        u8g2.drawStr(0, 40, "Rear / Mid / Front");
        u8g2.drawStr(0, 52, "Pruefen & Reset");
        u8g2.sendBuffer();
        while (true) delay(1000);
    }

    // Tara + gemeinsamen Kalibrierfaktor setzen
    scaleRear.tare();
    scaleMid.tare();
    scaleFront.tare();
    scaleRear.set_scale(factorRear);
    scaleMid.set_scale(factorMid);
    scaleFront.set_scale(factorFront);

    Serial.println("[setup] Alle 3 HX711 bereit und tariert.");

    // ── FreeRTOS-Queues anlegen ───────────────────────────────────────────────
    sampleQueue      = xQueueCreate(4,  sizeof(SampleSnapshot));
    scaleCmdQueue    = xQueueCreate(4,  sizeof(ScaleCommand));
    scaleResultQueue = xQueueCreate(4,  sizeof(ScaleResult));

    // ── Bluetooth ────────────────────────────────────────────────────────────
    BT.begin(BT_DEVICE_NAME);
    BT.register_callback([](esp_spp_cb_event_t event, esp_spp_cb_param_t*) {
        if (event == ESP_SPP_SRV_OPEN_EVT) {
            btConnected   = true;
            btReadyToSend = false;   // warten auf sync
        } else if (event == ESP_SPP_CLOSE_EVT) {
            btConnected   = false;
            btReadyToSend = false;
        }
    });
    Serial.printf("[setup] BT bereit als \"%s\"\n", BT_DEVICE_NAME);

    // ── Tasks starten ─────────────────────────────────────────────────────────
    // measureTask auf Core 1 (Arduino-Standard-Core), hohe Priorität
    xTaskCreatePinnedToCore(measureTask,   "measure",   4096, nullptr, 1, nullptr, 1);
    // btDisplayTask auf Core 0, normale Priorität
    xTaskCreatePinnedToCore(btDisplayTask, "btDisplay", 12288, nullptr, 1, nullptr, 0);

    // Warten bis beide Tasks gestartet sind
    while (!measureTaskStarted || !displayTaskStarted) delay(10);
    Serial.println("[setup] Tasks gestartet. System bereit.");
}

void loop() {
    // Beide Tasks laufen eigenständig; loop bleibt leer.
    vTaskDelay(pdMS_TO_TICKS(1000));
}