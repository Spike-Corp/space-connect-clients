#pragma once

#include "recaptchafetcher.h"

#include <QHash>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QTimer>
#include <QVariantList>

#include <functional>

class LauncherApi : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(bool loggedIn READ loggedIn NOTIFY loggedInChanged)
    Q_PROPERTY(QString email READ email NOTIFY emailChanged)
    Q_PROPERTY(bool rememberMe READ rememberMe WRITE setRememberMe NOTIFY rememberMeChanged)
    Q_PROPERTY(QString savedEmail READ savedEmail NOTIFY savedEmailChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorMessageChanged)
    Q_PROPERTY(QString state READ state NOTIFY statusChanged)
    Q_PROPERTY(int queuePosition READ queuePosition NOTIFY statusChanged)
    Q_PROPERTY(int queueTotal READ queueTotal NOTIFY statusChanged)
    Q_PROPERTY(QString planSlug READ planSlug NOTIFY statusChanged)
    Q_PROPERTY(QString machineName READ machineName NOTIFY statusChanged)
    Q_PROPERTY(QString creationPhase READ creationPhase NOTIFY statusChanged)
    Q_PROPERTY(qint64 remainingMinutes READ remainingMinutes NOTIFY statusChanged)
    // Falso enquanto ainda não sabemos (undecided) ou o usuário realmente não
    // tem nenhuma VM dedicada provisionada. Sem isso, o app só sabia "joinQueue",
    // e usuários sem VM ficavam presos num loop de fila (o backend nunca cria
    // VM a partir da fila — só o /create-machine faz isso, igual ao site).
    Q_PROPERTY(bool hasMachine READ hasMachine NOTIFY machinesChanged)
    Q_PROPERTY(bool machinesLoaded READ machinesLoaded NOTIFY machinesChanged)
    // Amigos (beta): listas pra UI + ações. friendMachines = máquinas que amigos
    // compartilham comigo (com permissão deles).
    Q_PROPERTY(QVariantList friends READ friends NOTIFY friendsChanged)
    Q_PROPERTY(QVariantList incomingRequests READ incomingRequests NOTIFY friendsChanged)
    Q_PROPERTY(QVariantList outgoingRequests READ outgoingRequests NOTIFY friendsChanged)
    Q_PROPERTY(QVariantList friendMachines READ friendMachines NOTIFY friendsChanged)
    Q_PROPERTY(QString myUsername READ myUsername NOTIFY friendsChanged)
    // Emuladores: [{id, name, tagline, systems, sizeBytes, version, icon, installed}]
    Q_PROPERTY(QVariantList emulators READ emulators NOTIFY emulatorsChanged)
    // Biblioteca de downloads gerenciada pelo admin (aba Downloads do app)
    Q_PROPERTY(QVariantList downloads READ downloads NOTIFY downloadsChanged)
    // Lista de VMs dedicadas do usuário (multi-plano) e a VM escolhida pra
    // abrir. Quando há 2+ máquinas, a UI mostra um seletor (o app Android já
    // tinha; o desktop não passava machineId em nada e o backend escolhia
    // "a" sessão ativa — usuário com 2 planos não conseguia escolher).
    Q_PROPERTY(QVariantList machines READ machines NOTIFY machinesChanged)
    Q_PROPERTY(QString selectedMachineId READ selectedMachineId WRITE setSelectedMachineId NOTIFY machinesChanged)
    // Máquina da sessão ativa reportada pelo /status (vazio quando não há) — a UI
    // usa pra exibir o saldo do plano da VM certa em contas multi-plano.
    Q_PROPERTY(QString statusMachineId READ statusMachineId NOTIFY statusChanged)
    // Minhas máquinas (pra permissão por máquina no painel de amigos)
    Q_PROPERTY(QVariantList myMachines READ myMachines NOTIFY friendsChanged)

