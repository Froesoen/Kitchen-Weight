#include <Arduino.h>
#include <HX711.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <BluetoothSerial.h>
#include <Preferences.h>
#include <ArduinoJson.h>

#define HX711_DOUT      25
#define HX711_SCK       26
#define BUTTON_PIN      32
#define BT_DEVICE_NAME  "Waage_ESP32"
#define NVS_NAMESPACE   "waage"
#define NVS_KEY_INIT    "cfginit"
#define NVS_KEY_FACTOR  "kalfaktor"
#define NVS_KEY_SRATE   "srate"
#define NVS_KEY_PRATE   "prate"
#define NVS_KEY_AVG     "avg"
#define NVS_KEY_BUFSEC  "bufsec"
#define NVS_KEY_DISPHZ  "disphz"        // NEU

constexpr uint16_t DEFAULT_SAMPLE_RATE_HZ        = 2;
constexpr uint16_t DEFAULT_PUBLISH_RATE_HZ       = 2;
constexpr uint8_t  DEFAULT_AVG_SAMPLES           = 4;
constexpr uint16_t DEFAULT_OFFLINE_BUFFER_SECONDS = 60;
constexpr uint8_t  DEFAULT_DISPLAY_HZ            = 2;  // NEU
constexpr uint16_t MAX_OFFLINE_BUFFER            = 3600;
constexpr uint8_t  DISPLAY_WIDTH                 = 128;
constexpr uint8_t  DISPLAY_HEIGHT                = 64;
constexpr uint8_t  VALUE_HEIGHT                  = 20;
constexpr uint8_t  PLOT_HEIGHT  = DISPLAY_HEIGHT - VALUE_HEIGHT;
constexpr uint8_t  PLOT_Y_TOP   = VALUE_HEIGHT;
constexpr uint8_t  PLOT_Y_BOTTOM = DISPLAY_HEIGHT - 1;
constexpr uint8_t  DISP_HIST    = 128;

struct DeviceConfig {
    uint16_t sampleRateHz;
    uint16_t publishRateHz;
    uint8_t  avgSamples;
    uint16_t offlineBufferSeconds;
    uint8_t  displayHz;   // NEU
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

U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
HX711          scale;
BluetoothSerial BT;
Preferences    prefs;

DeviceConfig     config;
float            calibrationFactor = 1.0f;
volatile uint32_t samplePeriodMs  = 500;
volatile uint32_t publishPeriodMs = 500;
volatile uint32_t displayPeriodMs = 500;  // NEU
uint8_t           displayMode     = 1;    // NEU: 0=nur Gewicht, 1=mit Verlauf
uint16_t          offlineBufferCapacity = 120;

QueueHandle_t sampleQueue      = nullptr;
QueueHandle_t scaleCmdQueue    = nullptr;
QueueHandle_t scaleResultQueue = nullptr;

float   currentWeight       = 0.0f;
int64_t currentWeightTs     = 0;
bool    currentWeightSynced = false;

volatile bool    btConnected = false;
volatile int64_t timeOffset  = 0;

OfflineSample offlineBuffer[MAX_OFFLINE_BUFFER];
uint16_t      offlineWriteIdx = 0;
uint16_t      offlineSendIdx  = 0;

float   dispHist[DISP_HIST] = {};
uint8_t dispHistIdx  = 0;
uint8_t dispHistFill = 0;

bool     lastBtnState = HIGH;
uint32_t btnPressedAt = 0;

bool displayTaskStarted  = false;
bool measureTaskStarted  = false;

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
    if (config.sampleRateHz  < 1) config.sampleRateHz  = 1;
    if (config.publishRateHz < 1) config.publishRateHz = 1;
    if (config.publishRateHz > config.sampleRateHz)
        config.publishRateHz = config.sampleRateHz;

    samplePeriodMs  = 1000UL / (uint32_t)config.sampleRateHz;
    publishPeriodMs = 1000UL / (uint32_t)config.publishRateHz;
    if (samplePeriodMs  < 1) samplePeriodMs  = 1;
    if (publishPeriodMs < 1) publishPeriodMs = 1;

    // NEU: Display-Periode
    uint8_t dHz = config.displayHz;
    if (dHz < 1)  dHz = 1;
    if (dHz > 10) dHz = 10;
    displayPeriodMs = 1000UL / dHz;

    uint32_t cap = (uint32_t)config.sampleRateHz
                   * (uint32_t)config.offlineBufferSeconds;
    if (cap < 1) cap = 1;
    if (cap > MAX_OFFLINE_BUFFER) cap = MAX_OFFLINE_BUFFER;
    offlineBufferCapacity = (uint16_t)cap;
}

