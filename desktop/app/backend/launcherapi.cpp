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
#include <QDir>
#include <QProcess>
#if defined(Q_OS_WIN)
#include <windows.h>
#pragma comment(lib, "winmm.lib")
#elif defined(Q_OS_LINUX)
#include <QStandardPaths>
#endif
#include <QSysInfo>
#include <QUuid>
#include <QUrl>

#include <cmath>
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
    m_SelectedMachineId = settings.value(QStringLiteral("launcher/selectedMachineId")).toString();

    // Discord Rich Presence: busca o app id no backend (rota pública /health) e
    // cacheia no QSettings — o RichPresenceManager lê de lá quando o stream começa.
    // Assim trocar o app id do Discord não exige rebuild do app.
    request("GET", QStringLiteral("health"), QJsonObject(), false,
            [](int status, const QJsonObject& root) {
                if (status < 200 || status >= 300) return;
                const QString appId = root.value(QStringLiteral("discordAppId")).toString();
                QSettings s;
                if (!appId.isEmpty())
                    s.setValue(QStringLiteral("launcher/discordAppId"), appId);
                else
                    s.remove(QStringLiteral("launcher/discordAppId"));
            });
    if (m_RememberMe && !m_SavedEmail.isEmpty()) {
        const qint64 savedAt = settings.value(QStringLiteral("auth/savedAt"), 0).toLongLong();
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        const qint64 kThirtyDaysMs = 30LL * 24 * 60 * 60 * 1000;
        if (savedAt > 0 && (now - savedAt) < kThirtyDaysMs) {
            m_RefreshToken = settings.value(QStringLiteral("auth/refreshToken")).toString();
            m_Email = m_SavedEmail;
            if (!m_RefreshToken.isEmpty()) {
                // NÃO marcar m_LoggedIn aqui. A tela inicial é SEMPRE a LoginView e a
                // navegação do main.qml só reage à MUDANÇA de loggedIn. Marcando true
                // às cegas no construtor, um login manual posterior não mudava o valor,
                // loggedInChanged nunca era emitido e o app ficava preso na tela de
                // login mesmo já autenticado (relatos "digito a senha e não acontece
                // nada", com loggedIn=true no report). Quem confirma a sessão salva é o
                // refreshTokens(), que emite a transição false -> true e navega.
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
        const bool wasLoggedIn = m_LoggedIn;
        m_LoggedIn = !m_AccessToken.isEmpty() && !m_RefreshToken.isEmpty();
        // Só emite em mudança real: handlers de navegação reagem a este sinal e
        // emissões espúrias provocavam replaces duplicados no StackView (views
        // duplicadas → dialogs duplicados, ex.: 2 modais de 2FA ao mesmo tempo).
        if (m_LoggedIn != wasLoggedIn)
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
    // Precisa mandar o mesmo machineId usado pra entrar na fila: sem ele o backend
    // não localizava a entrada e o app voltava pro estado "idle", como se o usuário
    // tivesse saído da fila sozinho.
    QString statusPath = QStringLiteral("status");
    if (!effectiveMachineId().isEmpty())
        statusPath += QStringLiteral("?machineId=") + effectiveMachineId();
    request("GET", statusPath, QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status >= 200 && status < 300) {
                    applyStatus(root);
                    // Re-checa as máquinas em qualquer transição de estado (não só idle):
                    // quem criou/iniciou a VM pelo SITE tem a máquina registrada, mas o app
                    // podia ter cacheado hasMachine=false num boot anterior e ficar preso em
                    // "Create your PC" mesmo com a VM subindo. Agora atualiza sempre que o
                    // estado muda ou ainda não carregou.
                    if (m_State == QStringLiteral("idle") || !m_MachinesLoaded)
                        fetchMachines();
                    // Se tem sessão/criação rolando, a máquina EXISTE — marca direto pra
                    // não cair no estado "sem VM" enquanto o fetchMachines não responde.
                    if (!m_HasMachine && (m_State == QStringLiteral("starting")
                                          || m_State == QStringLiteral("queued")
                                          || m_State == QStringLiteral("ready"))) {
                        m_HasMachine = true;
                        m_MachinesLoaded = true;
                        emit machinesChanged();
                    }
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
                    const QJsonArray array = root.value(QStringLiteral("machines")).toArray();
                    QVariantList list;
                    list.reserve(array.size());
                    for (const QJsonValue& value : array) {
                        const QJsonObject machine = value.toObject();
                        const QString id = machine.value(QStringLiteral("id")).toString();
                        if (id.isEmpty())
                            continue;
                        const QString name = machine.value(QStringLiteral("name")).toString();
                        const QString provider = machine.value(QStringLiteral("provider")).toString();
                        QVariantMap entry;
                        entry.insert(QStringLiteral("id"), id);
                        entry.insert(QStringLiteral("name"), name);
                        entry.insert(QStringLiteral("provider"), provider);
                        entry.insert(QStringLiteral("state"), machine.value(QStringLiteral("state")).toString());
                        // Saldo do plano ligado à VM (o backend já manda o
                        // entitlement por máquina — horas em HORAS, ex.: 87.5).
                        const QJsonObject entitlement =
                            machine.value(QStringLiteral("entitlement")).toObject();
                        const bool entActive =
                            entitlement.value(QStringLiteral("active")).toBool();
                        const bool entUnlimited =
                            entitlement.value(QStringLiteral("unlimited")).toBool();
                        const double hoursRemaining =
                            entitlement.value(QStringLiteral("hoursRemaining")).toDouble();
                        const double bonusHours =
                            entitlement.value(QStringLiteral("bonusHours")).toDouble();
                        entry.insert(QStringLiteral("planSlug"),
                                     entitlement.value(QStringLiteral("planSlug")).toString());
                        // Nome de exibição vindo do cadastro do produto no banco.
                        entry.insert(QStringLiteral("planName"),
                                     entitlement.value(QStringLiteral("planName")).toString());
                        entry.insert(QStringLiteral("entitlementActive"), entActive);
                        entry.insert(QStringLiteral("unlimited"), entUnlimited);
                        entry.insert(QStringLiteral("hoursRemaining"), hoursRemaining);
                        entry.insert(QStringLiteral("bonusHours"), bonusHours);
                        // Rótulo pronto pro seletor: "nome (provider) · 87h" —
                        // ajuda a escolher a VM certa quando há 2+ planos.
                        QString display =
                            (name.isEmpty() ? id : name)
                            + (provider.isEmpty() ? QString() : QStringLiteral(" (%1)").arg(provider));
                        if (entActive) {
                            if (entUnlimited) {
                                display += QStringLiteral(" · ") + tr("ilimitado");
                            }
                            else {
                                display += QStringLiteral(" · %1h").arg(
                                    hoursRemaining, 0, 'f', hoursRemaining == std::floor(hoursRemaining) ? 0 : 1);
                                if (bonusHours > 0)
                                    display += tr(" + %1h bônus").arg(
                                        bonusHours, 0, 'f', bonusHours == std::floor(bonusHours) ? 0 : 1);
                            }
                        }
                        entry.insert(QStringLiteral("display"), display);
                        list.append(entry);
                    }
                    m_Machines = list;
                    m_HasMachine = !list.isEmpty();
                    m_MachinesLoaded = true;

                    // Seleção: com 1 máquina, ela é sempre a escolhida. Com 2+,
                    // mantém a escolha salva se ainda existir; senão limpa pra
                    // o usuário escolher no seletor (backend decide até lá).
                    if (list.size() == 1) {
                        setSelectedMachineId(list.first().toMap().value(QStringLiteral("id")).toString());
                    }
                    else {
                        bool stillExists = false;
                        for (const QVariant& item : list) {
                            if (item.toMap().value(QStringLiteral("id")).toString() == m_SelectedMachineId) {
                                stillExists = true;
                                break;
                            }
                        }
                        if (!stillExists)
                            setSelectedMachineId(QString());
                    }
                    emit machinesChanged();
                }
                // Falha ao buscar máquinas não deve travar a UI: fica no estado
                // "ainda não sabemos" (machinesLoaded=false) e tenta de novo no
                // próximo refreshStatus().
            });
}

