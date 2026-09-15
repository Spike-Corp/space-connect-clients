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

#include <stdexcept>

namespace {
const QUrl kApiBase(QStringLiteral("https://spacecloud.gg/api/launcher/v1/"));
const QUrl kCrimsonApiBase(qEnvironmentVariable(
    "SPACE_CONNECT_CRIMSON_BETA_API",
    "https://gamingflix.space/crimson-api/v1/"));

QJsonObject errorObject(const QJsonObject& root)
{
    return root.value(QStringLiteral("error")).toObject();
}

QUrl crimsonUrl(const QString& path)
{
    return kCrimsonApiBase.resolved(QUrl(path));
}
}

LauncherApi::LauncherApi(QObject* parent)
    : QObject(parent)
{
    m_RefreshTimer.setSingleShot(true);
    connect(&m_RefreshTimer, &QTimer::timeout, this, &LauncherApi::refreshTokens);
    m_CrimsonPollTimer.setInterval(1500);
    connect(&m_CrimsonPollTimer, &QTimer::timeout, this, &LauncherApi::pollCrimsonSession);
}

void LauncherApi::login(const QString& email, const QString& password)
{
    if (m_Busy)
        return;

    setBusy(true);
    setError(QString());

    // Try logging in directly first, without minting a reCAPTCHA token — most
    // logins are legitimate and don't need one, so this avoids popping open a
    // browser window on every single attempt. The backend only replies with
    // RECAPTCHA_REQUIRED (handled in attemptLogin() below) once this e-mail has
    // shown recent suspicious activity, at which point we step up and fetch one.
    attemptLogin(email.trimmed(), password, QString());
}

void LauncherApi::attemptLogin(const QString& email, const QString& password, const QString& recaptchaToken)
{
    QJsonObject body{
        {QStringLiteral("email"), email},
        {QStringLiteral("password"), password},
        {QStringLiteral("deviceId"), deviceId()},
        {QStringLiteral("name"), QSysInfo::prettyProductName()},
        {QStringLiteral("platform"), platformName()},
        {QStringLiteral("appVersion"), QCoreApplication::applicationVersion()},
    };
    if (!recaptchaToken.isEmpty()) {
        body.insert(QStringLiteral("recaptchaToken"), recaptchaToken);
    }

    request("POST", QStringLiteral("auth/login"), body, false,
            [this, email, password](int status, const QJsonObject& root) {
                const QJsonObject error = errorObject(root);
                if (status == 400
                        && error.value(QStringLiteral("code")).toString() == QStringLiteral("RECAPTCHA_REQUIRED")) {
                    // Step up: this is the only path that opens a browser window.
                    m_Recaptcha.fetch(QStringLiteral("login"),
                            [this, email, password](const QString& recaptchaToken) {
                                if (recaptchaToken.isEmpty()) {
                                    setBusy(false);
                                    setError(QStringLiteral(
                                            "Não foi possível concluir a verificação de segurança. Tente novamente."));
                                    return;
                                }
                                attemptLogin(email, password, recaptchaToken);
                            });
                    return;
                }
                setBusy(false);
                handleAuthResponse(status, root);
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
                    if (connection.maxBitrateKbps > 0) {
                        QSettings settings;
                        settings.setValue(QStringLiteral("launchermaxbitratekbps"),
                                          connection.maxBitrateKbps);
                        settings.setValue(QStringLiteral("launcherrecommendedbitratekbps"),
                                          connection.recommendedBitrateKbps);
                    }
                    emit connectionReady(connection.host + QStringLiteral(":")
                                         + QString::number(connection.port));
                }
                catch (const std::exception&) {
                    setError(QStringLiteral("Conexão Moonlight ainda não disponível"));
                }
            });
}

int LauncherApi::maxBitrateKbps() const
{
    QSettings settings;
    return settings.value(QStringLiteral("launchermaxbitratekbps"), 0).toInt();
}