bool validateConfig(const DeviceConfig& c, String& err) {
    if (c.sampleRateHz  < 1 || c.sampleRateHz  > 20)
    { err = "sampleRateHz out of range";            return false; }
    if (c.publishRateHz < 1 || c.publishRateHz > 20)
    { err = "publishRateHz out of range";           return false; }
    if (c.publishRateHz > c.sampleRateHz)
    { err = "publishRateHz must be <= sampleRateHz"; return false; }
    if (c.avgSamples < 1 || c.avgSamples > 16)
    { err = "avgSamples out of range";              return false; }
    if (c.offlineBufferSeconds < 10 || c.offlineBufferSeconds > 3600)
    { err = "offlineBufferSeconds out of range";    return false; }
    if (c.displayHz < 1 || c.displayHz > 10)
    { err = "displayHz out of range";               return false; }  // NEU
    uint32_t cap = (uint32_t)c.sampleRateHz * (uint32_t)c.offlineBufferSeconds;
    if (cap > MAX_OFFLINE_BUFFER)
    { err = "offline buffer too large";             return false; }
    return true;
}

void loadConfig() {
    prefs.begin(NVS_NAMESPACE, false);
    if (!prefs.isKey(NVS_KEY_INIT)) {
        prefs.putBool  (NVS_KEY_INIT,   true);
        prefs.putUShort(NVS_KEY_SRATE,  DEFAULT_SAMPLE_RATE_HZ);
        prefs.putUShort(NVS_KEY_PRATE,  DEFAULT_PUBLISH_RATE_HZ);
        prefs.putUChar (NVS_KEY_AVG,    DEFAULT_AVG_SAMPLES);
        prefs.putUShort(NVS_KEY_BUFSEC, DEFAULT_OFFLINE_BUFFER_SECONDS);
        prefs.putUChar (NVS_KEY_DISPHZ, DEFAULT_DISPLAY_HZ);  // NEU
    }
    config.sampleRateHz         = prefs.getUShort(NVS_KEY_SRATE,  DEFAULT_SAMPLE_RATE_HZ);
    config.publishRateHz        = prefs.getUShort(NVS_KEY_PRATE,  DEFAULT_PUBLISH_RATE_HZ);
    config.avgSamples           = prefs.getUChar (NVS_KEY_AVG,    DEFAULT_AVG_SAMPLES);
    config.offlineBufferSeconds = prefs.getUShort(NVS_KEY_BUFSEC, DEFAULT_OFFLINE_BUFFER_SECONDS);
    config.displayHz            = prefs.getUChar (NVS_KEY_DISPHZ, DEFAULT_DISPLAY_HZ);  // NEU
    displayMode = prefs.getUChar("dispmode", 1);  // NEU: lokal, nicht in DeviceConfig
    prefs.end();
    applyDerivedConfig();
}

bool saveConfig(const DeviceConfig& c) {
    prefs.begin(NVS_NAMESPACE, false);
    bool ok = true;
    ok &= prefs.putUShort(NVS_KEY_SRATE,  c.sampleRateHz)         > 0;
    ok &= prefs.putUShort(NVS_KEY_PRATE,  c.publishRateHz)        > 0;
    ok &= prefs.putUChar (NVS_KEY_AVG,    c.avgSamples)           > 0;
    ok &= prefs.putUShort(NVS_KEY_BUFSEC, c.offlineBufferSeconds) > 0;
    ok &= prefs.putUChar (NVS_KEY_DISPHZ, c.displayHz)            > 0;  // NEU
    prefs.end();
    return ok;
}

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
    StaticJsonDocument<224> d;      // NEU: etwas größer für displayHz
    d["type"]                  = typeName;
    d["sampleRateHz"]          = config.sampleRateHz;
    d["publishRateHz"]         = config.publishRateHz;
    d["avgSamples"]            = config.avgSamples;
    d["offlineBufferSeconds"]  = config.offlineBufferSeconds;
    d["offlineBufferCapacity"] = offlineBufferCapacity;
    d["displayHz"]             = config.displayHz;  // NEU
    btSendJson(d);
}

// ── Display ──────────────────────────────────────────────────────────────────

