#include "sessionorchestrator.h"
#include "launcherapi.h"
#include "computermanager.h"
#include "desktoplaunch.h"
#include "streaming/session.h"

#include <QCoreApplication>
#include <QJsonDocument>
#include <QSettings>
#include <QUrl>

#ifdef Q_OS_WIN32
#include <windows.h>
#endif

SessionOrchestrator::SessionOrchestrator(QObject* parent) : QObject(parent)
{
    m_Poll.setInterval(3000);
    connect(&m_Poll, &QTimer::timeout, this, [this]() {
        if (m_Active && !m_ConnectionRequested)
            m_Launcher->refreshStatus(m_MachineId);
        drive();
    });
    m_HandshakeDeadline.setSingleShot(true);
    m_HandshakeDeadline.setInterval(60000);
    connect(&m_HandshakeDeadline, &QTimer::timeout, this, [this]() {
        reportError(tr("Your machine did not respond. Please try connecting again."));
    });
    connect(QCoreApplication::instance(), &QCoreApplication::aboutToQuit, this, &SessionOrchestrator::cancel);
}

SessionOrchestrator::~SessionOrchestrator()
{
    stopPreparation();
    if (m_Cancellation)
        m_Cancellation->store(true);
    if (m_Session)
        m_Session->cancel();
}

bool SessionOrchestrator::reducedMotion() const
{
    if (QSettings().value(QStringLiteral("accessibility/reduceMotion"), false).toBool())
        return true;
#ifdef Q_OS_WIN32
    BOOL enabled = TRUE;
    if (SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &enabled, 0))
        return !enabled;
#endif
    return false;
}

void SessionOrchestrator::initialize(LauncherApi* launcher, ComputerManager* computers)
{
    if (m_Launcher)
        return;
    m_Launcher = launcher;
    m_Computers = computers;
    connect(launcher, &LauncherApi::machineStatusReceived, this,
            [this](const QString& machineId, const QJsonObject& root) {
        if (!m_Active || m_ConnectionRequested || machineId != m_MachineId)
            return;
        try {
            m_Status = LauncherJson::parseStatus(QJsonDocument(root).toJson(QJsonDocument::Compact));
            m_StatusReadFailures = 0;
            const QJsonObject texts = root.value(QStringLiteral("bootTexts")).toObject();
            if (!texts.isEmpty())
                m_BootTexts = texts.toVariantMap();
            syncStatus();
        }
        catch (const std::exception&) {
            reportError(tr("Invalid status response. Please try again."));
        }
    });
    connect(launcher, &LauncherApi::busyChanged, this, [this]() {
        emit changed();
        QTimer::singleShot(0, this, &SessionOrchestrator::drive);
    });
    connect(launcher, &LauncherApi::connectionReady, this, &SessionOrchestrator::prepareHost);
    connect(launcher, &LauncherApi::machineRequestFailed, this,
            [this](const QString& machineId, const QString& message, const QString& code, bool retryableStatus) {
        if (!m_Active || machineId != m_MachineId || m_Account != m_Launcher->accountGeneration())
            return;
        if (retryableStatus && !m_ConnectionRequested && m_StatusReadFailures < 2) {
            ++m_StatusReadFailures;
            qWarning() << "Retrying machine status read:" << code << m_StatusReadFailures;
            m_StatusText = tr("Connection interrupted. Retrying machine status...");
            emit changed();
            return;
        }
        reportError(message);
    });
    connect(launcher, &LauncherApi::accountChanging, this, [this]() {
        cancel();
        m_BackgroundMachineId.clear();
        emit changed();
    });
    connect(computers, &ComputerManager::sessionHostPrepared, this, &SessionOrchestrator::hostPrepared);
    connect(computers, &ComputerManager::sessionPairingCompleted, this,
            [this](quint64 attempt, NvComputer* computer, const QString& error) {
        if (!m_Active || attempt != m_Attempt || computer != m_Computer)
            return;
        if (!error.isEmpty()) {
            qWarning() << "Desktop pairing failed:" << error;
            reportError(tr("Automatic pairing failed. Please try again."));
            return;
        }
        m_FreshlyPaired = true;
        emit pinAuthenticated();
        m_WaitingForApps = true;
        setPhase(QStringLiteral("authorizing"), tr("Connecting securely..."));
        tryDesktop();
    });
    connect(computers, &ComputerManager::computerStateChanged, this, [this](NvComputer* computer) {
        if (m_Active && computer == m_Computer && m_WaitingForApps)
            tryDesktop();
    });
}