public:
    explicit LauncherApi(QObject* parent = nullptr);

    bool busy() const { return m_Busy; }
    bool loggedIn() const { return m_LoggedIn; }
    QString email() const { return m_Email; }
    bool rememberMe() const { return m_RememberMe; }
    void setRememberMe(bool remember) {
        if (m_RememberMe != remember) {
            m_RememberMe = remember;
            emit rememberMeChanged();
        }
    }
    QString savedEmail() const { return m_SavedEmail; }
    QString errorMessage() const { return m_ErrorMessage; }
    QString state() const { return m_State; }
    int queuePosition() const { return m_QueuePosition; }
    int queueTotal() const { return m_QueueTotal; }
    QString planSlug() const { return m_PlanSlug; }
    QString machineName() const { return m_MachineName; }
    QString creationPhase() const { return m_CreationPhase; }
    qint64 remainingMinutes() const { return m_RemainingMs / 60000; }
    bool hasMachine() const { return m_HasMachine; }
    bool machinesLoaded() const { return m_MachinesLoaded; }
    QVariantList machines() const { return m_Machines; }
    QString selectedMachineId() const { return m_SelectedMachineId; }
    void setSelectedMachineId(const QString& id);
    QString statusMachineId() const { return m_StatusMachineId; }
    QVariantList friends() const { return m_Friends; }
    QVariantList incomingRequests() const { return m_Incoming; }
    QVariantList outgoingRequests() const { return m_Outgoing; }
    QVariantList friendMachines() const { return m_FriendMachines; }
    QString myUsername() const { return m_MyUsername; }
    QVariantList myMachines() const { return m_MyMachines; }
    QVariantList emulators() const { return m_Emulators; }
    QVariantList downloads() const { return m_Downloads; }

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
    // Emuladores: catálogo oficial + instalar/lançar na VM (o backend usa o
    // guest agent do Proxmox — nao precisa de versao nova do agent Windows).
    // Mic bridge: garante que o MicBridge está rodando na VM (chamado ao
    // conectar quando o encaminhamento de mic está ligado). Fire-and-forget.
    void ensureMicBridge();
    Q_INVOKABLE void refreshEmulators();
    // Transfere o disco de uma VM pra outra (a de origem é excluída, a de
    // destino ganha o espaço inteiro). Só aparece na UI com 2+ VMs Proxmox.
    Q_INVOKABLE void transferDisk(const QString& sourceMachineId, const QString& targetMachineId);
    Q_INVOKABLE void refreshDownloads();
    // Baixa um item da biblioteca NA VM (pasta Downloads dela) — NÃO pro PC
    // local. O backend manda a VM baixar da URL direto; o app faz polling no
    // status (downloadItemProgress 0-100 + downloadItemFinished ok/message).
    // kind=link abre no navegador local.
    Q_INVOKABLE void downloadItem(const QString& id, const QString& url, const QString& name, const QString& kind);
    // (interno) polling do download na VM
    void pollVmDownload(const QString& itemId, const QString& name, int attempt);
    // Licença NVIDIA vGPU (RTX Virtual Workstation) da T4: o cliente baixa o
    // driver novo (aba Downloads) e APLICA a licença por aqui — o backend
    // empurra o token .tok direto pra VM (nunca URL pública). Emite
    // gpuLicenseStatusChanged / gpuLicenseApplied(ok, message).
    Q_PROPERTY(bool gpuLicenseAvailable READ gpuLicenseAvailable NOTIFY gpuLicenseStatusChanged)
    bool gpuLicenseAvailable() const { return m_GpuLicenseAvailable; }
    Q_INVOKABLE void refreshGpuLicenseStatus();
    Q_INVOKABLE void applyGpuLicense();
    Q_INVOKABLE void installEmulator(const QString& emulatorId);
    Q_INVOKABLE void launchEmulator(const QString& emulatorId);
    // (interno) polling do install assíncrono
    void pollEmulatorInstall(const QString& emulatorId, int attempt);
    // Progresso do install em andamento (0-100; -1 = nenhum). O install NÃO
    // usa o busy global: o usuário pode continuar usando o app (inclusive
    // entrar na VM) enquanto baixa/instala — antes o busy travava tudo e
    // parecia um loop infinito.
    Q_PROPERTY(QString installingEmulatorId READ installingEmulatorId NOTIFY emulatorInstallProgressChanged)
    Q_PROPERTY(int emulatorInstallProgress READ emulatorInstallProgress NOTIFY emulatorInstallProgressChanged)
    QString installingEmulatorId() const { return m_InstallingEmulatorId; }
    int emulatorInstallProgress() const { return m_EmulatorInstallProgress; }
    // Relato de bug de dentro do app (vai pra página "Bugs app" do admin).
    // Funciona mesmo deslogado (tela de login): nesse caso emailHint é usado.
    Q_INVOKABLE void reportBug(const QString& description, const QString& emailHint);
    // Som curto de notificação (máquina pronta / desligando). Falha silenciosa
    // em sistema sem áudio — nunca deve quebrar o fluxo da sessão.
    Q_INVOKABLE void playNotifySound();
    // Lê um toggle de notificação DIRETO do QSettings (grupo sessionNotify,
    // escrito pelo SettingsView). O Settings do Qt.labs.settings cacheia na
    // criação — ler aqui garante que mexer no toggle vale na hora, sem restart.
    Q_INVOKABLE bool sessionNotifyEnabled(const QString& key) const;
    // SpaceUSB: pede uma sessão de túnel USB ao backend e abre o helper no PC.
    // Se o helper não estiver instalado, emite usbHelperMissing() pra UI oferecer
    // o download.
    Q_INVOKABLE void startUsbPassthrough();
    // Amigos
    Q_INVOKABLE void refreshFriends();
    Q_INVOKABLE void addFriend(const QString& username);
    Q_INVOKABLE void acceptFriendRequest(const QString& requestId);
    Q_INVOKABLE void declineFriendRequest(const QString& requestId);
    Q_INVOKABLE void removeFriend(const QString& friendId);
    Q_INVOKABLE void setFriendPermissions(const QString& friendId, bool showMachine, bool allowConnect);
    // Override por máquina (vence o global). machineId vazio = global.
    Q_INVOKABLE void setFriendMachinePermission(const QString& friendId, const QString& machineId, bool showMachine, bool allowConnect);
    Q_INVOKABLE void connectFriendMachine(const QString& machineId);
    // Renomeia a VM no backend (só funciona pra VM do próprio usuário — o
    // backend rejeita VM de amigo; nesse caso o rename fica só local no app).
    Q_INVOKABLE void renameMachine(const QString& machineId, const QString& name);
    // Exclui a VM de VERDADE (wipe total: disco + snapshots + libera slot).
    // Irreversível. O cliente confirma 2x no app. Emite machineDeleteFinished.
    Q_INVOKABLE void deleteMachine(const QString& machineId);
    // machineId da última conexão aberta pra um endereço (auto-rename no PcView).
    Q_INVOKABLE QString machineIdForAddress(const QString& address) const;
    Q_INVOKABLE void setUsername(const QString& username);
    Q_INVOKABLE void checkUsername(const QString& username);
    Q_INVOKABLE void logout();
    // Bitrate ceiling (Kbps) cached from the last connection reported by the backend.
    // 0 when never received; callers should fall back to a local heuristic.
    Q_INVOKABLE int maxBitrateKbps() const;
    Q_INVOKABLE int recommendedBitrateKbps() const;