uint8_t toPixel(float v, float lo, float hi) {
    if (hi <= lo) return PLOT_Y_BOTTOM;
    float r = (v - lo) / (hi - lo);
    return (uint8_t)constrain(
            (int)(PLOT_Y_BOTTOM - r * PLOT_HEIGHT),
            (int)PLOT_Y_TOP, (int)PLOT_Y_BOTTOM);
}

// ── Kommando-Handler ─────────────────────────────────────────────────────────

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
        next.sampleRateHz         = doc["sampleRateHz"]         | config.sampleRateHz;
        next.publishRateHz        = doc["publishRateHz"]        | config.publishRateHz;
        next.avgSamples           = doc["avgSamples"]           | config.avgSamples;
        next.offlineBufferSeconds = doc["offlineBufferSeconds"] | config.offlineBufferSeconds;
        next.displayHz            = doc["displayHz"]            | config.displayHz;  // NEU
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
        // NEU: displayHz im Initializer ergänzt
        cmd.newConfig = { DEFAULT_SAMPLE_RATE_HZ, DEFAULT_PUBLISH_RATE_HZ,
                          DEFAULT_AVG_SAMPLES, DEFAULT_OFFLINE_BUFFER_SECONDS,
                          DEFAULT_DISPLAY_HZ };
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

void measureTask(void* param) {
    if (!measureTaskStarted) measureTaskStarted = true;

    uint32_t lastSampleAt = millis();
    ScaleCommand cmd;

    while (true) {
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
                    strlcpy(r.type, "error",     sizeof(r.type));
                    strlcpy(r.msg,  "Rohwert 0", sizeof(r.msg));
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

        uint32_t now = millis();
        if ((uint32_t)(now - lastSampleAt) >= samplePeriodMs) {
            lastSampleAt += samplePeriodMs;
            if (scale.is_ready()) {
                float w = scale.get_units(config.avgSamples);
                SampleSnapshot s{ w, millis(), timeOffset != 0 };
                xQueueOverwrite(sampleQueue, &s);
            }
        }

        taskYIELD();
    }
}