void SessionOrchestrator::startDesktopSession()
{
    if (!m_Launcher || !m_Launcher->loggedIn() || busy() || m_Launcher->busy())
        return;

    QString machineId = m_Launcher->selectedMachineId();
    if (machineId.isEmpty())
        machineId = m_Launcher->statusMachineId();
    if (machineId.isEmpty() && m_Launcher->machines().size() == 1)
        machineId = m_Launcher->machines().first().toMap().value(QStringLiteral("id")).toString();
    beginSession(machineId);
}

void SessionOrchestrator::beginSession(const QString& machineId)
{
    if (!m_Launcher || !m_Launcher->loggedIn() || busy() || m_Launcher->busy()) return;
    if (machineId.isEmpty()) {
        emit errorOccurred(tr("Select a machine before connecting."));
        return;
    }

    m_MachineId = machineId;
    m_BackgroundMachineId.clear();
    m_Launcher->stopObservingMachineStartup(machineId);
    m_Launcher->cancelConnectionRequests();
    m_Account = m_Launcher->accountGeneration();
    m_Cancellation = std::make_shared<std::atomic_bool>(false);
    ++m_Attempt;
    m_Active = true;
    m_JoinRequested = false;
    m_Status = LauncherStatus();
    m_StatusReadFailures = 0;
    m_BootTexts.clear();
    m_ConnectionRequested = false;
    m_WaitingForApps = false;
    m_FreshlyPaired = false;
    m_Computer = nullptr;
    // The full-screen loader takes focus from the dashboard, whose idle timer
    // may stop polling during a long queue. Keep our own reference until launch.
    m_Computers->startPolling();
    m_OwnsPolling = true;
    m_Launcher->setSelectedMachineId(m_MachineId);
    setPhase(QStringLiteral("allocating"), tr("Finding your machine..."));
    m_Poll.start();
    m_Launcher->refreshStatus(m_MachineId);
}

bool SessionOrchestrator::canBackground() const
{
    return m_Active && !m_ConnectionRequested && m_Launcher && m_Launcher->loggedIn() &&
            m_Account == m_Launcher->accountGeneration() && !m_Launcher->queueJoinPending(m_MachineId) &&
            (m_Status.state == QStringLiteral("queued") || m_Status.state == QStringLiteral("starting"));
}

bool SessionOrchestrator::followInBackground()
{
    if (!canBackground()) return false;
    if (!m_Launcher->observeMachineStartup(m_MachineId, m_Account)) {
        const QString message = tr("Enable machine-ready notifications in Settings before following in the background.");
        m_StatusText = message;
        emit changed();
        emit errorOccurred(message);
        return false;
    }
    m_BackgroundMachineId = m_MachineId;
    m_Active = false;
    m_JoinRequested = false;
    m_Cancellation->store(true);
    ++m_Attempt;
    stopPreparation();
    setPhase(QStringLiteral("backgrounded"), tr("Following startup in the background. We will notify you when your machine is ready."));
    return true;
}

void SessionOrchestrator::resumeConnection(const QString& machineId)
{
    const QString target = machineId.isEmpty() ? m_BackgroundMachineId : machineId;
    if (!target.isEmpty()) beginSession(target);
}

void SessionOrchestrator::setPhase(const QString& phase, const QString& text)
{
    m_Phase = phase;
    m_StatusText = text;
    emit changed();
}

