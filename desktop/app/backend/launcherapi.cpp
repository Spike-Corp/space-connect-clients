#include "launcherapi.h"

#include "launcherjson.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QFileInfo>
#include <QHttpMultiPart>
#include <QHttpPart>
#include <QJsonArray>
#include <QJsonDocument>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>
#include <QSysInfo>
#include <QUuid>
#include <QUrl>

#include <stdexcept>

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

    QSettings settings;
    m_RememberMe = settings.value(QStringLiteral("auth/rememberMe"), true).toBool();
    m_SavedEmail = settings.value(QStringLiteral("auth/email")).toString();
    if (m_RememberMe && !m_SavedEmail.isEmpty()) {
        const qint64 savedAt = settings.value(QStringLiteral("auth/savedAt"), 0).toLongLong();
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        const qint64 kThirtyDaysMs = 30LL * 24 * 60 * 60 * 1000;
        if (savedAt > 0 && (now - savedAt) < kThirtyDaysMs) {
            m_RefreshToken = settings.value(QStringLiteral("auth/refreshToken")).toString();
            m_Email = m_SavedEmail;
            if (!m_RefreshToken.isEmpty()) {
                m_LoggedIn = true;
                QTimer::singleShot(100, this, &LauncherApi::refreshTokens);
            }
        }
    }
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

        QSettings settings;
        if (m_RememberMe) {
            settings.setValue(QStringLiteral("auth/rememberMe"), true);
            settings.setValue(QStringLiteral("auth/refreshToken"), m_RefreshToken);
            settings.setValue(QStringLiteral("auth/email"), m_Email);
            settings.setValue(QStringLiteral("auth/savedAt"), QDateTime::currentMSecsSinceEpoch());
            m_SavedEmail = m_Email;
            emit savedEmailChanged();
        } else {
            settings.setValue(QStringLiteral("auth/rememberMe"), false);
            settings.remove(QStringLiteral("auth/refreshToken"));
            settings.remove(QStringLiteral("auth/savedAt"));
        }

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
                    if (m_State == QStringLiteral("idle"))
                        fetchMachines();
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

void LauncherApi::fetchMachines()
{
    if (!m_LoggedIn)
        return;

    request("GET", QStringLiteral("machines"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    m_HasMachine = root.value(QStringLiteral("machines")).toArray().size() > 0;
                    m_MachinesLoaded = true;
                    emit machinesChanged();
                }
                // Falha ao buscar máquinas não deve travar a UI: fica no estado
                // "ainda não sabemos" (machinesLoaded=false) e tenta de novo no
                // próximo refreshStatus().
            });
}

void LauncherApi::createMachine(const QString& password)
{
    if (!m_LoggedIn || m_Busy)
        return;
    if (password.size() < 5 || password.size() > 64) {
        setError(QStringLiteral("Senha deve ter entre 5 e 64 caracteres"));
        return;
    }

    setBusy(true);
    setError(QString());
    request("POST", QStringLiteral("create-machine"),
            QJsonObject{{QStringLiteral("password"), password}},
            true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300) {
                    // VM entrando em provisionamento/boot: refresca status (que por sua
                    // vez já re-checa machines) pra UI sair do estado "sem VM".
                    refreshStatus();
                }
                else {
                    setError(errorObject(root).value(QStringLiteral("message")).toString(
                        QStringLiteral("Não foi possível criar sua VM")));
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

    QSettings settings;
    settings.remove(QStringLiteral("auth/refreshToken"));
    settings.remove(QStringLiteral("auth/savedAt"));

    emit emailChanged();
    emit loggedInChanged();
    emit statusChanged();
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
                    const QJsonObject user = root.value(QStringLiteral("user")).toObject();
                    if (!user.value(QStringLiteral("email")).toString().isEmpty()) {
                        m_Email = user.value(QStringLiteral("email")).toString();
                        emit emailChanged();
                    }
                    m_LoggedIn = true;
                    emit loggedInChanged();

                    if (m_RememberMe) {
                        QSettings settings;
                        settings.setValue(QStringLiteral("auth/refreshToken"), m_RefreshToken);
                        if (!m_Email.isEmpty()) {
                            settings.setValue(QStringLiteral("auth/email"), m_Email);
                            m_SavedEmail = m_Email;
                            emit savedEmailChanged();
                        }
                    }

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

void LauncherApi::uploadFileToVm(const QString& localFilePath)
{
    QString cleanPath = localFilePath;
    if (cleanPath.startsWith("file:///")) {
        cleanPath = QUrl(cleanPath).toLocalFile();
    }
    
    QFile* file = new QFile(cleanPath);
    if (!file->open(QIODevice::ReadOnly)) {
        delete file;
        setError(tr("Não foi possível abrir o arquivo para upload"));
        return;
    }

    setBusy(true);

    QHttpMultiPart* multiPart = new QHttpMultiPart(QHttpMultiPart::FormDataType);
    QHttpPart filePart;
    QFileInfo fileInfo(cleanPath);
    filePart.setHeader(QNetworkRequest::ContentDispositionHeader,
                       QVariant(QString("form-data; name=\"file\"; filename=\"%1\"").arg(fileInfo.fileName())));
    filePart.setBodyDevice(file);
    file->setParent(multiPart);
    multiPart->append(filePart);

    QNetworkRequest request(kApiBase.resolved(QUrl(QStringLiteral("/api/user/session/upload"))));
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("User-Agent", "SpaceConnect-Qt/0.1.0");
    if (!m_AccessToken.isEmpty())
        request.setRawHeader("Authorization", "Bearer " + m_AccessToken.toUtf8());

    QNetworkReply* reply = m_Network.post(request, multiPart);
    multiPart->setParent(reply);

    connect(reply, &QNetworkReply::finished, this, [this, reply, fileInfo]() {
        setBusy(false);
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray responseData = reply->readAll();
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (status >= 200 && status < 300) {
            emit fileUploadSucceeded(fileInfo.fileName());
        } else {
            QString err = doc.object().value(QStringLiteral("error")).toString();
            setError(err.isEmpty() ? tr("Falha ao transferir arquivo para a VM") : err);
        }
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
