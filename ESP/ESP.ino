// ============================================================
//  Kitchen Weight — ESP32 Firmware
//  Waage mit HX711, SSD1306, Bluetooth, FFT
// ============================================================
//
//  Feste Parameter (nicht konfigurierbar):
//    SAMPLE_RATE_HZ = 20 Hz  → Nyquist 10 Hz, KitchenAid-Stufen auflösbar
//    FFT_SIZE       = 128    → 0.156 Hz/Bin, 6.4 s Frame
//    FFT_BARS       = 32     → 4 px/Balken auf 128px OLED
//
//  Konfigurierbar (App / NVS):
//    publishRateHz         1–20 Hz   BT-Senderate
//    avgSamples            1–4       HX711-Mittelung
//    offlineBufferSeconds  10–180 s  Puffergröße
//    displayHz             1–10 Hz   OLED-Refresh
//
//  Benötigte Libraries:
//    HX711 (bogde), U8g2, ArduinoJson, arduinoFFT (kosme1 v2.x)
// ============================================================

#include <Arduino.h>
#include <HX711.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <BluetoothSerial.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <arduinoFFT.h>

// ── Hardware ─────────────────────────────────────────────────────────────────
#define HX711_DOUT      25
#define HX711_SCK       26
#define BUTTON_PIN      32
// HX711 RATE-Pin muss auf HIGH (3.3V) → 80 SPS Modus

// ── NVS ──────────────────────────────────────────────────────────────────────
#define BT_DEVICE_NAME  "Waage_ESP32"
#define NVS_NAMESPACE   "waage"
#define NVS_KEY_INIT    "cfginit"
#define NVS_KEY_FACTOR  "kalfaktor"
#define NVS_KEY_PRATE   "prate"
#define NVS_KEY_AVG     "avg"
#define NVS_KEY_BUFSEC  "bufsec"
#define NVS_KEY_DISPHZ  "disphz"
#define NVS_KEY_DISPMOD "dispmode"

// ── Feste Sampling-Konstante ──────────────────────────────────────────────────
constexpr uint8_t  SAMPLE_RATE_HZ   = 20;        // Hz – nie ändern
constexpr uint32_t SAMPLE_PERIOD_MS = 1000UL / SAMPLE_RATE_HZ;  // 50 ms

// ── Defaults ─────────────────────────────────────────────────────────────────
constexpr uint16_t DEFAULT_PUBLISH_RATE_HZ         = 2;
constexpr uint8_t  DEFAULT_AVG_SAMPLES             = 2;
constexpr uint16_t DEFAULT_OFFLINE_BUFFER_SECONDS  = 60;
constexpr uint8_t  DEFAULT_DISPLAY_HZ              = 2;

// ── Puffer-Limits ─────────────────────────────────────────────────────────────
// Max. offlineBufferSeconds = 180 s → 20 × 180 = 3600 Samples
constexpr uint16_t MAX_OFFLINE_BUFFER_SECONDS = 180;
constexpr uint16_t MAX_OFFLINE_BUFFER         = 3600;  // SAMPLE_RATE_HZ × 180

// ── Display-Layout ────────────────────────────────────────────────────────────
constexpr uint8_t DISPLAY_WIDTH   = 128;
constexpr uint8_t DISPLAY_HEIGHT  = 64;
constexpr uint8_t VALUE_HEIGHT    = 20;
constexpr uint8_t PLOT_HEIGHT     = DISPLAY_HEIGHT - VALUE_HEIGHT;
constexpr uint8_t PLOT_Y_TOP      = VALUE_HEIGHT;
constexpr uint8_t PLOT_Y_BOTTOM   = DISPLAY_HEIGHT - 1;
constexpr uint8_t DISP_HIST       = 128;

// ── FFT – fest, nicht konfigurierbar ─────────────────────────────────────────
constexpr uint16_t FFT_SIZE      = 128;   // Samples/Frame → 6.4 s bei 20 Hz
constexpr float    FFT_BIN_RES   = (float)SAMPLE_RATE_HZ / (float)FFT_SIZE;
                                          // = 0.156 Hz/Bin