void SessionOrchestrator::syncStatus()
{
    if (!m_Active || m_ConnectionRequested) {
        drive();
        return;
    }
    const QVariantMap texts = m_BootTexts;
    const auto copy = [&texts](const QString& key, const QString& fallback) {
        const QString value = texts.value(key).toString().trimmed();
        return value.isEmpty() ? fallback : value;
    };
    if (m_Status.state == QStringLiteral("queued")) {
        const int position = m_Status.queuePosition;
        const int total = m_Status.queueTotal;
        QString text = position > 0
                ? copy(QStringLiteral("queueTitle"), tr("You are #%1 in queue").arg(position))
                : copy(QStringLiteral("queueSearching"), tr("Finding your machine..."));
        text.replace(QStringLiteral("{position}"), QString::number(position));
        text.replace(QStringLiteral("{total}"), QString::number(total));
        setPhase(QStringLiteral("queued"), text);
        emit queueUpdated(position, total);
    }
    else if (m_Status.state == QStringLiteral("starting")) {
        const QString detail = texts.value(QStringLiteral("phases")).toMap()
                .value(m_Status.creationPhase).toString().trimmed();
        const bool entering = m_Phase != QStringLiteral("booting");
        setPhase(QStringLiteral("booting"), detail.isEmpty()
                 ? copy(QStringLiteral("bootingTitle"), tr("Booting your machine...")) : detail);
        if (entering)
            emit machineBooting();
    }
    else if (m_Status.state == QStringLiteral("ending")) {
        reportError(tr("This session is ending. Wait for it to finish before connecting again."));
        return;
    }
    drive();
}

void SessionOrchestrator::drive()
{
    if (!m_Launcher || m_Launcher->busy())
        return;
    if (m_CancelQueued) return;
    if (!m_Active || m_ConnectionRequested || m_StatusReadFailures > 0)
        return;
    if (m_Status.state == QStringLiteral("ready") &&
        (m_Status.machineId.isEmpty() || m_Status.machineId == m_MachineId)) {
        m_ConnectionRequested = true;
        setPhase(QStringLiteral("connecting"), tr("Connecting securely..."));
        m_HandshakeDeadline.start();
        m_Launcher->requestConnection(m_MachineId);
    }
    else if (m_Status.state == QStringLiteral("idle") && !m_JoinRequested) {
        m_JoinRequested = true;
        m_Launcher->joinQueue(m_MachineId);
    }
}

void SessionOrchestrator::prepareHost(const QString& address, const QString& machineId, const QString& name)
{
    if (!m_Active || !m_ConnectionRequested || machineId != m_MachineId)
        return;
    const QUrl url = QUrl::fromUserInput(QStringLiteral("moonlight://") + address);
    if (!url.isValid() || url.host().isEmpty()) {
        reportError(tr("Your machine did not respond. Please try connecting again."));
        return;
    }
    m_Address = address;
    m_Name = name;
    m_Computers->addNewHost(NvAddress(url.host(), url.port(47989)), false, NvAddress(),
                            machineId, QStringLiteral("launcher"), m_Cancellation, m_Attempt);
}

void SessionOrchestrator::hostPrepared(quint64 attempt, bool success)
{
    if (!m_Active || attempt != m_Attempt)
        return;
    const int index = m_Computers->findComputerIndexByAddress(m_Address);
    const auto hosts = m_Computers->getComputers();
    if (!success || index < 0 || index >= hosts.size()) {
        reportError(tr("Your machine did not respond. Please try connecting again."));
        return;
    }
    m_Computer = hosts[index];
    if (!m_Name.isEmpty())
        m_Computers->renameHost(m_Computer, m_Name);
    bool paired;
    {
        QReadLocker lock(&m_Computer->lock);
        paired = m_Computer->pairState == NvComputer::PS_PAIRED;
    }
    if (paired) {
        emit pinAuthenticated();
        m_WaitingForApps = true;
        tryDesktop();
    }
    else {
        setPhase(QStringLiteral("pairing"), tr("Authenticating your device..."));
        const QString pin = m_Computers->generatePinString();
        m_Computers->pairHost(m_Computer, pin, m_Cancellation, m_Attempt);
        m_Launcher->submitPairPinForMachine(pin, m_MachineId);
    }
}