void LauncherApi::setSelectedMachineId(const QString& id)
{
    if (m_SelectedMachineId == id)
        return;
    m_SelectedMachineId = id;
    QSettings settings;
    settings.setValue(QStringLiteral("launcher/selectedMachineId"), id);
    emit machinesChanged();
}

QString LauncherApi::effectiveMachineId() const
{
    // Sessão ativa manda: conectar/encerrar têm que mirar a VM DA SESSÃO,
    // mesmo que o usuário tenha selecionado outra no seletor (igual ao Android).
    if (!m_StatusMachineId.isEmpty())
        return m_StatusMachineId;
    return m_SelectedMachineId;
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
    QJsonObject body{
        {QStringLiteral("requestedHours"), 24},
        {QStringLiteral("provider"), QStringLiteral("proxmox")},
    };
    // Multi-máquina: entra na fila da VM escolhida no seletor (backend sem
    // machineId assumia a sessão "padrão" e podia abrir a máquina errada).
    if (!effectiveMachineId().isEmpty())
        body.insert(QStringLiteral("machineId"), effectiveMachineId());
    request("POST", QStringLiteral("queue"),
            body,
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
    QString path = QStringLiteral("queue");
    if (!effectiveMachineId().isEmpty())
        path += QStringLiteral("?machineId=") + effectiveMachineId();
    request("DELETE", path, QJsonObject(), true,
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
    QString path = QStringLiteral("connection");
    const QString targetMachineId = effectiveMachineId();
    if (!targetMachineId.isEmpty())
        path += QStringLiteral("?machineId=") + targetMachineId;
    request("GET", path, QJsonObject(), true,
            [this, targetMachineId](int status, const QJsonObject& root) {
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
                    const QString address = connection.host + QStringLiteral(":")
                                            + QString::number(connection.port);
                    // Apelido da conta (só preenchido se o dono renomeou) —
                    // vai junto pro PcView aplicar por cima do SCG-VMF.
                    QString name;
                    for (const QVariant& item : m_Machines) {
                        const QVariantMap map = item.toMap();
                        if (map.value(QStringLiteral("id")).toString() == targetMachineId) {
                            name = map.value(QStringLiteral("accountName")).toString();
                            break;
                        }
                    }
                    m_MachineIdByAddress.insert(address, targetMachineId);
                    emit connectionReady(address, targetMachineId, name);
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
    QJsonObject body;
    if (!effectiveMachineId().isEmpty())
        body.insert(QStringLiteral("machineId"), effectiveMachineId());
    request("POST", QStringLiteral("session/end"), body, true,
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
    // Pareamento na VM de um AMIGO usa a rota friend-aware (valida permissão
    // de acesso no backend). O flag é setado em connectFriendMachine().
    if (!m_PendingFriendMachineId.isEmpty()) {
        sendFriendPairAttempt(m_PendingFriendMachineId, pin, 0);
        return;
    }
    sendPairAttempt(pin, 0);
}

void LauncherApi::sendFriendPairAttempt(const QString& machineId, const QString& pin, int attempt)
{
    request("POST", QStringLiteral("friends/machines/") + machineId + QStringLiteral("/pair"),
            QJsonObject{{QStringLiteral("pin"), pin}},
            true,
            [this, machineId, pin, attempt](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300
                    && root.value(QStringLiteral("paired")).toBool()) {
                    return;
                }
                if (attempt < 7) {
                    QTimer::singleShot(500, this, [this, machineId, pin, attempt]() {
                        sendFriendPairAttempt(machineId, pin, attempt + 1);
                    });
                }
            });
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
    const bool wasLoggedIn = m_LoggedIn;
    m_LoggedIn = false;

    QSettings settings;
    settings.remove(QStringLiteral("auth/refreshToken"));
    settings.remove(QStringLiteral("auth/savedAt"));

    emit emailChanged();
    if (wasLoggedIn)
        emit loggedInChanged();
    emit statusChanged();
}

void LauncherApi::refreshTokens()
{
    // Uma renovação por vez: sem este guard o app disparava DOIS refreshes
    // concorrentes no startup (timer do construtor + poll inicial do LauncherView
    // que tomava 401 por ainda não ter access token), ambos com o MESMO refresh
    // token. O backend rotaciona o token a cada uso e o segundo request caía na
    // detecção de reuso → dispositivo REVOGADO → usuário deslogado logo depois
    // de ativar "lembrar por 30 dias" (e com 2FA, obrigado a logar de novo).
    if (m_Refreshing || m_RefreshToken.isEmpty())
        return;
    m_Refreshing = true;

    request("POST", QStringLiteral("auth/refresh"),
            QJsonObject{
                {QStringLiteral("refreshToken"), m_RefreshToken},
                {QStringLiteral("deviceId"), deviceId()},
            },
            false,
            [this](int status, const QJsonObject& root) {
                m_Refreshing = false;
                if (status >= 200 && status < 300) {
                    m_AccessToken = root.value(QStringLiteral("accessToken")).toString();
                    m_RefreshToken = root.value(QStringLiteral("refreshToken")).toString();
                    const QJsonObject user = root.value(QStringLiteral("user")).toObject();
                    if (!user.value(QStringLiteral("email")).toString().isEmpty()) {
                        m_Email = user.value(QStringLiteral("email")).toString();
                        emit emailChanged();
                    }
                    const bool wasLoggedIn = m_LoggedIn;
                    m_LoggedIn = true;
                    if (!wasLoggedIn)
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
                else if (status == 401) {
                    // Sessão realmente inválida/expirada no servidor: desloga.
                    logout();
                    setError(QStringLiteral("Sua sessão expirou. Entre novamente."));
                }
                else {
                    // Qualquer outra falha é transitória (sem internet no boot do
                    // PC, 5xx, ou 409 = refresh já em voo absorvido pelo backend).
                    // NÃO derruba a sessão local — o refresh token segue válido
                    // no servidor. Antes disto, um simples boot sem Wi-Fi já
                    // apagava o login salvo e forçava login (+2FA) de novo.
                    m_RefreshTimer.start(60000);
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
        m_StatusMachineId = status.machineId;
        m_CreationPhase = status.creationPhase;
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
    else if (method == "PUT")
        reply = m_Network.sendCustomRequest(request, "PUT", QJsonDocument(body).toJson(QJsonDocument::Compact));
    else if (method == "PATCH")
        reply = m_Network.sendCustomRequest(request, "PATCH", QJsonDocument(body).toJson(QJsonDocument::Compact));
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

    // Era /api/user/session/upload (middleware `authenticate`, JWT do SITE) com um
    // token de LAUNCHER (aud 'space-connect') → 401 "User not found" em todo upload.
    // A rota certa é a do launcher, que aceita este token (mesma do app Android).
    QNetworkRequest request(kApiBase.resolved(QUrl(QStringLiteral("session/upload"))));
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

#if defined(Q_OS_WIN) || defined(Q_OS_LINUX)
namespace {
// Extrai o wav embutido (qrc) pra um arquivo temporário, uma vez por processo.
// PlaySound/paplay não tocam direto de recurso Qt.
QString notifyWavPath()
{
    static QString cached;
    if (!cached.isEmpty())
        return cached;
    QFile res(QStringLiteral(":/sounds/notify.wav"));
    if (!res.open(QIODevice::ReadOnly))
        return QString();
    const QString tmp = QDir::temp().filePath(QStringLiteral("spaceconnect-notify.wav"));
    QFile out(tmp);
    if (!out.open(QIODevice::WriteOnly))
        return QString();
    out.write(res.readAll());
    out.close();
    cached = tmp;
    return cached;
}
}
#endif

bool LauncherApi::sessionNotifyEnabled(const QString& key) const
{
    return QSettings().value(QStringLiteral("sessionNotify/") + key, true).toBool();
}

void LauncherApi::startUsbPassthrough()
{
    if (!m_LoggedIn || m_Busy)
        return;

    setBusy(true);
    setError(QString());
    QJsonObject body;
    if (!effectiveMachineId().isEmpty())
        body.insert(QStringLiteral("machineId"), effectiveMachineId());
    request("POST", QStringLiteral("usb/start"), body, true,
            [this](int status, const QJsonObject& root) {
                setBusy(false);
                if (status < 200 || status >= 300) {
                    setError(errorObject(root).value(QStringLiteral("message")).toString(
                        QStringLiteral("Não foi possível abrir o USB passthrough")));
                    return;
                }

                const QString host = root.value(QStringLiteral("host")).toString();
                const int port = root.value(QStringLiteral("port")).toInt();
                const QString token = root.value(QStringLiteral("token")).toString();
                const QString machineName = root.value(QStringLiteral("machineName")).toString();

                // Helper instalado? (estilo AppData — %LOCALAPPDATA%\SpaceUSB\SpaceUSB.exe)
#if defined(Q_OS_WIN)
                const QString helper =
                    QDir(qEnvironmentVariable("LOCALAPPDATA")).filePath(QStringLiteral("SpaceUSB/SpaceUSB.exe"));
#else
                const QString helper =
                    QDir::home().filePath(QStringLiteral(".local/share/SpaceUSB/SpaceUSB"));
#endif
                if (!QFile::exists(helper)) {
                    emit usbHelperMissing();
                    return;
                }

                QProcess::startDetached(helper, {
                    QStringLiteral("--host"), host,
                    QStringLiteral("--port"), QString::number(port),
                    QStringLiteral("--token"), token,
                });
                emit usbSessionStarted(machineName);
            });
}

void LauncherApi::playNotifySound()
{
    // Sem Qt Multimedia de propósito: o build legado (Win7/8) usa Qt 5.15 sem o
    // módulo, e adicionar "QT += multimedia" quebrava as 3 pernas do CI.
    // PlaySound (winmm) existe em todo Windows; no Linux tenta paplay/aplay.
    const QString wav = notifyWavPath();
    if (wav.isEmpty())
        return;
#if defined(Q_OS_WIN)
    PlaySoundW(reinterpret_cast<LPCWSTR>(wav.utf16()), nullptr, SND_FILENAME | SND_ASYNC);
#elif defined(Q_OS_LINUX)
    const QString paplay = QStandardPaths::findExecutable(QStringLiteral("paplay"));
    if (!paplay.isEmpty()) {
        QProcess::startDetached(paplay, {wav});
        return;
    }
    const QString aplay = QStandardPaths::findExecutable(QStringLiteral("aplay"));
    if (!aplay.isEmpty())
        QProcess::startDetached(aplay, {wav});
#endif
}

void LauncherApi::reportBug(const QString& description, const QString& emailHint)
{
    const QString trimmed = description.trimmed();
    if (trimmed.size() < 10) {
        emit bugReportFinished(false, tr("Descreva o problema em pelo menos 10 caracteres."));
        return;
    }

    QJsonObject body{
        {QStringLiteral("description"), trimmed.left(4000)},
        {QStringLiteral("app"), platformName()},
        {QStringLiteral("appVersion"), QCoreApplication::applicationVersion()},
        {QStringLiteral("osVersion"), QSysInfo::prettyProductName()
            + QStringLiteral(" (") + QSysInfo::currentCpuArchitecture() + QStringLiteral(")")},
        {QStringLiteral("deviceModel"), QSysInfo::machineHostName()},
        {QStringLiteral("deviceId"), deviceId()},
        // Contexto pra entender COMO o bug aconteceu (visto na página "Bugs app").
        {QStringLiteral("context"), QJsonObject{
            {QStringLiteral("state"), m_State},
            {QStringLiteral("queuePosition"), m_QueuePosition},
            {QStringLiteral("queueTotal"), m_QueueTotal},
            {QStringLiteral("planSlug"), m_PlanSlug},
            {QStringLiteral("machineName"), m_MachineName},
            {QStringLiteral("creationPhase"), m_CreationPhase},
            {QStringLiteral("loggedIn"), m_LoggedIn},
        }},
    };
    if (!emailHint.trimmed().isEmpty())
        body.insert(QStringLiteral("email"), emailHint.trimmed());

    // Autenticado quando há sessão — o backend amarra o relato à conta. Sem
    // sessão (bug na própria tela de login), segue anônimo com o e-mail digitado.
    request("POST", QStringLiteral("bug-report"), body, !m_AccessToken.isEmpty(),
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    emit bugReportFinished(true, tr("Relato enviado. Obrigado por avisar!"));
                }
                else {
                    emit bugReportFinished(false,
                        errorObject(root).value(QStringLiteral("message")).toString(
                            tr("Não foi possível enviar o relato. Tente novamente.")));
                }
            });
}

// ── Amigos (beta) ────────────────────────────────────────────────────────────

void LauncherApi::refreshFriends()
{
    if (!m_LoggedIn)
        return;
    request("GET", QStringLiteral("friends"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                if (status < 200 || status >= 300) return;
                m_Friends = root.value(QStringLiteral("friends")).toArray().toVariantList();
                m_Incoming = root.value(QStringLiteral("incoming")).toArray().toVariantList();
                m_Outgoing = root.value(QStringLiteral("outgoing")).toArray().toVariantList();
                m_MyUsername = root.value(QStringLiteral("me")).toObject()
                                   .value(QStringLiteral("username")).toString();
                m_MyMachines = root.value(QStringLiteral("myMachines")).toArray().toVariantList();
                emit friendsChanged();
            });
    request("GET", QStringLiteral("friends/machines"), QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                if (status < 200 || status >= 300) return;
                m_FriendMachines = root.value(QStringLiteral("machines")).toArray().toVariantList();
                emit friendsChanged();
            });
}

void LauncherApi::addFriend(const QString& username)
{
    if (!m_LoggedIn) return;
    request("POST", QStringLiteral("friends/add"),
            QJsonObject{{QStringLiteral("username"), username.trimmed()}},
            true,
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    const bool accepted = root.value(QStringLiteral("status")).toString() == QStringLiteral("accepted");
                    emit friendActionResult(true, accepted
                        ? tr("Vocês já são amigos! (pedido mútuo aceito)")
                        : tr("Pedido de amizade enviado!"));
                    refreshFriends();
                } else {
                    emit friendActionResult(false, errorObject(root).value(QStringLiteral("message")).toString(
                        tr("Não foi possível adicionar")));
                }
            });
}

void LauncherApi::acceptFriendRequest(const QString& requestId)
{
    request("POST", QStringLiteral("friends/requests/") + requestId + QStringLiteral("/accept"),
            QJsonObject(), true,
            [this](int status, const QJsonObject&) {
                if (status >= 200 && status < 300) refreshFriends();
            });
}

void LauncherApi::declineFriendRequest(const QString& requestId)
{
    request("POST", QStringLiteral("friends/requests/") + requestId + QStringLiteral("/decline"),
            QJsonObject(), true,
            [this](int status, const QJsonObject&) {
                if (status >= 200 && status < 300) refreshFriends();
            });
}

void LauncherApi::removeFriend(const QString& friendId)
{
    request("DELETE", QStringLiteral("friends/") + friendId, QJsonObject(), true,
            [this](int status, const QJsonObject&) {
                if (status >= 200 && status < 300) refreshFriends();
            });
}

void LauncherApi::setFriendPermissions(const QString& friendId, bool showMachine, bool allowConnect)
{
    setFriendMachinePermission(friendId, QString(), showMachine, allowConnect);
}

void LauncherApi::setFriendMachinePermission(const QString& friendId, const QString& machineId, bool showMachine, bool allowConnect)
{
    QJsonObject body{
        {QStringLiteral("showMachine"), showMachine},
        {QStringLiteral("allowConnect"), allowConnect},
    };
    if (!machineId.isEmpty())
        body.insert(QStringLiteral("machineId"), machineId);
    request("PUT", QStringLiteral("friends/") + friendId + QStringLiteral("/permissions"),
            body, true,
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    refreshFriends();
                } else {
                    emit friendActionResult(false, errorObject(root).value(QStringLiteral("message")).toString(
                        tr("Não foi possível salvar as permissões")));
                }
            });
}

void LauncherApi::connectFriendMachine(const QString& machineId)
{
    if (!m_LoggedIn || m_Busy) return;
    setBusy(true);
    request("GET", QStringLiteral("friends/machines/") + machineId + QStringLiteral("/connection"),
            QJsonObject(), true,
            [this, machineId](int status, const QJsonObject& root) {
                setBusy(false);
                if (status < 200 || status >= 300) {
                    emit friendActionResult(false, errorObject(root).value(QStringLiteral("message")).toString(
                        tr("Não foi possível conectar na máquina do seu amigo")));
                    return;
                }
                try {
                    const LauncherConnection connection =
                        LauncherJson::parseConnection(QJsonDocument(root).toJson(QJsonDocument::Compact));
                    if (connection.host.isEmpty() || connection.port <= 0)
                        throw std::runtime_error("Connection unavailable");
                    // O PIN do pareamento vai pra rota friend-aware enquanto esta
                    // conexão estiver em curso.
                    m_PendingFriendMachineId = machineId;
                    const QString address = connection.host + QStringLiteral(":")
                                            + QString::number(connection.port);
                    // Apelido que o DONO deu à VM (vem do /friends/machines,
                    // null quando é o slug interno) — o PcView aplica por cima
                    // do hostname do Apollo (SCG-VMF).
                    QString name;
                    for (const QVariant& item : m_FriendMachines) {
                        const QVariantMap map = item.toMap();
                        if (map.value(QStringLiteral("machineId")).toString() == machineId) {
                            name = map.value(QStringLiteral("accountName")).toString();
                            break;
                        }
                    }
                    m_MachineIdByAddress.insert(address, machineId);
                    emit connectionReady(address, machineId, name);
                }
                catch (const std::exception&) {
                    emit friendActionResult(false, tr("Conexão ainda não disponível na máquina do seu amigo"));
                }
            });
}

void LauncherApi::renameMachine(const QString& machineId, const QString& name)
{
    const QString trimmed = name.trimmed().left(60);
    if (!m_LoggedIn || machineId.isEmpty() || trimmed.isEmpty()) return;
    request("PATCH", QStringLiteral("machines/") + machineId + QStringLiteral("/name"),
            QJsonObject{{QStringLiteral("name"), trimmed}}, true,
            [this, machineId, trimmed](int status, const QJsonObject&) {
                if (status < 200 || status >= 300) return; // VM de amigo etc. — fica só local
                // Atualiza a lista em memória pra Launcher/seletor refletirem na hora.
                for (int i = 0; i < m_Machines.size(); i++) {
                    QVariantMap map = m_Machines[i].toMap();
                    if (map.value(QStringLiteral("id")).toString() == machineId) {
                        map.insert(QStringLiteral("name"), trimmed);
                        map.insert(QStringLiteral("accountName"), trimmed);
                        m_Machines[i] = map;
                        emit machinesChanged();
                        break;
                    }
                }
            });
}

QString LauncherApi::machineIdForAddress(const QString& address) const
{
    const QString exact = m_MachineIdByAddress.value(address);
    if (!exact.isEmpty())
        return exact;
    // Fallback por host: o endereço salvo no computador pode divergir do
    // "host:port" do /connection (activeAddress x remoteAddress, porta default).
    const QString host = address.section(QLatin1Char(':'), 0, 0);
    if (host.isEmpty())
        return QString();
    for (auto it = m_MachineIdByAddress.constBegin(); it != m_MachineIdByAddress.constEnd(); ++it) {
        if (it.key().section(QLatin1Char(':'), 0, 0) == host)
            return it.value();
    }
    return QString();
}

void LauncherApi::setUsername(const QString& username)
{
    request("PUT", QStringLiteral("friends/username"),
            QJsonObject{{QStringLiteral("username"), username.trimmed().toLower()}},
            true,
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    emit friendActionResult(true, tr("Username salvo!"));
                } else {
                    emit friendActionResult(false, errorObject(root).value(QStringLiteral("message")).toString(
                        tr("Não foi possível salvar o username")));
                }
            });
}

void LauncherApi::checkUsername(const QString& username)
{
    request("GET", QStringLiteral("friends/username/availability?u=") + QUrl::toPercentEncoding(username.trimmed().toLower()),
            QJsonObject(), true,
            [this](int status, const QJsonObject& root) {
                if (status >= 200 && status < 300) {
                    emit usernameCheckResult(root.value(QStringLiteral("available")).toBool(),
                                             root.value(QStringLiteral("reason")).toString());
                }
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
