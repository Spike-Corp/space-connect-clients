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
    // Falso enquanto ainda não sabemos (undecided) ou o usuário realmente não
    // tem nenhuma VM dedicada provisionada. Sem isso, o app só sabia "joinQueue",
    // e usuários sem VM ficavam presos num loop de fila (o backend nunca cria
    // VM a partir da fila — só o /create-machine faz isso, igual ao site).
    Q_PROPERTY(bool hasMachine READ hasMachine NOTIFY machinesChanged)
    Q_PROPERTY(bool machinesLoaded READ machinesLoaded NOTIFY machinesChanged)

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
    bool hasMachine() const { return m_HasMachine; }
    bool machinesLoaded() const { return m_MachinesLoaded; }

    Q_INVOKABLE void login(const QString& email, const QString& password);
    Q_INVOKABLE void verifyTwoFactor(const QString& code);
    Q_INVOKABLE void refreshStatus();
    Q_INVOKABLE void joinQueue();
    Q_INVOKABLE void leaveQueue();
    Q_INVOKABLE void requestConnection();
    Q_INVOKABLE void endSession();
    // Provisiona a VM dedicada do usuário (self-service), igual ao botão
    // "Criar VM" do site. Necessário antes de conseguir entrar na fila —
    // a fila nunca cria VM sozinha.
    Q_INVOKABLE void createMachine(const QString& password);
    Q_INVOKABLE void fetchMachines();
    Q_INVOKABLE void submitPairPin(const QString& pin);
    Q_INVOKABLE void uploadFileToVm(const QString& filePath);
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
    void machinesChanged();
    void loginSucceeded();
    void twoFactorRequired();
    void connectionReady(QString address);
    void fileUploadSucceeded(QString fileName);

private:
    using ResponseHandler = std::function<void(int, const QJsonObject&)>;

    void request(
        const QByteArray& method,
        const QString& path,
        const QJsonObject& body,
        bool authenticated,
        ResponseHandler handler);
    void attemptLogin(const QString& email, const QString& password, const QString& recaptchaToken);
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
    bool m_HasMachine = false;
    bool m_MachinesLoaded = false;
};
