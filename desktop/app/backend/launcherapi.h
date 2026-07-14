#pragma once

#include "recaptchafetcher.h"

#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QTimer>

#include <functional>

class LauncherApi : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(bool loggedIn READ loggedIn NOTIFY loggedInChanged)
    Q_PROPERTY(QString email READ email NOTIFY emailChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorMessageChanged)
    Q_PROPERTY(QString state READ state NOTIFY statusChanged)
    Q_PROPERTY(int queuePosition READ queuePosition NOTIFY statusChanged)
    Q_PROPERTY(int queueTotal READ queueTotal NOTIFY statusChanged)
    Q_PROPERTY(QString planSlug READ planSlug NOTIFY statusChanged)
    Q_PROPERTY(QString machineName READ machineName NOTIFY statusChanged)
    Q_PROPERTY(qint64 remainingMinutes READ remainingMinutes NOTIFY statusChanged)

public:
    explicit LauncherApi(QObject* parent = nullptr);

    bool busy() const { return m_Busy; }
    bool loggedIn() const { return m_LoggedIn; }
    QString email() const { return m_Email; }
    QString errorMessage() const { return m_ErrorMessage; }
    QString state() const { return m_State; }
    int queuePosition() const { return m_QueuePosition; }
    int queueTotal() const { return m_QueueTotal; }
    QString planSlug() const { return m_PlanSlug; }
    QString machineName() const { return m_MachineName; }
    qint64 remainingMinutes() const { return m_RemainingMs / 60000; }

    Q_INVOKABLE void login(const QString& email, const QString& password);
    Q_INVOKABLE void verifyTwoFactor(const QString& code);
    Q_INVOKABLE void refreshStatus();
    Q_INVOKABLE void joinQueue();
    Q_INVOKABLE void leaveQueue();
    Q_INVOKABLE void requestConnection();
    Q_INVOKABLE void endSession();
    Q_INVOKABLE void submitPairPin(const QString& pin);
    Q_INVOKABLE void logout();
    // Bitrate ceiling (Kbps) cached from the last connection reported by the backend.
    // 0 when never received; callers should fall back to a local heuristic.
    Q_INVOKABLE int maxBitrateKbps() const;
    Q_INVOKABLE int recommendedBitrateKbps() const;

signals:
    void busyChanged();
    void loggedInChanged();
    void emailChanged();
    void errorMessageChanged();
    void statusChanged();
    void loginSucceeded();
    void twoFactorRequired();
    void connectionReady(QString address);

private:
    using ResponseHandler = std::function<void(int, const QJsonObject&)>;

    void request(
        const QByteArray& method,
        const QString& path,
        const QJsonObject& body,
        bool authenticated,
        ResponseHandler handler);
    void handleAuthResponse(int status, const QJsonObject& root);
    void scheduleRefresh(int expiresInSeconds);
    void refreshTokens();
    void applyStatus(const QJsonObject& root);
    void setBusy(bool busy);
    void setError(const QString& message);
    void sendPairAttempt(const QString& pin, int attempt);
    QString deviceId() const;
    static QString platformName();

    QNetworkAccessManager m_Network;
    QTimer m_RefreshTimer;
    RecaptchaFetcher m_Recaptcha;
    QString m_AccessToken;
    QString m_RefreshToken;
    QString m_TempToken;
    QString m_Email;
    QString m_ErrorMessage;
    QString m_State = QStringLiteral("idle");
    QString m_PlanSlug;
    QString m_MachineName;
    int m_QueuePosition = 0;
    int m_QueueTotal = 0;
    qint64 m_RemainingMs = 0;
    bool m_Busy = false;
    bool m_LoggedIn = false;
};