constexpr uint8_t  FFT_BAR_PX   = 4;
constexpr uint8_t  FFT_BARS     = DISPLAY_WIDTH / FFT_BAR_PX;  // 32
constexpr uint8_t  FFT_LABEL_H  = 10;
constexpr uint8_t  FFT_AXIS_H   = 8;
constexpr uint8_t  FFT_MAX_BAR_H = DISPLAY_HEIGHT - FFT_LABEL_H - FFT_AXIS_H;

// KitchenAid Stufen-Frequenzen (planetare Umlauffrequenz)
constexpr float KA_SPEEDS[] = { 1.00f, 1.58f, 2.25f, 3.00f, 3.75f, 4.67f };
constexpr uint8_t KA_SPEED_COUNT = 6;

// ── Structs ───────────────────────────────────────────────────────────────────
struct DeviceConfig {
    uint16_t publishRateHz;
    uint8_t  avgSamples;
    uint16_t offlineBufferSeconds;
    uint8_t  displayHz;
};

struct SampleSnapshot {
    float    weight;
    uint32_t sampleMs;
    bool     timeSynced;
};

enum class ScaleCommandType : uint8_t { None, Tare, Calibrate, ApplyConfig };

struct ScaleCommand {
    ScaleCommandType type;
    float            knownWeightG;
    DeviceConfig     newConfig;
};

struct ScaleResult {
    bool  ok;
    char  type[16];
    float value;
    char  msg[48];
};

struct OfflineSample {
    int64_t ts;
    float   w;
    bool    synced;
};

// ── Hardware-Objekte ──────────────────────────────────────────────────────────
U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
HX711           scale;
BluetoothSerial BT;
Preferences     prefs;

// ── Konfiguration & abgeleitete Werte ─────────────────────────────────────────
DeviceConfig      config;
float             calibrationFactor = 1.0f;
volatile uint32_t publishPeriodMs   = 500;
volatile uint32_t displayPeriodMs   = 500;
uint8_t           displayMode       = 1;     // 0=groß, 1=Verlauf, 2=FFT
uint16_t          offlineBufferCapacity = 1200;

// ── Queues ────────────────────────────────────────────────────────────────────
QueueHandle_t sampleQueue      = nullptr;
QueueHandle_t scaleCmdQueue    = nullptr;
QueueHandle_t scaleResultQueue = nullptr;

// ── Aktueller Messwert ────────────────────────────────────────────────────────
float    currentWeight       = 0.0f;
int64_t  currentWeightTs     = 0;
bool     currentWeightSynced = false;

// ── BT & Zeit ────────────────────────────────────────────────────────────────
volatile bool    btConnected = false;
volatile int64_t timeOffset  = 0;

// ── Offline-Ringpuffer ────────────────────────────────────────────────────────
OfflineSample offlineBuffer[MAX_OFFLINE_BUFFER];
uint16_t      offlineWriteIdx = 0;
uint16_t      offlineSendIdx  = 0;

// ── Display-History ───────────────────────────────────────────────────────────
float   dispHist[DISP_HIST] = {};
uint8_t dispHistIdx  = 0;
uint8_t dispHistFill = 0;

// ── FFT ───────────────────────────────────────────────────────────────────────
double   fftReal[FFT_SIZE] = {};
double   fftImag[FFT_SIZE] = {};
uint16_t fftSampleCount    = 0;

// FFT-Ergebnisse (nur in btDisplayTask geschrieben und gelesen)
float   fftPeakHz       = 0.f;
float   fftPeakAmp      = 0.f;
float   fftPeakHold     = 1.f;
float   fftBarHeights[FFT_BARS] = {};  // normiert 0..1 für OLED
bool    fftResultReady  = false;

// ── Button ────────────────────────────────────────────────────────────────────
bool     lastBtnState = HIGH;
uint32_t btnPressedAt = 0;

bool displayTaskStarted = false;
bool measureTaskStarted = false;

// ============================================================
//  BLOCK 2 — Config-Funktionen
// ============================================================

float loadFactor() {
    prefs.begin(NVS_NAMESPACE, true);
    float f = prefs.getFloat(NVS_KEY_FACTOR, 1.0f);
    prefs.end();
    return f;
}

