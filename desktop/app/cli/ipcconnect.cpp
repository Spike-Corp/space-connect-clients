// Space Connect: headless "connect --ipc" action (JSON lines over stdin/stdout).
// Licensed under GPL-3.0 like the rest of the project.

#include "ipcconnect.h"

#include "backend/computermanager.h"
#include "backend/connectioncancel.h"
#include "backend/desktoplaunch.h"
#include "backend/nvhttp.h"
#include "settings/streamingpreferences.h"
#include "streaming/session.h"
#include "streaming/video/ipcstats.h"

#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QMutexLocker>
#include <QRegularExpression>
#include <QTimer>
#include <QWindow>

#include <atomic>
#include <chrono>
#include <thread>

#ifdef Q_OS_WIN
#include <qt_windows.h>
#else
#include <unistd.h>
#endif

namespace CliIpcConnect
{

namespace {

constexpr int HOST_PREPARE_TIMEOUT_MS = 20000;
constexpr int PAIR_TIMEOUT_MS = 60000;
constexpr int APPS_TIMEOUT_MS = 30000;

QMutex s_OutLock;

void writeRaw(const QByteArray& data)
{
#ifdef Q_OS_WIN
    HANDLE h = GetStdHandle(STD_OUTPUT_HANDLE);
    if (h == nullptr || h == INVALID_HANDLE_VALUE)
        return;
    const char* p = data.constData();
    DWORD left = (DWORD)data.size();
    while (left > 0) {
        DWORD written = 0;
        if (!WriteFile(h, p, left, &written, nullptr) || written == 0)
            return;
        p += written;
        left -= written;
    }
#else
    const char* p = data.constData();
    qsizetype left = data.size();
    while (left > 0) {
        ssize_t n = ::write(1, p, (size_t)left);
        if (n <= 0)
            return;
        p += n;
        left -= n;
    }
#endif
}

void emitEvent(const QJsonObject& obj)
{
    QByteArray line = QJsonDocument(obj).toJson(QJsonDocument::Compact);
    line.append('\n');
    QMutexLocker lock(&s_OutLock);
    writeRaw(line);
}

void emitState(const QString& phase)
{
    emitEvent({{"ev", "state"}, {"phase", phase}});
}

void emitWarn(const QString& detail)
{
    emitEvent({{"ev", "warn"}, {"detail", detail}});
}

// Blocks until a full line is available. Returns false on EOF.
bool readLine(QByteArray& line)
{
    line.clear();
    char c;
    for (;;) {
#ifdef Q_OS_WIN
        DWORD n = 0;
        if (!ReadFile(GetStdHandle(STD_INPUT_HANDLE), &c, 1, &n, nullptr) || n == 0)
            return !line.isEmpty();
#else
        ssize_t n = ::read(0, &c, 1);
        if (n <= 0)
            return !line.isEmpty();
#endif
        if (c == '\n')
            return true;
        if (c != '\r')
            line.append(c);
    }
}

}

class Launcher : public QObject
{
public:
    explicit Launcher(QObject* parent) : QObject(parent)
    {
        m_Cancel = std::make_shared<std::atomic_bool>(false);
        m_Timeout.setSingleShot(true);
        connect(&m_Timeout, &QTimer::timeout, this, [this]() { onTimeout(); });
        m_ReaderThread = std::thread([this]() { readerLoop(); });
        m_ReaderThread.detach();
    }

private:
    enum Stage { StWaitRequest, StPrepare, StPair, StApps, StStream, StDone };