void btDisplayTask(void* param) {
    if (!displayTaskStarted) displayTaskStarted = true;

    String   inBuf      = "";
    uint32_t lastDisplay = 0;
    uint32_t lastPublish = 0;

    while (true) {
        uint32_t now = millis();

        // ── Button ───────────────────────────────────────────────────────────
        bool btn = digitalRead(BUTTON_PIN);
        if (lastBtnState == HIGH && btn == LOW) btnPressedAt = now;
        if (lastBtnState == LOW  && btn == HIGH) {
            uint32_t held = now - btnPressedAt;
            if (held >= 1000) {
                // Langer Druck: displayMode umschalten
                displayMode = (displayMode == 0) ? 1 : 0;
                prefs.begin(NVS_NAMESPACE, false);
                prefs.putUChar("dispmode", displayMode);
                prefs.end();
            } else if (held > 50) {
                // Kurzer Druck: Tara
                ScaleCommand cmd{};
                cmd.type = ScaleCommandType::Tare;
                xQueueSend(scaleCmdQueue, &cmd, 0);
            }
        }
        lastBtnState = btn;

        // ── Samples übernehmen ───────────────────────────────────────────────
        SampleSnapshot snap;
        while (xQueueReceive(sampleQueue, &snap, 0) == pdTRUE) {
            currentWeight       = snap.weight;
            currentWeightTs     = timeOffset + (int64_t)snap.sampleMs;
            currentWeightSynced = snap.timeSynced;

            dispHist[dispHistIdx] = snap.weight;
            dispHistIdx = (dispHistIdx + 1) % DISP_HIST;
            if (dispHistFill < DISP_HIST) dispHistFill++;

            offlineBuffer[offlineWriteIdx] = { currentWeightTs, snap.weight, snap.timeSynced };
            offlineWriteIdx = (offlineWriteIdx + 1) % offlineBufferCapacity;
            if (offlineWriteIdx == offlineSendIdx)
                offlineSendIdx = (offlineSendIdx + 1) % offlineBufferCapacity;
        }

        // ── ScaleResults verarbeiten ─────────────────────────────────────────
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

        // ── Batch senden ─────────────────────────────────────────────────────
        if (btConnected && (uint32_t)(now - lastPublish) >= publishPeriodMs) {
            lastPublish = now;
            uint16_t snapHead = offlineWriteIdx;
            if (snapHead != offlineSendIdx) {
                uint16_t count = (snapHead + offlineBufferCapacity
                                  - offlineSendIdx) % offlineBufferCapacity;
                String msg = "{\"type\":\"measurement_batch\",\"samples\":[";
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

        // ── Bluetooth empfangen ───────────────────────────────────────────────
        if (btConnected) {
            while (BT.available()) {
                char c = BT.read();
                if (c == '\n') {
                    if (inBuf.length() > 0) { handleCommand(inBuf); inBuf = ""; }
                } else if (c != '\r') {
                    inBuf += c;
                    if (inBuf.length() > 512) inBuf = "";
                }
            }
        }

        // ── Display ───────────────────────────────────────────────────────────
        if ((uint32_t)(now - lastDisplay) >= displayPeriodMs) {  // NEU: displayPeriodMs
            lastDisplay = now;
            u8g2.clearBuffer();

            char buf[24];
            if (currentWeight >= 1000.0f || currentWeight <= -1000.0f)
                snprintf(buf, sizeof(buf), "%.3f kg", currentWeight / 1000.0f);
            else
                snprintf(buf, sizeof(buf), "%.1f g",  currentWeight);

            if (displayMode == 0) {
                // ── Mode 0: nur großes Gewicht, vertikal zentriert ───────────
                u8g2.setFont(u8g2_font_10x20_tf);
                uint8_t tw = u8g2.getStrWidth(buf);
                u8g2.drawStr((DISPLAY_WIDTH - tw) / 2, 42, buf);

                if      (btConnected && timeOffset != 0) u8g2.drawDisc  (124, 4, 3);
                else if (btConnected)                    u8g2.drawCircle(124, 4, 3);
                else                                     u8g2.drawFrame (120, 1, 7, 7);

            } else {
                // ── Mode 1: Gewicht oben + Verlauf unten (wie bisher) ────────
                u8g2.setFont(u8g2_font_9x15B_tf);
                uint8_t tw = u8g2.getStrWidth(buf);
                u8g2.drawStr((DISPLAY_WIDTH - tw) / 2, VALUE_HEIGHT - 3, buf);

                if      (btConnected && timeOffset != 0) u8g2.drawDisc  (124, 4, 3);
                else if (btConnected)                    u8g2.drawCircle(124, 4, 3);
                else                                     u8g2.drawFrame (120, 1, 7, 7);

                u8g2.drawHLine(0, VALUE_HEIGHT, DISPLAY_WIDTH);

                if (dispHistFill >= 2) {
                    float lo = 1e9f, hi = -1e9f;
                    for (uint8_t i = 0; i < dispHistFill; i++) {
                        uint8_t idx = (dispHistIdx + DISP_HIST - dispHistFill + i) % DISP_HIST;
                        lo = min(lo, dispHist[idx]);
                        hi = max(hi, dispHist[idx]);
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
            }  // Ende displayMode

            u8g2.sendBuffer();
        }

        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

void setup() {
    Serial.begin(115200);
    delay(500);

    pinMode(BUTTON_PIN, INPUT_PULLUP);
    Wire.begin();

    if (!u8g2.begin()) {
        while (true) delay(1000);
    }

    loadConfig();
    calibrationFactor = loadFactor();

    scale.begin(HX711_DOUT, HX711_SCK);

    uint32_t t = millis() + 3000;
    while (!scale.is_ready() && millis() < t) delay(200);
    if (!scale.is_ready()) {
        while (true) delay(1000);
    }

    scale.tare();
    scale.set_scale(calibrationFactor);

    offlineWriteIdx = 0;
    offlineSendIdx  = 0;

    sampleQueue      = xQueueCreate(1, sizeof(SampleSnapshot));
    scaleCmdQueue    = xQueueCreate(4, sizeof(ScaleCommand));
    scaleResultQueue = xQueueCreate(4, sizeof(ScaleResult));

    if (!sampleQueue || !scaleCmdQueue || !scaleResultQueue) {
        while (true) delay(1000);
    }

    BT.register_callback([](esp_spp_cb_event_t event, esp_spp_cb_param_t*) {
        if      (event == ESP_SPP_SRV_OPEN_EVT) { btConnected = true; }
        else if (event == ESP_SPP_CLOSE_EVT)    { btConnected = false; timeOffset = 0; }
    });

    if (!BT.begin(BT_DEVICE_NAME)) {
        while (true) delay(1000);
    }

    xTaskCreatePinnedToCore(measureTask,   "Messen",     4096, NULL, 1, NULL, 1);
    xTaskCreatePinnedToCore(btDisplayTask, "BT+Display", 8192, NULL, 1, NULL, 0);
}

void loop() {
    vTaskDelay(portMAX_DELAY);
}