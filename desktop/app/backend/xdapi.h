#pragma once

#include <QHash>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QVariantList>
#include <QVariantMap>

#include <functional>

// XD CONSOLE — cliente da API /admin/xd (painel de suporte da staff).
// Só existe de verdade no build xdconsole (CONFIG+=xdconsole); no app normal
// o singleton fica registrado mas nunca aparece.
// Auth: login do SITE (/auth/login + /auth/2fa/verify) — o token do launcher
// não passa no middleware admin (payload diferente), então o XD tem sessão própria.
class XdApi : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(bool loggedIn READ loggedIn NOTIFY loggedInChanged)
    Q_PROPERTY(bool accessDenied READ accessDenied NOTIFY accessDeniedChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorMessageChanged)
    Q_PROPERTY(QString accountLabel READ accountLabel NOTIFY accountLabelChanged)
    Q_PROPERTY(QVariantMap overview READ overview NOTIFY overviewChanged)
    Q_PROPERTY(QVariantList machines READ machines NOTIFY machinesChanged)
    Q_PROPERTY(QVariantMap machineDetail READ machineDetail NOTIFY machineDetailChanged)
    Q_PROPERTY(QVariantList machineHistory READ machineHistory NOTIFY machineDetailChanged)
    Q_PROPERTY(QString serverNow READ serverNow NOTIFY serverNowChanged)

public:
    explicit XdApi(QObject* parent = nullptr);

    bool busy() const { return m_Busy; }
    bool loggedIn() const { return m_LoggedIn; }
    bool accessDenied() const { return m_AccessDenied; }
    QString errorMessage() const { return m_ErrorMessage; }
    QString accountLabel() const { return m_AccountLabel; }
    QVariantMap overview() const { return m_Overview; }
    QVariantList machines() const { return m_Machines; }
    QVariantMap machineDetail() const { return m_MachineDetail; }
    QVariantList machineHistory() const { return m_MachineHistory; }
    QString serverNow() const { return m_ServerNow; }

    Q_INVOKABLE void login(const QString& email, const QString& password);
    Q_INVOKABLE void verifyTwoFactor(const QString& code);
    Q_INVOKABLE void logout();
    Q_INVOKABLE void tryAutoLogin();
    Q_INVOKABLE void refresh(const QString& query, bool onlyRunning);
    Q_INVOKABLE void loadMachineDetail(const QString& machineId);
    Q_INVOKABLE void powerAction(const QString& machineId, const QString& action);

signals:
    void busyChanged();
    void loggedInChanged();
    void accessDeniedChanged();
    void errorMessageChanged();
    void accountLabelChanged();
    void overviewChanged();
    void machinesChanged();
    void machineDetailChanged();
    void serverNowChanged();
    void twoFactorRequired();
    void actionFinished(bool success, QString message);

private:
    using ResponseHandler = std::function<void(int, const QJsonObject&)>;
    void request(const QByteArray& method, const QString& path,
                 const QJsonObject& body, ResponseHandler handler);
    void setBusy(bool busy);
    void setError(const QString& message);
    void saveSession(const QString& token, const QString& email, const QString& name);
    void clearSession();
    void handleXdError(int status, const QJsonObject& root, const QString& fallback);

    QNetworkAccessManager m_Network;
    QString m_Token;
    QString m_TempToken;
    QString m_AccountLabel;
    QString m_ErrorMessage;
    QVariantMap m_Overview;
    QVariantList m_Machines;
    QVariantMap m_MachineDetail;
    QVariantList m_MachineHistory;
    QString m_ServerNow;
    bool m_Busy = false;
    bool m_LoggedIn = false;
    bool m_AccessDenied = false;
};