    // ---- stdin handling (reader thread) ----
    void readerLoop()
    {
        QByteArray line;
        bool first = true;
        while (readLine(line)) {
            QJsonParseError perr;
            const QJsonObject obj = QJsonDocument::fromJson(line, &perr).object();
            if (perr.error != QJsonParseError::NoError || obj.isEmpty()) {
                emitWarn(QStringLiteral("invalid input line"));
                continue;
            }
            const QString cmd = obj.value("cmd").toString();
            if (first) {
                if (cmd != QStringLiteral("connect")) {
                    QMetaObject::invokeMethod(this, [this]() {
                        fail("BAD_REQUEST", "first command must be connect");
                    }, Qt::QueuedConnection);
                    return;
                }
                first = false;
                QMetaObject::invokeMethod(this, [this, obj]() { begin(obj); }, Qt::QueuedConnection);
            }
            else {
                handleCommand(cmd, obj);
            }
        }
        // stdin closed: the controlling process is gone
        requestQuit(false);
    }

    void handleCommand(const QString& cmd, const QJsonObject& obj)
    {
        if (cmd == QStringLiteral("quit")) {
            requestQuit(obj.value("quit_app").toBool(false));
        }
        else if (cmd == QStringLiteral("overlay")) {
            const bool on = obj.value("on").toBool(false);
            QMutexLocker lock(&m_SessionLock);
            if (m_Session && m_Streaming)
                m_Session->getOverlayManager().setOverlayState(Overlay::OverlayDebug, on);
            else
                m_OverlayPref.store(on);
        }
        else if (cmd == QStringLiteral("set")) {
            emitWarn(QStringLiteral("live setting change not supported: ") + obj.value("key").toString().left(40));
        }
        else {
            emitWarn(QStringLiteral("unknown command"));
        }
    }

    void requestQuit(bool quitApp)
    {
        if (quitApp)
            m_QuitApp.store(true);
        m_QuitRequested.store(true);
        m_Cancel->store(true);
        {
            QMutexLocker lock(&m_SessionLock);
            if (m_Session && m_SessionExecStarted)
                m_Session->cancel();
        }
        // Not streaming yet: let the main thread finish the flow.
        QMetaObject::invokeMethod(this, [this, quitApp]() {
            if (m_Stage != StStream && m_Stage != StDone)
                finish(0, quitApp);
        }, Qt::QueuedConnection);
    }

    // ---- main thread flow ----
    void begin(const QJsonObject& req)
    {
        m_Address = req.value("address").toString().trimmed();
        int port = req.value("port").toInt(DEFAULT_HTTP_PORT);
        const QJsonObject ports = req.value("ports").toObject();
        if (ports.value("http").toInt(0) > 0)
            port = ports.value("http").toInt();
        m_Pin = req.value("pin").toString();
        m_AppName = req.value("app").toString(QStringLiteral("Desktop"));
        if (m_Address.isEmpty() || port <= 0 || port > 65535) {
            fail("HOST_UNREACHABLE", "invalid host address");
            return;
        }
        m_Port = port;
        applySettings(req.value("settings").toObject());

        m_Computers = new ComputerManager(StreamingPreferences::get());
        connect(m_Computers, &ComputerManager::sessionHostPrepared, this,
                [this](quint64 attempt, bool success) { onHostPrepared(attempt, success); });
        connect(m_Computers, &ComputerManager::sessionPairingCompleted, this,
                [this](quint64 attempt, NvComputer* c, const QString& error) { onPaired(attempt, c, error); });
        connect(m_Computers, &ComputerManager::computerStateChanged, this,
                [this](NvComputer* c) { if (c == m_Computer && m_Stage == StApps) tryLaunch(); });
        m_Computers->startPolling();

        m_Stage = StPrepare;
        m_Timeout.start(HOST_PREPARE_TIMEOUT_MS);
        m_Computers->addNewHost(NvAddress(m_Address, (uint16_t)m_Port), false, NvAddress(),
                                QString(), QString(), m_Cancel, 1);
    }