void saveFactor(float f) {
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putFloat(NVS_KEY_FACTOR, f);
    prefs.end();
}

void applyDerivedConfig() {
    // publishRateHz
    if (config.publishRateHz < 1)              config.publishRateHz = 1;
    if (config.publishRateHz > SAMPLE_RATE_HZ) config.publishRateHz = SAMPLE_RATE_HZ;
    publishPeriodMs = 1000UL / (uint32_t)config.publishRateHz;

    // displayHz
    uint8_t dHz = config.displayHz;
    if (dHz < 1)  dHz = 1;
    if (dHz > 10) dHz = 10;
    config.displayHz  = dHz;
    displayPeriodMs   = 1000UL / dHz;

    // offlineBufferCapacity
    uint32_t cap = (uint32_t)SAMPLE_RATE_HZ
                 * (uint32_t)config.offlineBufferSeconds;
    if (cap < SAMPLE_RATE_HZ)     cap = SAMPLE_RATE_HZ;
    if (cap > MAX_OFFLINE_BUFFER) cap = MAX_OFFLINE_BUFFER;
    offlineBufferCapacity = (uint16_t)cap;

    if (offlineWriteIdx >= offlineBufferCapacity ||
        offlineSendIdx  >= offlineBufferCapacity) {
        offlineWriteIdx = 0;
        offlineSendIdx  = 0;
    }

    // FFT zurücksetzen wenn Config geändert
    fftSampleCount = 0;
    fftResultReady = false;
    fftPeakHold    = 1.f;
}

bool validateConfig(const DeviceConfig& c, String& err) {
    if (c.publishRateHz < 1 || c.publishRateHz > SAMPLE_RATE_HZ)
        { err = "publishRateHz out of range (1–20)";     return false; }
    if (c.avgSamples < 1 || c.avgSamples > 4)
        { err = "avgSamples out of range (1–4)";         return false; }
    if (c.offlineBufferSeconds < 10 ||
        c.offlineBufferSeconds > MAX_OFFLINE_BUFFER_SECONDS)
        { err = "offlineBufferSeconds out of range (10–180)"; return false; }
    if (c.displayHz < 1 || c.displayHz > 10)
        { err = "displayHz out of range (1–10)";         return false; }
    return true;
}

