# How to Run On-Device Tests on an Android Device

This guide provides step-by-step instructions for running unit and instrumented integration tests on a connected physical Android device or local emulator.

---

## Prerequisites

1. **Android Device or Emulator**:
   - Physical device with **USB Debugging** enabled in Developer Options.
   - Connected via USB or Wi-Fi debugging (`adb devices` must show your device as `device`).
2. **JDK 17** installed and configured in your shell environment (`JAVA_HOME`).

---

## 1. Verify Connected Device

Run the following terminal command to verify `adb` detects your connected phone:

```bash
adb devices
```

*Example Output:*
```text
List of devices attached
22041219PI    device
```

---

## 2. Run All Unit & On-Device Instrumented Tests

Run the complete test suite (Unit tests + Instrumented tests on device) using the Gradle wrapper:

```bash
./gradlew test connectedCheck
```

---

## 3. Run On-Device Instrumented Tests Only

To run **only** the on-device instrumented tests across all modules (`:core` and `:service`):

```bash
./gradlew connectedCheck
```

---

## 4. Run Specific Module Instrumented Tests

### Run `:core` Engine Instrumented Tests
Tests SQLite FTS5 search, 1,000-doc load benchmark, WebView SPA backend, adversarial security, cancellation propagation, passage selection, and fragment joining:

```bash
./gradlew :core:connectedDebugAndroidTest
```

### Run `:service` HTTP API Server Instrumented Tests
Tests Android KeyStore token management, local loopback `127.0.0.1` server, authentication rejection, resource limits, and all `/v1/*` endpoints:

```bash
./gradlew :service:connectedDebugAndroidTest
```

---

## 5. View Test Reports

After test execution completes, HTML test reports are generated at:
- **`:core` On-Device Report**: `core/build/reports/androidTests/connected/debug/index.html`
- **`:service` On-Device Report**: `service/build/reports/androidTests/connected/debug/index.html`