void SessionOrchestrator::tryDesktop()
{
    if (!m_Active || !m_WaitingForApps || m_Session)
        return;
    QReadLocker lock(&m_Computer->lock);
    if (m_Computer->pairState != NvComputer::PS_PAIRED || m_Computer->appList.isEmpty())
        return;
    const int index = DesktopLaunch::appIndex(m_Computer->appList);
    if (index < 0) {
        lock.unlock();
        reportError(tr("Desktop is not available on this machine. Please contact support."));
        return;
    }
    NvApp app = m_Computer->appList[index];
    if (m_Computer->currentGameId != 0 && m_Computer->currentGameId != app.id) {
        lock.unlock();
        reportError(tr("Another app is running on this machine. Close it before opening Desktop."));
        return;
    }
    auto computer = std::make_shared<NvComputer>(*m_Computer);
    lock.unlock();
    m_WaitingForApps = false;
    stopPreparation();
    m_Session = new Session(computer, app, m_FreshlyPaired);
    m_Session->initializeStreamControls(m_Launcher, m_Account);
    Session* session = m_Session;
    const quint64 attempt = m_Attempt;
    connect(session, &Session::connectionStarted, this, [this, session, attempt]() {
        if (m_Active && m_Session == session && m_Attempt == attempt)
            setPhase(QStringLiteral("video"), tr("Waiting for the first video frame..."));
    });
    connect(session, &Session::firstFramePresented, this, [this, session, attempt]() {
        if (!m_Active || m_Session != session || m_Attempt != attempt)
            return;
        setPhase(QStringLiteral("streaming"), tr("Connected"));
        emit firstFrameReceived();
    });
    connect(session, &Session::sessionFinished, this, [this, session](int) {
        if (m_Session == session)
            finish();
    });
    connect(session, &Session::readyForDeletion, this, [this, session]() {
        session->deleteLater();
        if (m_Session == session) {
            m_Session = nullptr;
            emit changed();
        }
    });
    setPhase(QStringLiteral("connecting"), tr("Connecting securely..."));
    emit sessionReady(m_Session);
}

void SessionOrchestrator::cancel()
{
    if (!m_Active)
        return;
    m_CancelQueued = !m_ConnectionRequested && m_JoinRequested &&
            (m_Status.state == QStringLiteral("queued") || m_Launcher->busy());
    m_Active = false;
    if (m_CancelQueued) {
        m_Launcher->cancelQueuedAttempt(m_MachineId, *this, [this]() {
            m_CancelQueued = false;
            emit changed();
        });
    }
    stopPreparation();
    m_Cancellation->store(true);
    ++m_Attempt;
    m_Launcher->cancelConnectionRequests();
    if (m_Session)
        m_Session->cancel();
    setPhase(QStringLiteral("cancelled"), QString());
    drive();
}

void SessionOrchestrator::completeReveal(bool activate)
{
    if (m_Active && m_Session && m_Phase == QStringLiteral("streaming"))
        m_Session->completeReveal(activate);
}

void SessionOrchestrator::reportError(const QString& message)
{
    if (!m_Active)
        return;
    cancel();
    emit errorOccurred(message);
}

void SessionOrchestrator::finish()
{
    m_Active = false;
    stopPreparation();
    if (m_Cancellation)
        m_Cancellation->store(true);
    m_Launcher->cancelConnectionRequests();
    setPhase(QStringLiteral("idle"), QString());
}

void SessionOrchestrator::stopPreparation()
{
    m_Poll.stop();
    m_HandshakeDeadline.stop();
    if (m_OwnsPolling) {
        m_OwnsPolling = false;
        if (m_Computers)
            m_Computers->stopPollingAsync();
    }
}
