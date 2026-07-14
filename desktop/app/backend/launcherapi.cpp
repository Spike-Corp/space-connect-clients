#include "launcherapi.h"

#include "launcherjson.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>
#include <QSysInfo>
#include <QUuid>

namespace {
const QUrl kApiBase(QStringLiteral("https://spacecloud.gg/api/launcher/v1/"));

QJsonObject errorObject(const QJsonObject& root)
{
    return root.value(QStringLiteral("error")).toObject();
}
}

LauncherApi::LauncherApi(QObject* parent)
    : QObject(parent)
{
    m_RefreshTimer.setSingleShot(true);
    connect(&m_RefreshTimer, &QTimer::timeout, this, &LauncherApi::refreshTokens);
}

void LauncherApi::login(const QString& email, const QString& password)
{
    if (m_Busy)
        return;

    setBusy(true);
    setError(QString());

    const QString trimmedEmail = email.trimmed();
    m_Recaptcha.fetch(QStringLiteral("login"),
            [this, trimmedEmail, password](const QString& recaptchaToken) {
                QJsonObject body{
                    {QStringLiteral("email"), trimmedEmail},
                    {QStringLiteral("password"), password},
                    {QStringLiteral("recaptchaToken"), recaptchaToken},
                    {QStringLiteral("deviceId"), deviceId()},
                    {QStringLiteral("name"), QSysInfo::prettyProductName()},
                    {QStringLiteral("platform"), platformName()},
                    {QStringLiteral("appVersion"), QCoreApplication::applicationVersion()},
                };
                request("POST", QStringLiteral("auth/login"), body, false,
                        [this](int status, const QJsonObject& root) {
                            setBusy(false);
                            handleAuthResponse(status, root);
                        });
            });
}

void LauncherApi::verifyTwoFactor(const QString& code)
{
    if (m_Busy || m_TempToken.isEmpty())
        return;

    setBusy(true);
    setError(QString());
    request("POST", QStringLiteral("auth/2fa"),
            QJsonObject{
                {QStringLiteral("tempToken"), m_TempToken},
                {QStringLiteral("code"), code.trimmed()},
            },
            false,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                handleAuthResponse(status, root);
            });
}

void LauncherApi::handleAuthResponse(int status, const QJsonObject& root)
{
    if (status >= 200 && status < 300) {
        m_AccessToken = root.value(QStringLiteral("accessToken")).toString();
        m_RefreshToken = root.value(QStringLiteral("refreshToken")).toString();
        const QJsonObject user = root.value(QStringLiteral("user")).toObject();
        if (!user.value(QStringLiteral("email")).toString().isEmpty()) {
            m_Email = user.value(QStringLiteral("email")).toString();
            emit emailChanged();
        }
        m_TempToken.clear();
        m_LoggedIn = !m_AccessToken.isEmpty() && !m_RefreshToken.isEmpty();
        emit loggedInChanged();
        scheduleRefresh(root.value(QStringLiteral("accessTokenExpiresIn")).toInt(900));
        emit loginSucceeded();
        refreshStatus();
        return;
    }

    const QJsonObject error = errorObject(root);
    const QString code = error.value(QStringLiteral("code")).toString();
    if (code == QStringLiteral("TWO_FACTOR_REQUIRED")) {
        m_TempToken = error.value(QStringLiteral("tempToken")).toString();
        emit twoFactorRequired();
        return;
    }
    setError(error.value(QStringLiteral("message")).toString(
        QStringLiteral("Não foi possível entrar na SpaceCloud")));
}

void LauncherApi::refreshStatus()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    request("GET", QStringLiteral("status"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300) {
                    applyStatus(root);
                }
                else if (status == 401) {
                    refreshTokens();
                }
                else {
                    setError(errorObject(root).value(QStringLiteral("message")).toString(
                        QStringLiteral("Não foi possível atualizar o status")));
                }
            });
}

void LauncherApi::joinQueue()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    request("POST", QStringLiteral("queue"),
            QJsonObject{
                {QStringLiteral("requestedHours"), 24},
                {QStringLiteral("provider"), QStringLiteral("proxmox")},
            },
            true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300)
                    applyStatus(root);
                else
                    setError(errorObject(root).value(QStringLiteral("message")).toString());
            });
}

void LauncherApi::leaveQueue()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    request("DELETE", QStringLiteral("queue"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300)
                    applyStatus(root);
                else
                    setError(errorObject(root).value(QStringLiteral("message")).toString());
            });
}

void LauncherApi::requestConnection()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    request("GET", QStringLiteral("connection"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status < 200 || status >= 300) {
                    setError(errorObject(root).value(QStringLiteral("message")).toString());
                    return;
                }
                try {
                    const LauncherConnection connection =
                        LauncherJson::parseConnection(QJsonDocument(root).toJson(QJsonDocument::Compact));
                    if (connection.host.isEmpty() || connection.port <= 0)
                        throw std::runtime_error("Connection unavailable");
                    emit connectionReady(connection.host + QStringLiteral(":")
                                         + QString::number(connection.port));
                }
                catch (const std::exception&) {
                    setError(QStringLiteral("Conexão Moonlight ainda não disponível"));
                }
            });
}