    void applySettings(const QJsonObject& s)
    {
        StreamingPreferences* p = StreamingPreferences::get();
        p->quitAppAfter = false;
        p->showPerformanceOverlay = false;
        if (s.contains("width")) p->width = s.value("width").toInt(p->width);
        if (s.contains("height")) p->height = s.value("height").toInt(p->height);
        if (s.contains("fps")) p->fps = s.value("fps").toInt(p->fps);
        if (s.contains("bitrate_kbps")) p->bitrateKbps = s.value("bitrate_kbps").toInt(p->bitrateKbps);
        p->enableVsync = s.value("vsync").toBool(false);
        p->framePacing = s.value("frame_pacing").toBool(false);
        p->absoluteMouseMode = s.value("absolute_mouse").toBool(false);
        p->enableHdr = s.value("hdr").toBool(false);

        const QString codec = s.value("codec").toString("auto").toLower();
        if (codec == "h264") p->videoCodecConfig = StreamingPreferences::VCC_FORCE_H264;
        else if (codec == "hevc") p->videoCodecConfig = StreamingPreferences::VCC_FORCE_HEVC;
        else if (codec == "av1") p->videoCodecConfig = StreamingPreferences::VCC_FORCE_AV1;
        else p->videoCodecConfig = StreamingPreferences::VCC_AUTO;

        const QString audio = s.value("audio").toString("stereo").toLower();
        if (audio == "5.1") p->audioConfig = StreamingPreferences::AC_51_SURROUND;
        else if (audio == "7.1") p->audioConfig = StreamingPreferences::AC_71_SURROUND;
        else p->audioConfig = StreamingPreferences::AC_STEREO;

        const QString mode = s.value("display_mode").toString("borderless").toLower();
        if (mode == "fullscreen") p->windowMode = StreamingPreferences::WM_FULLSCREEN;
        else if (mode == "windowed") p->windowMode = StreamingPreferences::WM_WINDOWED;
        else p->windowMode = StreamingPreferences::WM_FULLSCREEN_DESKTOP;

        // Competitive mode: never trade latency for smoothness.
        if (s.value("competitive").toBool(false)) {
            p->enableVsync = false;
            p->framePacing = false;
        }
    }

    NvComputer* findComputer()
    {
        const int index = m_Computers->findComputerIndexByAddress(
                    m_Address + QStringLiteral(":") + QString::number(m_Port));
        const auto hosts = m_Computers->getComputers();
        return (index >= 0 && index < hosts.size()) ? hosts[index] : nullptr;
    }

    void onHostPrepared(quint64 attempt, bool success)
    {
        if (attempt != 1 || m_Stage != StPrepare)
            return;
        m_Timeout.stop();
        m_Computer = success ? findComputer() : nullptr;
        if (!m_Computer) {
            fail("HOST_UNREACHABLE", "host did not respond");
            return;
        }
        bool paired;
        {
            QReadLocker lock(&m_Computer->lock);
            paired = m_Computer->pairState == NvComputer::PS_PAIRED;
        }
        if (paired) {
            m_Stage = StApps;
            m_Timeout.start(APPS_TIMEOUT_MS);
            tryLaunch();
            return;
        }
        if (m_Pin.isEmpty()) {
            fail("PAIR_FAILED", "host not paired and no PIN provided");
            return;
        }
        m_Stage = StPair;
        emitState(QStringLiteral("pairing"));
        m_Timeout.start(PAIR_TIMEOUT_MS);
        m_Computers->pairHost(m_Computer, m_Pin, m_Cancel, 1);
    }

    void onPaired(quint64 attempt, NvComputer* computer, const QString& error)
    {
        if (attempt != 1 || m_Stage != StPair || computer != m_Computer)
            return;
        m_Timeout.stop();
        if (!error.isEmpty()) {
            fail("PAIR_FAILED", error);
            return;
        }
        m_Stage = StApps;
        m_Timeout.start(APPS_TIMEOUT_MS);
        tryLaunch();
    }