int LauncherApi::recommendedBitrateKbps() const
{
    QSettings settings;
    return settings.value(QStringLiteral("launcherrecommendedbitratekbps"), 0).toInt();
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

void LauncherApi::startCrimsonDesert()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    setError(QString());
    m_CrimsonState = QStringLiteral("provisioning");
    m_CrimsonDownloadPercent = 0;
    emit crimsonStateChanged();

    QNetworkRequest request(crimsonUrl(QStringLiteral("games/crimson-desert/start")));
    request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("Authorization", "Bearer " + m_AccessToken.toUtf8());
    QNetworkReply* reply = m_Network.post(
        request,
        QJsonDocument(QJsonObject{
            {QStringLiteral("userId"), m_Email},
            {QStringLiteral("gameId"), QStringLiteral("crimson-desert")},
            {QStringLiteral("vramGb"), 16},
        }).toJson(QJsonDocument::Compact));

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QJsonParseError parseError;
        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll(), &parseError);
        const QJsonObject root = parseError.error == QJsonParseError::NoError && document.isObject()
            ? document.object()
            : QJsonObject();
        reply->deleteLater();
        setBusy(false);
        if (status < 200 || status >= 300) {
            m_CrimsonState = QStringLiteral("error");
            setError(errorObject(root).value(QStringLiteral("message")).toString(
                QStringLiteral("Crimson Desert indisponível neste beta")));
            emit crimsonStateChanged();
            return;
        }
        const QJsonObject data = root.value(QStringLiteral("data")).toObject();
        m_CrimsonSessionId = data.value(QStringLiteral("id")).toString();
        m_CrimsonState = data.value(QStringLiteral("state")).toString(QStringLiteral("provisioning"));
        m_CrimsonDownloadPercent = data.value(QStringLiteral("downloadPercent")).toInt(0);
        emit crimsonStateChanged();
        if (!m_CrimsonSessionId.isEmpty())
            m_CrimsonPollTimer.start();
    });
}

void LauncherApi::pollCrimsonSession()
{
    if (m_CrimsonSessionId.isEmpty())
        return;

    QNetworkRequest request(crimsonUrl(QStringLiteral("sessions/") + m_CrimsonSessionId));
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("Authorization", "Bearer " + m_AccessToken.toUtf8());
    QNetworkReply* reply = m_Network.get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QJsonParseError parseError;
        const QJsonDocument document = QJsonDocument::fromJson(reply->readAll(), &parseError);
        const QJsonObject root = parseError.error == QJsonParseError::NoError && document.isObject()
            ? document.object()
            : QJsonObject();
        reply->deleteLater();
        if (status < 200 || status >= 300) {
            m_CrimsonPollTimer.stop();
            m_CrimsonState = QStringLiteral("error");
            setError(QStringLiteral("Não foi possível acompanhar a sessão Crimson"));
            emit crimsonStateChanged();
            return;
        }
        const QJsonObject data = root.value(QStringLiteral("data")).toObject();
        m_CrimsonState = data.value(QStringLiteral("state")).toString(m_CrimsonState);
        m_CrimsonDownloadPercent = data.value(QStringLiteral("downloadPercent")).toInt(m_CrimsonDownloadPercent);
        emit crimsonStateChanged();
        if (m_CrimsonState == QStringLiteral("ready")) {
            m_CrimsonPollTimer.stop();
            const QJsonObject connection = data.value(QStringLiteral("connection")).toObject();
            const QString host = connection.value(QStringLiteral("host")).toString();
            const int port = connection.value(QStringLiteral("port")).toInt();
            if (!host.isEmpty() && port > 0)
                emit crimsonConnectionReady(host + QStringLiteral(":") + QString::number(port));
        }
        else if (m_CrimsonState == QStringLiteral("error")
                 || m_CrimsonState == QStringLiteral("ended")) {
            m_CrimsonPollTimer.stop();
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
    m_CrimsonPollTimer.stop();
    m_AccessToken.clear();
    m_RefreshToken.clear();
    m_TempToken.clear();
    m_Email.clear();
    m_CrimsonSessionId.clear();
    m_CrimsonState = QStringLiteral("idle");
    m_CrimsonDownloadPercent = 0;
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
