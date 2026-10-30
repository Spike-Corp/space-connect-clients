#pragma once

#include "connectioncancel.h"
#include <QObject>
#include <QPointer>
#include <QTimer>

class LauncherApi;
class ComputerManager;
class NvComputer;
class Session;

class SessionOrchestrator : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool active READ active NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString phase READ phase NOTIFY changed)
    Q_PROPERTY(QString statusText READ statusText NOTIFY changed)
    Q_PROPERTY(bool reducedMotion READ reducedMotion NOTIFY changed)

public:
    explicit SessionOrchestrator(QObject* parent = nullptr);
    ~SessionOrchestrator() override;

    Q_INVOKABLE void initialize(LauncherApi* launcher, ComputerManager* computers);
    Q_INVOKABLE void startDesktopSession();
    Q_INVOKABLE void cancel();
    Q_INVOKABLE void completeReveal();
    Q_INVOKABLE void reportError(const QString& message);

    bool active() const { return m_Active; }
    bool busy() const { return m_Active || m_Session != nullptr || m_CancelQueued; }
    QString phase() const { return m_Phase; }
    QString statusText() const { return m_StatusText; }
    bool reducedMotion() const;

signals:
    void changed();
    void queueUpdated(int position, int total);
    void machineBooting();
    void pinAuthenticated();
    void firstFrameReceived();
    void errorOccurred(QString message);
    void sessionReady(Session* session);

private:
    void syncStatus();
    void drive();
    void prepareHost(const QString& address, const QString& machineId, const QString& name);
    void hostPrepared(quint64 attempt, bool success);
    void tryDesktop();
    void setPhase(const QString& phase, const QString& text);
    void finish();
    void stopPreparation();

    QPointer<LauncherApi> m_Launcher;
    QPointer<ComputerManager> m_Computers;
    NvComputer* m_Computer = nullptr;
    Session* m_Session = nullptr;
    ConnectionCancellation m_Cancellation;
    QTimer m_Poll;
    QTimer m_HandshakeDeadline;
    quint64 m_Attempt = 0;
    bool m_Active = false;
    bool m_JoinRequested = false;
    bool m_ConnectionRequested = false;
    bool m_CancelQueued = false;
    bool m_WaitingForApps = false;
    bool m_FreshlyPaired = false;
    bool m_OwnsPolling = false;
    QString m_MachineId;
    QString m_Address;
    QString m_Name;
    QString m_Phase = QStringLiteral("idle");
    QString m_StatusText;
};