    void onTimeout()
    {
        switch (m_Stage) {
        case StPrepare: fail("HOST_UNREACHABLE", "timed out reaching host"); break;
        case StPair: fail("PAIR_FAILED", "timed out waiting for pairing"); break;
        case StApps: fail(m_AppsSeen ? "NO_DESKTOP" : "LAUNCH_FAILED",
                          m_AppsSeen ? "requested app not found on host" : "timed out loading app list"); break;
        default: break;
        }
    }

    void tryLaunch()
    {
        if (m_Stage != StApps)
            return;
        NvApp app;
        {
            QReadLocker lock(&m_Computer->lock);
            if (m_Computer->pairState != NvComputer::PS_PAIRED || m_Computer->appList.isEmpty())
                return;
            m_AppsSeen = true;
            int index = -1;
            if (m_AppName.compare(QStringLiteral("Desktop"), Qt::CaseInsensitive) == 0) {
                index = DesktopLaunch::appIndex(m_Computer->appList);
            }
            else {
                for (int i = 0; i < m_Computer->appList.size(); ++i) {
                    if (m_Computer->appList[i].name.compare(m_AppName, Qt::CaseInsensitive) == 0)
                        index = i;
                }
            }
            if (index < 0)
                return; // keep waiting; the timeout reports NO_DESKTOP
            app = m_Computer->appList[index];
            if (m_Computer->currentGameId != 0 && m_Computer->currentGameId != app.id) {
                lock.unlock();
                fail("LAUNCH_FAILED", "another app is running on the host");
                return;
            }
        }
        m_Timeout.stop();
        m_Stage = StStream;
        emitState(QStringLiteral("launching"));
        if (m_QuitRequested.load()) {
            finish(0, false);
            return;
        }

        IpcStats::setEnabled(true);
        Session* session = new Session(m_Computer, app, StreamingPreferences::get());
        connect(session, &Session::firstFramePresented, this, [this]() {
            m_FirstFrame.store(true);
            {
                QMutexLocker lock(&m_SessionLock);
                m_Streaming = true;
                if (m_Session && m_OverlayPref.load())
                    m_Session->getOverlayManager().setOverlayState(Overlay::OverlayDebug, true);
            }
            emitEvent({{"ev", "first_frame"}});
            emitState(QStringLiteral("streaming"));
            startStatsThread();
        }, Qt::DirectConnection);
        connect(session, &Session::stageFailed, this, [this](QString, int errorCode, QString) {
            recordError("STREAM_FAILED", QStringLiteral("stream stage failed (error %1)").arg(errorCode));
        }, Qt::DirectConnection);
        connect(session, &Session::displayLaunchError, this, [this](QString text) {
            recordError(m_FirstFrame.load() ? "STREAM_FAILED" : "LAUNCH_FAILED", text);
        }, Qt::DirectConnection);
        connect(session, &Session::displayLaunchWarning, this, [this](QString text) {
            emitWarn(scrub(text));
        }, Qt::DirectConnection);
        {
            QMutexLocker lock(&m_SessionLock);
            m_Session = session;
        }
        emitState(QStringLiteral("connecting"));
        // Session::exec() blocks (pumping events itself) until the stream ends.
        QTimer::singleShot(0, this, [this, session]() {
            {
                QMutexLocker lock(&m_SessionLock);
                m_SessionExecStarted = true;
                if (m_QuitRequested.load())
                    session->cancel();
            }
            QWindow screenAnchor; // only provides the primary screen to Session
            session->exec(&screenAnchor);
            m_Stats.store(false);
            if (m_ErrorCode.isEmpty() && !m_FirstFrame.load() && !m_QuitRequested.load())
                recordError("STREAM_FAILED", "stream could not be started");
            finish(m_ErrorCode.isEmpty() ? 0 : 1, m_QuitApp.load());
        });
    }