void LauncherApi::endSession()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    request("POST", QStringLiteral("session/end"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300) {
                    m_State = QStringLiteral("ending");
                    emit statusChanged();
                }
                else {
                    setError(errorObject(root).value(QStringLiteral("message")).toString());
                }
            });
}

void LauncherApi::submitPairPin(const QString& pin)
{
    if (!m_LoggedIn || pin.size() != 4)
        return;
    sendPairAttempt(pin, 0);
}

void LauncherApi::sendPairAttempt(const QString& pin, int attempt)
{
    request("POST", QStringLiteral("pair"),
            QJsonObject{{QStringLiteral("pin"), pin}},
            true,
            [this, pin, attempt](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300
                    && root.value(QStringLiteral("paired")).toBool()) {
                    return;
                }
                if (attempt < 7) {
                    QTimer::singleShot(500, this, [this, pin, attempt]() {
                        sendPairAttempt(pin, attempt + 1);
                    });
                }
            });
}

void LauncherApi::logout()
{
    m_RefreshTimer.stop();
    m_AccessToken.clear();
    m_RefreshToken.clear();
    m_TempToken.clear();
    m_Email.clear();
    m_LoggedIn = false;
    emit emailChanged();
    emit loggedInChanged();
}

void LauncherApi::refreshTokens()
{
    if (m_RefreshToken.isEmpty())
        return;

    request("POST", QStringLiteral("auth/refresh"),
            QJsonObject{
                {QStringLiteral("refreshToken"), m_RefreshToken},
                {QStringLiteral("deviceId"), deviceId()},
            },
            false,
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    m_AccessToken = root.value(QStringLiteral("accessToken")).toString();
                    m_RefreshToken = root.value(QStringLiteral("refreshToken")).toString();
                    scheduleRefresh(root.value(QStringLiteral("accessTokenExpiresIn")).toInt(900));
                    refreshStatus();
                }
                else {
                    logout();
                    setError(QStringLiteral("Sua sessão expirou. Entre novamente."));
                }
            });
}

void LauncherApi::scheduleRefresh(int expiresInSeconds)
{
    const int refreshInMs = qMax(60, expiresInSeconds - 60) * 1000;
    m_RefreshTimer.start(refreshInMs);
}

void LauncherApi::applyStatus(const QJsonObject& root)
{
    try {
        const LauncherStatus status =
            LauncherJson::parseStatus(QJsonDocument(root).toJson(QJsonDocument::Compact));
        m_State = status.state;
        m_QueuePosition = status.queuePosition;
        m_QueueTotal = status.queueTotal;
        m_PlanSlug = status.planSlug;
        m_MachineName = status.machineName;
        m_RemainingMs = status.remainingMs;
        setError(QString());
        emit statusChanged();
    }
    catch (const std::exception&) {
        setError(QStringLiteral("Resposta inválida da SpaceCloud"));
    }
}

void LauncherApi::request(
    const QByteArray& method,
    const QString& path,
    const QJsonObject& body,
    bool authenticated,
    ResponseHandler handler)
{
    QNetworkRequest request(kApiBase.resolved(QUrl(path)));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("User-Agent", "SpaceConnect-Qt/0.1.0");
    if (authenticated && !m_AccessToken.isEmpty())
        request.setRawHeader("Authorization", "Bearer " + m_AccessToken.toUtf8());

    QNetworkReply* reply;
    if (method == "GET")
        reply = m_Network.get(request);
    else if (method == "DELETE")
        reply = m_Network.sendCustomRequest(request, "DELETE");
    else
        reply = m_Network.post(request, QJsonDocument(body).toJson(QJsonDocument::Compact));

    connect(reply, &QNetworkReply::finished, this, [reply, handler = std::move(handler)]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QJsonParseError parseError;
        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll(), &parseError);
        QJsonObject root;
        if (parseError.error == QJsonParseError::NoError && document.isObject())
            root = document.object();
        handler(status > 0 ? status : 503, root);
        reply->deleteLater();
    });
}

void LauncherApi::setBusy(bool busy)
{
    if (m_Busy == busy)
        return;
    m_Busy = busy;
    emit busyChanged();
}

void LauncherApi::setError(const QString& message)
{
    if (m_ErrorMessage == message)
        return;
    m_ErrorMessage = message;
    emit errorMessageChanged();
}

QString LauncherApi::deviceId() const
{
    QSettings settings;
    QString id = settings.value(QStringLiteral("launcher/deviceId")).toString();
    if (id.isEmpty()) {
        id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        settings.setValue(QStringLiteral("launcher/deviceId"), id);
    }
    return id;
}

QString LauncherApi::platformName()
{
#if defined(Q_OS_WIN)
    return QStringLiteral("windows");
#elif defined(Q_OS_MACOS)
    return QStringLiteral("macos");
#else
    return QStringLiteral("linux");
#endif
}