signals:
    void busyChanged();
    void loggedInChanged();
    void emailChanged();
    void rememberMeChanged();
    void savedEmailChanged();
    void errorMessageChanged();
    void statusChanged();
    void machinesChanged();
    void loginSucceeded();
    void twoFactorRequired();
    void connectionReady(QString address, QString machineId, QString name);
    void fileUploadSucceeded(QString fileName);
    void bugReportFinished(bool success, QString message);
    void usbHelperMissing();
    void usbSessionStarted(QString machineName);
    void friendsChanged();
    void friendActionResult(bool success, QString message);
    void usernameCheckResult(bool available, QString reason);
    // Emuladores
    void emulatorsChanged();
    void emulatorActionResult(bool success, QString message);
    void emulatorInstallProgressChanged();
    // Downloads (biblioteca do admin)
    void downloadsChanged();
    void downloadItemProgress(int percent);
    void downloadItemFinished(bool success, QString message);
    void gpuLicenseStatusChanged();
    void gpuLicenseApplied(bool success, QString message);
    void diskTransferFinished(bool success, QString message);
    void machineDeleteFinished(bool success, QString message);

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
    void sendFriendPairAttempt(const QString& machineId, const QString& pin, int attempt);
    QString deviceId() const;
    // VM alvo das ações (fila/conexão/encerrar): a da sessão ativa, se houver;
    // senão a selecionada no seletor. Vazio = backend decide (comportamento antigo).
    QString effectiveMachineId() const;
    static QString platformName();

    QNetworkAccessManager m_Network;
    QTimer m_RefreshTimer;
    RecaptchaFetcher m_Recaptcha;
    QString m_AccessToken;
    QString m_RefreshToken;
    QString m_TempToken;
    QString m_Email;
    QString m_SavedEmail;
    QString m_ErrorMessage;
    QString m_State = QStringLiteral("idle");
    QString m_PlanSlug;
    QString m_MachineName;
    QString m_CreationPhase;
    int m_QueuePosition = 0;
    int m_QueueTotal = 0;
    qint64 m_RemainingMs = 0;
    bool m_Busy = false;
    // Serializa o refresh de token: o app disparava 2 refreshes concorrentes no
    // startup (timer do construtor + poll 401 do LauncherView) com o MESMO token,
    // e o backend (rotação + detecção de reuso) revogava o dispositivo.
    bool m_Refreshing = false;
    bool m_LoggedIn = false;
    bool m_RememberMe = true;
    bool m_HasMachine = false;
    bool m_MachinesLoaded = false;
    QVariantList m_Machines;
    QString m_SelectedMachineId;
    // Máquina da sessão ativa reportada pelo /status (prioriza sobre a seleção).
    QString m_StatusMachineId;
    // Amigos
    QVariantList m_Friends;
    QVariantList m_Incoming;
    QVariantList m_Outgoing;
    QVariantList m_FriendMachines;
    // Emuladores (catalogo + flag installed)
    QVariantList m_Emulators;
    QVariantList m_Downloads;
    bool m_GpuLicenseAvailable = false;
    QString m_InstallingEmulatorId;
    int m_EmulatorInstallProgress = -1;
    // Máquina de amigo sendo conectada agora (o PIN vai pra rota friend-aware).
    QString m_PendingFriendMachineId;
    // endereço ("host:port") → machineId das conexões abertas neste app —
    // permite ao PcView aplicar/salvar o nome da conta no computador pareado.
    QHash<QString, QString> m_MachineIdByAddress;
    QString m_MyUsername;
    QVariantList m_MyMachines;
};