    void startStatsThread()
    {
        if (m_Stats.exchange(true))
            return;
        std::thread([this]() {
            uint64_t lastBytes = IpcStats::totalBytes();
            auto last = std::chrono::steady_clock::now();
            while (m_Stats.load()) {
                std::this_thread::sleep_for(std::chrono::seconds(1));
                if (!m_Stats.load())
                    break;
                const auto now = std::chrono::steady_clock::now();
                const double secs = std::chrono::duration<double>(now - last).count();
                const uint64_t bytes = IpcStats::totalBytes();
                const double kbps = secs > 0 ? (double)(bytes - lastBytes) * 8.0 / 1000.0 / secs : 0;
                last = now;
                lastBytes = bytes;
                const IpcStats::Snapshot s = IpcStats::current();
                emitEvent({{"ev", "stats"},
                           {"fps", s.fps},
                           {"decode_ms", s.decodeMs},
                           {"render_ms", s.renderMs},
                           {"rtt_ms", s.rttMs},
                           {"loss_pct", s.lossPct},
                           {"bitrate_kbps", kbps},
                           {"codec", QString::fromLatin1(s.codec)}});
            }
        }).detach();
    }

    // Removes anything that looks like an address or the pairing PIN.
    QString scrub(QString text) const
    {
        return scrubText(text, m_Pin);
    }

    static QString scrubText(QString text, const QString& pin)
    {
        static const QRegularExpression ipv4(QStringLiteral("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"));
        static const QRegularExpression ipv6(QStringLiteral("\\b([0-9a-fA-F]{0,4}:){3,7}[0-9a-fA-F]{0,4}\\b"));
        text.replace(ipv4, QStringLiteral("<host>"));
        text.replace(ipv6, QStringLiteral("<host>"));
        if (!pin.isEmpty())
            text.replace(pin, QStringLiteral("****"));
        return text.simplified().left(200);
    }

    void recordError(const QString& code, const QString& detail)
    {
        QMutexLocker lock(&m_ErrorLock);
        if (m_ErrorCode.isEmpty()) {
            m_ErrorCode = code;
            m_ErrorDetail = detail;
        }
    }

    void fail(const QString& code, const QString& detail)
    {
        if (m_Stage == StDone)
            return;
        recordError(code, detail);
        finish(1, false);
    }

    void finish(int exitCode, bool quitApp)
    {
        if (m_Stage == StDone)
            return;
        m_Stage = StDone;
        m_Timeout.stop();
        m_Stats.store(false);
        m_Cancel->store(true);
        if (quitApp && m_Computer && m_Computer->currentGameId != 0) {
            try {
                NvHTTP http(m_Computer);
                http.quitApp();
            } catch (...) {
            }
        }
        if (exitCode != 0) {
            QMutexLocker lock(&m_ErrorLock);
            emitEvent({{"ev", "error"}, {"code", m_ErrorCode}, {"detail", scrubText(m_ErrorDetail, m_Pin)}});
        }
        else {
            emitState(QStringLiteral("ended"));
        }
        if (m_Computers)
            m_Computers->stopPollingAsync();
        QCoreApplication::exit(exitCode);
    }

    ConnectionCancellation m_Cancel;
    QTimer m_Timeout;
    std::thread m_ReaderThread;

    ComputerManager* m_Computers = nullptr;
    NvComputer* m_Computer = nullptr;
    Stage m_Stage = StWaitRequest;

    QString m_Address, m_Pin, m_AppName;
    int m_Port = DEFAULT_HTTP_PORT;
    bool m_AppsSeen = false;

    QMutex m_SessionLock;
    Session* m_Session = nullptr;
    bool m_SessionExecStarted = false;
    bool m_Streaming = false;

    QMutex m_ErrorLock;
    QString m_ErrorCode, m_ErrorDetail;

    std::atomic_bool m_QuitRequested{false};
    std::atomic_bool m_QuitApp{false};
    std::atomic_bool m_FirstFrame{false};
    std::atomic_bool m_OverlayPref{false};
    std::atomic_bool m_Stats{false};
};

Launcher* start(QObject* parent)
{
    return new Launcher(parent);
}

}