void loadConfig() {
    prefs.begin(NVS_NAMESPACE, false);
    if (!prefs.isKey(NVS_KEY_INIT)) {
        prefs.putBool  (NVS_KEY_INIT,   true);
        prefs.putUShort(NVS_KEY_PRATE,  DEFAULT_PUBLISH_RATE_HZ);
        prefs.putUChar (NVS_KEY_AVG,    DEFAULT_AVG_SAMPLES);
        prefs.putUShort(NVS_KEY_BUFSEC, DEFAULT_OFFLINE_BUFFER_SECONDS);
        prefs.putUChar (NVS_KEY_DISPHZ, DEFAULT_DISPLAY_HZ);
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

// ============================================================
//  BLOCK 3 — BT-Helfer + handleCommand
// ============================================================

void btSend(const String& s) {
    if (btConnected) BT.print(s);
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
    StaticJsonDocument<256> d;
    d["type"]                  = typeName;
    d["sampleRateHz"]          = SAMPLE_RATE_HZ;
    d["publishRateHz"]         = config.publishRateHz;
    d["avgSamples"]            = config.avgSamples;
    d["offlineBufferSeconds"]  = config.offlineBufferSeconds;
    d["offlineBufferCapacity"] = offlineBufferCapacity;
    d["displayHz"]             = config.displayHz;
    d["calibrationFactor"]     = calibrationFactor; 
    btSendJson(d);
}

uint8_t toPixel(float v, float lo, float hi) {
    if (hi <= lo) return PLOT_Y_BOTTOM;
    float r = (v - lo) / (hi - lo);
    return (uint8_t)constrain(
        (int)(PLOT_Y_BOTTOM - r * PLOT_HEIGHT),
        (int)PLOT_Y_TOP, (int)PLOT_Y_BOTTOM);
}

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

    if (strcmp(type, "calibrate") == 0) {
        float known = doc["weight"] | 0.0f;
        if (known <= 0.0f) { sendError("Gewicht ungueltig"); return; }
        ScaleCommand cmd{};
        cmd.type         = ScaleCommandType::Calibrate;
        cmd.knownWeightG = known;
        xQueueSend(scaleCmdQueue, &cmd, 0);
        return;
    }

    if (strcmp(type, "get_factor") == 0 || strcmp(type, "getfactor") == 0) {
        StaticJsonDocument<64> r;
        r["type"]  = "factor";
        r["value"] = calibrationFactor;
        btSendJson(r);
        return;
    }

    if (strcmp(type, "getconfig") == 0) {
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
        StaticJsonDocument<64> r;
        r["type"] = "sync_done";
        btSendJson(r);
        return;
    }

    sendError("unknown command");
}

// ============================================================
//  BLOCK 4 — measureTask (Core 1)
// ============================================================

void measureTask(void* param) {
    measureTaskStarted = true;

    uint32_t lastSampleAt = millis();
    ScaleCommand cmd;

    while (true) {
        // Kommandos verarbeiten
        while (xQueueReceive(scaleCmdQueue, &cmd, 0) == pdTRUE) {

            if (cmd.type == ScaleCommandType::Tare) {
                scale.tare();
                ScaleResult r{};
                r.ok = true;
                strlcpy(r.type, "tare_done", sizeof(r.type));
                r.value = (float)scale.get_offset();
                xQueueSend(scaleResultQueue, &r, 0);

            } else if (cmd.type == ScaleCommandType::Calibrate) {
                scale.set_scale(1.0f);
                float raw = scale.get_value(config.avgSamples * 4);
                ScaleResult r{};
                if (raw == 0.0f || cmd.knownWeightG <= 0.0f) {
                    scale.set_scale(calibrationFactor);
                    r.ok = false;
                    strlcpy(r.type, "error",      sizeof(r.type));
                    strlcpy(r.msg,  "Rohwert 0",  sizeof(r.msg));
                } else {
                    calibrationFactor = raw / cmd.knownWeightG;
                    scale.set_scale(calibrationFactor);
                    saveFactor(calibrationFactor);
                    r.ok = true;
                    strlcpy(r.type, "factor", sizeof(r.type));
                    r.value = calibrationFactor;
                }
                xQueueSend(scaleResultQueue, &r, 0);

            } else if (cmd.type == ScaleCommandType::ApplyConfig) {
                config = cmd.newConfig;
                applyDerivedConfig();
                saveConfig(config);
                ScaleResult r{};
                r.ok = true;
                strlcpy(r.type, "config_saved", sizeof(r.type));
                xQueueSend(scaleResultQueue, &r, 0);
            }
        }

        // Feste 20 Hz Abtastrate
        uint32_t now = millis();
        if ((uint32_t)(now - lastSampleAt) >= SAMPLE_PERIOD_MS) {
            lastSampleAt += SAMPLE_PERIOD_MS;
            if (scale.is_ready()) {
                float w = scale.get_units(config.avgSamples);
                SampleSnapshot s{ w, millis(), timeOffset != 0 };
                xQueueSend(sampleQueue, &s, 0);
            }
        }

        taskYIELD();
    }
}

// ============================================================
//  BLOCK 5 — btDisplayTask (Core 0)
// ============================================================

void btDisplayTask(void* param) {
    displayTaskStarted = true;

    String   inBuf      = "";
    uint32_t lastDisplay = 0;
    uint32_t lastPublish = 0;

    while (true) {
        uint32_t now = millis();

        // ── Button ───────────────────────────────────────────────────────────
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

        // ── Samples übernehmen ────────────────────────────────────────────────
        SampleSnapshot snap;
        while (xQueueReceive(sampleQueue, &snap, 0) == pdTRUE) {
            currentWeight       = snap.weight;
            currentWeightTs     = timeOffset + (int64_t)snap.sampleMs;
            currentWeightSynced = snap.timeSynced;

            // [1] Display-History
            dispHist[dispHistIdx] = snap.weight;
            dispHistIdx = (dispHistIdx + 1) % DISP_HIST;
            if (dispHistFill < DISP_HIST) dispHistFill++;

            // [2] Offline-Ringpuffer
            offlineBuffer[offlineWriteIdx] = {
                currentWeightTs, snap.weight, snap.timeSynced
            };
            offlineWriteIdx = (offlineWriteIdx + 1) % offlineBufferCapacity;
            if (offlineWriteIdx == offlineSendIdx)
                offlineSendIdx = (offlineSendIdx + 1) % offlineBufferCapacity;

            // [3] FFT-Buffer
            fftReal[fftSampleCount] = (double)snap.weight;
            fftSampleCount++;

            // ── FFT berechnen wenn Buffer voll ────────────────────────────────
            if (fftSampleCount >= FFT_SIZE) {
                fftSampleCount = 0;

                // DC entfernen (Mittelwert abziehen)
                double mean = 0.0;
                for (uint16_t i = 0; i < FFT_SIZE; i++) mean += fftReal[i];
                mean /= (double)FFT_SIZE;
                for (uint16_t i = 0; i < FFT_SIZE; i++) {
                    fftReal[i] -= mean;
                    fftImag[i]  = 0.0;
                }

                // FFT
                ArduinoFFT<double> fft(fftReal, fftImag, FFT_SIZE,
                                       (double)SAMPLE_RATE_HZ);
                fft.windowing(FFTWindow::Hamming, FFTDirection::Forward);
                fft.compute(FFTDirection::Forward);
                fft.complexToMagnitude();
                // fftReal[i] enthält jetzt Amplituden (Magnitude)

                // Maximale Amplitude (Bins 1..N/2-1, Bin 0 = DC verwerfen)
                double maxAmp = 0.001;
                for (uint16_t i = 1; i < FFT_SIZE / 2; i++)
                    if (fftReal[i] > maxAmp) maxAmp = fftReal[i];

                // Peak-Frequenz
                double peakAmp = 0.0;
                uint16_t peakBin = 1;
                for (uint16_t i = 1; i < FFT_SIZE / 2; i++) {
                    if (fftReal[i] > peakAmp) {
                        peakAmp  = fftReal[i];
                        peakBin  = i;
                    }
                }

                // Peak-Hold (langsames Abklingen)
                if (peakAmp > (double)fftPeakHold) fftPeakHold = (float)peakAmp;
                else                                fftPeakHold *= 0.98f;
                if (fftPeakHold < 1.f)              fftPeakHold = 1.f;

                fftPeakHz  = peakBin * FFT_BIN_RES;
                fftPeakAmp = (float)peakAmp;

                // Bins auf 32 Display-Balken verteilen (gleichmäßig über Nyquist)
                float nyquist = SAMPLE_RATE_HZ / 2.0f;
                for (uint8_t bar = 0; bar < FFT_BARS; bar++) {
                    float    fLow    =  bar      * nyquist / FFT_BARS;
                    float    fHigh   = (bar + 1) * nyquist / FFT_BARS;
                    uint16_t binLow  = max(1, (int)(fLow  * FFT_SIZE / SAMPLE_RATE_HZ));
                    uint16_t binHigh = max(binLow + 1,
                                          (int)(fHigh * FFT_SIZE / SAMPLE_RATE_HZ));
                    if (binHigh > FFT_SIZE / 2) binHigh = FFT_SIZE / 2;

                    double barMax = 0.0;
                    for (uint16_t bin = binLow; bin < binHigh; bin++)
                        if (fftReal[bin] > barMax) barMax = fftReal[bin];

                    fftBarHeights[bar] = (float)(barMax / fftPeakHold);
                }
                fftResultReady = true;

                // ── FFT-Ergebnis per BT senden ────────────────────────────────
                if (btConnected) {
                    // Bins 0..FFT_SIZE/2-1 normiert auf uint8 0–255
                    // Bin 0 (DC) immer 0
                    String msg;
                    msg.reserve(300);
                    msg  = "{\"type\":\"fft_result\"";
                    msg += ",\"peakHz\":";  msg += String(fftPeakHz, 3);
                    msg += ",\"peakAmp\":"; msg += String(fftPeakAmp, 1);
                    msg += ",\"binRes\":";  msg += String(FFT_BIN_RES, 4);
                    msg += ",\"fs\":";      msg += SAMPLE_RATE_HZ;
                    msg += ",\"bins\":[0";  // Bin 0 = DC = 0
                    for (uint16_t i = 1; i < FFT_SIZE / 2; i++) {
                        uint8_t val = (uint8_t)constrain(
                            (int)(fftReal[i] / maxAmp * 255.0), 0, 255);
                        msg += ",";
                        msg += val;
                    }
                    msg += "]}\n";
                    btSend(msg);
                }

                // Serial Debug
                Serial.printf("[FFT] Peak: %.3f Hz | Amp: %.1f | Hold: %.1f\n",
                              fftPeakHz, fftPeakAmp, fftPeakHold);
            }
        }

        // ── ScaleResults verarbeiten ──────────────────────────────────────────
        ScaleResult res;
        while (xQueueReceive(scaleResultQueue, &res, 0) == pdTRUE) {
            if (strcmp(res.type, "tare_done") == 0) {
                StaticJsonDocument<64> d;
                d["type"]   = "tare_done";
                d["offset"] = res.value;
                btSendJson(d);
            } else if (strcmp(res.type, "factor") == 0) {
                StaticJsonDocument<64> d;
                d["type"]  = "factor";
                d["value"] = res.value;
                btSendJson(d);
            } else if (strcmp(res.type, "config_saved") == 0) {
                sendConfig("config_saved");
            } else if (strcmp(res.type, "error") == 0) {
                sendError(res.msg);
            }
        }

        // ── BT: Messwert-Batch senden ─────────────────────────────────────────
        if (btConnected && (uint32_t)(now - lastPublish) >= publishPeriodMs) {
            lastPublish = now;

            uint16_t snapHead = offlineWriteIdx;
            if (snapHead != offlineSendIdx) {
                uint16_t count = (snapHead + offlineBufferCapacity
                                  - offlineSendIdx) % offlineBufferCapacity;

                String msg;
                msg.reserve(count * 22 + 48);
                msg = "{\"type\":\"measurement_batch\",\"samples\":[";
                uint16_t idx = offlineSendIdx;
                for (uint16_t i = 0; i < count; i++) {
                    if (i > 0) msg += ",";
                    msg += "{\"w\":";
                    msg += String(offlineBuffer[idx].w, 4);
                    msg += ",\"ts\":";
                    msg += String((long long)offlineBuffer[idx].ts);
                    msg += "}";
                    idx = (idx + 1) % offlineBufferCapacity;
                }
                msg += "]}\n";
                btSend(msg);
                offlineSendIdx = snapHead;
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

            char wBuf[24];
            if (currentWeight >= 1000.0f || currentWeight <= -1000.0f)
                snprintf(wBuf, sizeof(wBuf), "%.3f kg", currentWeight / 1000.0f);
            else
                snprintf(wBuf, sizeof(wBuf), "%.1f g",  currentWeight);

            // BT-Status-Indikator (für alle Modi)
            auto drawBtIndicator = [&]() {
                if      (btConnected && timeOffset != 0) u8g2.drawDisc  (124, 4, 3);
                else if (btConnected)                    u8g2.drawCircle(124, 4, 3);
                else                                     u8g2.drawFrame (120, 1, 7, 7);
            };

            if (displayMode == 0) {
                // ── Modus 0: Großes Gewicht ───────────────────────────────────
                u8g2.setFont(u8g2_font_10x20_tf);
                uint8_t tw = u8g2.getStrWidth(wBuf);
                u8g2.drawStr((DISPLAY_WIDTH - tw) / 2, 42, wBuf);
                drawBtIndicator();

            } else if (displayMode == 1) {
                // ── Modus 1: Gewicht + Verlaufsgraph ──────────────────────────
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

            } else {
                // ── Modus 2: FFT-Spektrum ─────────────────────────────────────
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

                    // Peak-Frequenz oben links
                    char fBuf[20];
                    snprintf(fBuf, sizeof(fBuf), "%.2fHz", fftPeakHz);
                    u8g2.drawStr(0, FFT_LABEL_H - 1, fBuf);

                    // Trennlinie
                    u8g2.drawHLine(0, DISPLAY_HEIGHT - FFT_AXIS_H, DISPLAY_WIDTH);

                    // Achsenbeschriftung: 0 | Mitte | Nyquist
                    u8g2.drawStr(0,                   DISPLAY_HEIGHT - 1, "0");
                    u8g2.drawStr(DISPLAY_WIDTH / 2 - 6, DISPLAY_HEIGHT - 1, "5Hz");
                    u8g2.drawStr(DISPLAY_WIDTH - 20,  DISPLAY_HEIGHT - 1, "10Hz");

                } else {
                    // Noch nicht genug Samples für FFT
                    u8g2.drawStr(4, 25, "FFT wird berechnet");
                    char wBuf2[24];
                    uint16_t remaining = FFT_SIZE - fftSampleCount;
                    snprintf(wBuf2, sizeof(wBuf2), "noch %d s...",
                             remaining / SAMPLE_RATE_HZ);
                    u8g2.drawStr(24, 40, wBuf2);
                }
            }

            u8g2.sendBuffer();
        }

        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

// ============================================================
//  BLOCK 6 — setup / loop
// ============================================================

void setup() {
    Serial.begin(115200);
    delay(500);

    pinMode(BUTTON_PIN, INPUT_PULLUP);
    Wire.begin();

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

    loadConfig();
    calibrationFactor = loadFactor();

    // HX711 initialisieren
    scale.begin(HX711_DOUT, HX711_SCK);
    uint32_t t = millis() + 3000;
    while (!scale.is_ready() && millis() < t) delay(200);
    if (!scale.is_ready()) {
        u8g2.clearBuffer();
        u8g2.drawStr(0, 32, "HX711 fehlt!");
        u8g2.sendBuffer();
        while (true) delay(1000);
    }
    scale.tare();
    scale.set_scale(calibrationFactor);

    // Puffer / FFT zurücksetzen
    offlineWriteIdx = 0;
    offlineSendIdx  = 0;
    fftSampleCount  = 0;
    memset(fftReal, 0, sizeof(fftReal));
    memset(fftImag, 0, sizeof(fftImag));

    // Queues anlegen
    // sampleQueue Größe 4: Puffer damit bei FFT-Berechnung keine Samples verloren gehen
    sampleQueue      = xQueueCreate(4, sizeof(SampleSnapshot));
    scaleCmdQueue    = xQueueCreate(4, sizeof(ScaleCommand));
    scaleResultQueue = xQueueCreate(4, sizeof(ScaleResult));

    if (!sampleQueue || !scaleCmdQueue || !scaleResultQueue) {
        Serial.println("Queue-Fehler");
        while (true) delay(1000);
    }

    // Bluetooth
    BT.register_callback([](esp_spp_cb_event_t event, esp_spp_cb_param_t*) {
        if      (event == ESP_SPP_SRV_OPEN_EVT) { btConnected = true;  }
        else if (event == ESP_SPP_CLOSE_EVT)    { btConnected = false; timeOffset = 0; }
    });
    if (!BT.begin(BT_DEVICE_NAME)) {
        Serial.println("BT init failed");
        while (true) delay(1000);
    }

    // Tasks starten
    // btDisplayTask Stack 12288: ArduinoFFT (128 doubles) + String-Builder brauchen Platz
    xTaskCreatePinnedToCore(measureTask,   "Messen",     4096,  NULL, 1, NULL, 1);
    xTaskCreatePinnedToCore(btDisplayTask, "BT+Display", 12288, NULL, 1, NULL, 0);

    Serial.println("Bereit.");
    Serial.printf("sampleRateHz=%d (fest) | FFT_SIZE=%d | binRes=%.3fHz\n",
                  SAMPLE_RATE_HZ, FFT_SIZE, FFT_BIN_RES);
}

void loop() {
    vTaskDelay(portMAX_DELAY);
}
