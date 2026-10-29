import QtQuick 2.9
import QtQuick.Controls 2.3
import QtQuick.Layouts 1.3
import Qt.labs.platform 1.1 as Labs
import LauncherApi 1.0
import ComputerManager 1.0

Item {
    id: launcherView
    objectName: qsTr("SpaceCloud")
    property bool addingComputer: false
    // Endereço/nome da conexão em abertura — o nome da conta é aplicado no
    // PcView por cima do hostname do Apollo (SCG-VMF).
    property string pendingConnectAddress: ""
    property string pendingConnectName: ""
    property string connectionErrorText: qsTr("Não foi possível alcançar o host de streaming. Tente novamente em alguns segundos.")

    // Dicas curtas que rodam enquanto a VM liga/entra na fila — ensinam a usar a
    // SpaceCloud do jeito certo. Trocam sozinhas (ver Timer mais abaixo).
    property var fallbackBootTips: [
        qsTr("Baixe o app Space Connect no PC ou celular — a conexão é automática, sem IP nem configurar porta."),
        qsTr("Controle de Xbox/PS funciona plugado no seu dispositivo. O app reconhece sozinho."),
        qsTr("Use Wi-Fi 5 GHz ou cabo de rede pra melhor qualidade de imagem e menos delay."),
        qsTr("Seus jogos e arquivos ficam salvos na VM mesmo depois de desligar. Nada se perde."),
        qsTr("Feche a sessão pelo painel quando terminar — assim suas horas não são consumidas à toa."),
        qsTr("Quanto mais perto do servidor (São Paulo), menor o delay. Teste sua rede no painel."),
        qsTr("No app, dá pra mandar arquivos do seu PC/celular direto pra dentro da máquina na nuvem."),
        qsTr("Jogos pesados pedem planos com GPU mais forte. Cyberpunk e afins rolam melhor na RTX.")
    ]
    property var remoteBootTexts: LauncherApi.bootTexts
    property var bootTips: remoteBootTexts && remoteBootTexts.tips !== undefined
            && typeof remoteBootTexts.tips !== "string" && typeof remoteBootTexts.tips.length === "number"
            ? remoteBootTexts.tips : fallbackBootTips
    property int bootTipIndex: 0

    Timer {
        interval: 6000
        running: launcherView.bootTips.length > 0 && (LauncherApi.state === "starting" || LauncherApi.state === "queued")
        repeat: true
        onTriggered: launcherView.bootTipIndex = (launcherView.bootTipIndex + 1) % launcherView.bootTips.length
    }

    function currentBootTip() {
        return bootTips.length ? bootTips[bootTipIndex % bootTips.length] : ""
    }

    function bootText(key, fallback) {
        var value = remoteBootTexts ? remoteBootTexts[key] : null
        return typeof value === "string" && value.trim().length > 0 ? value : fallback
    }

    function queueTitle() {
        return bootText("queueTitle", qsTr("Position %1 of %2").arg(LauncherApi.queuePosition).arg(LauncherApi.queueTotal))
                .split("{position}").join(LauncherApi.queuePosition)
                .split("{total}").join(LauncherApi.queueTotal)
    }

    function formatActivationDate(value) {
        if (!value) return "--/--"
        var d = new Date(value)
        if (isNaN(d.getTime())) return "--/--"
        var day = (d.getUTCDate() < 10 ? "0" : "") + d.getUTCDate()
        var monthValue = d.getUTCMonth() + 1
        var month = (monthValue < 10 ? "0" : "") + monthValue
        return day + "/" + month
    }

    function pendingMachine() {
        var list = LauncherApi.machines
        if (!list || list.length === 0) return null
        var id = LauncherApi.statusMachineId || LauncherApi.selectedMachineId
        var m = null
        for (var i = 0; i < list.length; i++) {
            if (list[i].id === id) { m = list[i]; break }
        }
        if (!m && list.length === 1) m = list[0]
        return m && m.locked && m.upcomingEntitlement && m.availableAt ? m : null
    }

    function activationPending() {
        return pendingMachine() !== null
                || (LauncherApi.planLocked && LauncherApi.hasUpcomingPlan)
    }

    function pendingPlanName() {
        var m = pendingMachine()
        if (m)
            return (m.planName && ("" + m.planName).trim().length > 0)
                    ? m.planName : prettifyPlan(m.planSlug)
        return LauncherApi.upcomingPlanName || qsTr("Plano")
    }

    function pendingActivationDate() {
        var m = pendingMachine()
        return formatActivationDate(m ? m.availableAt : LauncherApi.upcomingPlanAvailableAt)
    }

    function needsMachine() {
        return !activationPending()
                && LauncherApi.state === "idle"
                && LauncherApi.machinesLoaded
                && !LauncherApi.hasMachine
    }

    function primaryAction() {
        if (activationPending()) return
        if (needsMachine()) {
            createMachineDialog.open()
            return
        }
        if (LauncherApi.state === "queued")
            LauncherApi.leaveQueue()
        else if (LauncherApi.state === "ready")
            LauncherApi.requestConnection()
        else if (LauncherApi.state === "idle")
            LauncherApi.joinQueue()
    }

    function statusTitle() {
        if (activationPending()) return qsTr("Pré-venda confirmada")
        if (needsMachine()) return qsTr("Create your PC")
        if (LauncherApi.state === "queued") return queueTitle()
        if (LauncherApi.state === "starting") return bootText("bootingTitle", qsTr("Your PC is starting"))
        if (LauncherApi.state === "ready") return qsTr("Your PC is ready")
        if (LauncherApi.state === "ending") return qsTr("Ending session")
        return qsTr("Ready to play")
    }

    // Rótulo amigável da fase de criação/boot (mesmo vocabulário do site).
    function creationPhaseLabel(phase) {
        var phases = remoteBootTexts ? remoteBootTexts.phases : null
        if (phases && typeof phases[phase] === "string" && phases[phase].trim().length)
            return phases[phase]
        var map = {
            "queued": qsTr("Allocating cloud resources"),
            "checking_snapshot": qsTr("Checking saved snapshots"),
            "restoring_disk": qsTr("Restoring VM disk"),
            "restoring_instance": qsTr("Recreating cloud instance"),
            "creating_instance": qsTr("Creating VM from template"),
            "attaching_gpu": qsTr("Attaching GPU"),
            "starting": qsTr("Starting the operating system"),
            "securing": qsTr("Securing the environment"),
            "setting_password": qsTr("Setting your access password"),
            "configuring_network": qsTr("Configuring the network"),
            "waiting_agent": qsTr("Connecting monitoring agent"),
            "ready": qsTr("Almost ready!")
        }
        return map[phase] || bootText("defaultTitle", qsTr("Preparing your machine"))
    }

    function statusDetails() {
        if (activationPending())
            return qsTr("Seu plano %1 está reservado. Ativação em %2; a validade começa nessa data.")
                    .arg(pendingPlanName()).arg(pendingActivationDate())
        if (needsMachine())
            return qsTr("You don't have a dedicated PC yet. Create one to start playing.")
        if (LauncherApi.state === "queued" || LauncherApi.state === "starting") {
            var reasons = remoteBootTexts ? remoteBootTexts.reasons : null
            var reason = reasons && reasons[LauncherApi.queueBlockedReason]
            var detail = LauncherApi.state === "starting" ? creationPhaseLabel(LauncherApi.creationPhase)
                    : reason || bootText("queueSearching", qsTr("Join the shared queue. Your plan determines your priority."))
            return LauncherApi.bootElapsedSeconds >= 60
                    ? bootText("takingLonger", "{reason}").split("{reason}").join(detail)
                    : detail
        }
        if (LauncherApi.machineName)
            return qsTr("%1 · %2 minutes remaining").arg(LauncherApi.machineName).arg(LauncherApi.remainingMinutes)
        return qsTr("Join the shared queue. Your plan determines your priority.")
    }

    function primaryText() {
        if (activationPending()) return qsTr("Ativação em %1").arg(pendingActivationDate())
        if (needsMachine()) return qsTr("Create your VM")
        if (LauncherApi.state === "queued") return qsTr("Leave queue")
        if (LauncherApi.state === "ready") return qsTr("Connect")
        if (LauncherApi.state === "idle") return qsTr("Open machine")
        return qsTr("Please wait")
    }

    function prettifyPlan(slug) {
        if (!slug) return qsTr("Plan")
        var clean = ("" + slug).replace(/-nw$/, "")
        var words = clean.split(/[-_]/)
        var out = []
        for (var i = 0; i < words.length; i++) {
            var w = words[i]
            if (w.length > 0) out.push(w.charAt(0).toUpperCase() + w.slice(1))
        }
        return out.length ? out.join(" ") : slug
    }

    // Linha de saldo do plano da VM em foco (sessão ativa > selecionada > única):
    // "Builder Lite · faltam 87h" / "Builder Lite · horas ilimitadas" (como no site).
    function planHoursText() {
        var list = LauncherApi.machines
        var lines = []
        var id = LauncherApi.statusMachineId || LauncherApi.selectedMachineId
        var m = null
        for (var i = 0; list && i < list.length; i++) {
            if (list[i].id === id) {
                m = list[i]
                break
            }
        }
        if (!m && list && list.length === 1) m = list[0]
        if (m && m.entitlementActive) {
            var name = (m.planName && ("" + m.planName).trim().length > 0)
                    ? m.planName : prettifyPlan(m.planSlug)
            if (m.unlimited) {
                lines.push(qsTr("%1 · unlimited hours").arg(name))
            } else {
                var h = Math.round((m.hoursRemaining || 0) * 10) / 10
                var hStr = (Math.floor(h) === h ? h.toFixed(0) : h.toFixed(1)) + "h"
                var text = qsTr("%1 · %2 left").arg(name).arg(hStr)
                var b = Math.round((m.bonusHours || 0) * 10) / 10
                if (b > 0)
                    text += qsTr(" + %1 bonus").arg(
                                (Math.floor(b) === b ? b.toFixed(0) : b.toFixed(1)) + "h")
                lines.push(text)
            }
        }
        else if (m && !m.upcomingEntitlement) {
            // Plano da VM inativo (expirado/cancelado) — mostra o status como o
            // site faz em "Meus produtos", em vez de deixar a VM travada sem dizer por quê.
            var iname = (m.planName && ("" + m.planName).trim().length > 0)
                    ? m.planName : prettifyPlan(m.planSlug)
            var st = m.entitlementStatus || ""
            var stLabel = st === "grace" ? qsTr("Em carência")
                        : st === "canceled" ? qsTr("Cancelada")
                        : qsTr("Expirada")
            lines.push(qsTr("%1 · %2").arg(iname).arg(stLabel))
        }
        if (LauncherApi.hasUpcomingPlan) {
            lines.push(qsTr("Pré-venda: %1 · ativação em %2")
                       .arg(LauncherApi.upcomingPlanName || qsTr("Plano"))
                       .arg(formatActivationDate(LauncherApi.upcomingPlanAvailableAt)))
        }
        return lines.join("\n")
    }

    Connections {
        target: LauncherApi
        function onConnectionReady(address, machineId, name) {
            addingComputer = true
            pendingConnectAddress = address
            pendingConnectName = name || ""
            // Marca como VM da conta (machineId) — PCs antigos da conta
            // (VM recriada com IP/UUID novo) somem sozinhos em vez de
            // acumular entradas mortas na grade.
            ComputerManager.addNewHostForMachine(address, machineId || "", "launcher")
        }
        // Navegação de login/logout é centralizada no main.qml
        // (window.showLoginView/showLauncherView) — não duplicar aqui.
        function onBugReportFinished(success, message) {
            if (success) {
                bugReportDialog.close()
                bugResultDialog.isError = false
            } else {
                bugResultDialog.isError = true
            }
            bugResultDialog.customTitle = ""
            bugResultDialog.text = message
            bugResultDialog.open()
        }
        function onDiskTransferFinished(success, message) {
            bugResultDialog.isError = !success
            bugResultDialog.customTitle = success ? qsTr("Disco transferido") : ""
            bugResultDialog.text = message
            bugResultDialog.open()
        }
        function onUsbHelperMissing() {
            usbSetupDialog.open()
        }
        function onUsbSessionStarted(machineName) {
            bugResultDialog.isError = false
            bugResultDialog.customTitle = qsTr("USB Passthrough")
            bugResultDialog.text = qsTr("SpaceUSB is open — pick the device and connect. It will show up in %1 as if plugged in directly.").arg(machineName)
            bugResultDialog.open()
        }
    }

    Connections {
        target: ComputerManager
        function onComputerAddCompleted(success, detectedPortBlocking) {
            if (!addingComputer)
                return
            addingComputer = false
            if (success)
                // push (e nao replace) pra manter o Launcher na pilha —
                // assim o PcView mostra o botao de voltar pro inicio.
                stackView.push("qrc:/gui/PcView.qml", {
                    "autoAddress": pendingConnectAddress,
                    "autoName": pendingConnectName
                })
            else {
                connectionErrorText = detectedPortBlocking
                        ? qsTr("Sua rede parece bloquear as portas de streaming. Tente outra conexão ou libere o aplicativo no firewall.")
                        : qsTr("Não foi possível alcançar o host de streaming. Tente novamente em alguns segundos.")
                errorDialog.open()
            }
        }
    }

    Timer {
        interval: 5000
        repeat: true
        // Só faz polling enquanto o Launcher é a tela ativa — com o PcView
        // agora empilhado por cima (push), isso evita refresh em dobro.
        running: StackView.status === StackView.Active
        triggeredOnStart: true
        onTriggered: LauncherApi.refreshStatus()
    }

    Flickable {
        id: launcherScroll
        anchors.fill: parent
        clip: true
        contentWidth: width
        contentHeight: Math.max(height, launcherColumn.implicitHeight + 40)
        ScrollBar.vertical: ScrollBar {}

    ColumnLayout {
        id: launcherColumn
        anchors.horizontalCenter: parent.horizontalCenter
        y: Math.max(20, (launcherScroll.height - implicitHeight) / 2)
        width: Math.min(620, parent.width - 60)
        spacing: 16

        Label {
            text: "SPACE CONNECT"
            color: "#a482fa"
            font.pixelSize: 30
            font.bold: true
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            text: LauncherApi.email
            color: "#9793aa"
            Layout.alignment: Qt.AlignHCenter
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: statusColumn.implicitHeight + 48
            radius: 14
            color: "#110d17"
            border.width: 1
            border.color: "#e8e2ff"

            ColumnLayout {
                id: statusColumn
                anchors.fill: parent
                anchors.margins: 24
                spacing: 14

                Label {
                    visible: (LauncherApi.state === "starting" || LauncherApi.state === "queued") && text.length > 0
                    text: bootText("eyebrow", "")
                    textFormat: Text.PlainText
                    color: "#a993ff"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                Label {
                    text: statusTitle()
                    textFormat: Text.PlainText
                    color: "#f8f5ff"
                    font.pixelSize: 24
                    font.bold: true
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                Label {
                    text: statusDetails()
                    textFormat: Text.PlainText
                    color: "#9793aa"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                Label {
                    visible: (LauncherApi.state === "starting" || LauncherApi.state === "queued") && text.length > 0
                    text: bootText("subtitle", "")
                    textFormat: Text.PlainText
                    color: "#9793aa"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                Repeater {
                    model: (LauncherApi.state === "starting" || LauncherApi.state === "queued")
                            && remoteBootTexts && remoteBootTexts.steps ? remoteBootTexts.steps : []
                    delegate: Label {
                        text: (index + 1) + ". " + modelData
                        textFormat: Text.PlainText
                        color: "#9793aa"
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                }

                Label {
                    visible: LauncherApi.errorMessage.length > 0
                    text: LauncherApi.errorMessage
                    color: "#F87171"
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                // Saldo do plano (horas restantes / ilimitado) — paridade com o
                // site e com o app Android. A binding reavalia sozinha porque lê
                // propriedades com NOTIFY (machines/statusMachineId/selectedMachineId).
                Label {
                    visible: text.length > 0
                    text: planHoursText()
                    color: "#e8c85a"
                    font.bold: true
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }

                // Dica rotativa enquanto a VM liga / está na fila (só aparece nesses estados).
                Rectangle {
                    visible: bootTips.length > 0 && (LauncherApi.state === "starting" || LauncherApi.state === "queued")
                    Layout.fillWidth: true
                    implicitHeight: tipRow.implicitHeight + 20
                    radius: 10
                    color: "#1a1430"
                    border.width: 1
                    border.color: "#4572fa"

                    RowLayout {
                        id: tipRow
                        anchors.fill: parent
                        anchors.margins: 10
                        spacing: 10

                        Label {
                            text: "💡"
                            font.pixelSize: 16
                            Layout.alignment: Qt.AlignTop
                        }
                        Label {
                            text: currentBootTip()
                            textFormat: Text.PlainText
                            color: "#c9c3dd"
                            font.pixelSize: 13
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                        }
                    }
                }

                BusyIndicator {
                    running: LauncherApi.busy || addingComputer
                    visible: running
                    Layout.alignment: Qt.AlignHCenter
                }

                // Seletor de VM pra quem tem 2+ planos/máquinas. O app Android já
                // tinha; o desktop não mandava machineId em nada e o backend
                // abria "a" sessão ativa — usuário multi-plano não escolhia.
                Label {
                    visible: LauncherApi.machines.length > 1
                    text: qsTr("Which PC do you want to open?")
                    color: "#9793aa"
                    Layout.fillWidth: true
                }

                SpaceComboBox {
                    id: machineSelector
                    visible: LauncherApi.machines.length > 1
                    model: LauncherApi.machines
                    textRole: "display"
                    // ComboBox não tem placeholderText (só TextField/TextArea). Atribuir a
                    // propriedade inexistente fazia a LauncherView inteira falhar ao compilar,
                    // o StackView.replace() abortava e o app ficava preso na tela de login.
                    displayText: currentIndex < 0 ? qsTr("Select a machine") : currentText
                    Layout.fillWidth: true

                    function syncSelection() {
                        var idx = -1
                        for (var i = 0; i < LauncherApi.machines.length; i++) {
                            if (LauncherApi.machines[i].id === LauncherApi.selectedMachineId) {
                                idx = i
                                break
                            }
                        }
                        currentIndex = idx
                    }

                    onActivated: function(index) {
                        if (index >= 0 && index < LauncherApi.machines.length)
                            LauncherApi.selectedMachineId = LauncherApi.machines[index].id
                    }

                    Component.onCompleted: syncSelection()

                    Connections {
                        target: LauncherApi
                        function onMachinesChanged() { machineSelector.syncSelection() }
                    }
                }

                // "Criar nova VM": aparece quando o cliente JÁ tem VM mas tem um
                // plano ativo sem máquina (ex.: comprou/ganhou o demo). Sem isso o
                // fluxo de criação só abria pra quem tinha ZERO VMs, então o plano
                // novo ficava invisível no app.
                Button {
                    visible: LauncherApi.hasMachine && LauncherApi.creatablePlans.length > 0
                    text: qsTr("Criar nova VM")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: createMachineDialog.open()
                }

                Button {
                    text: primaryText()
                    highlighted: true
                    enabled: !LauncherApi.busy
                             && !addingComputer
                             && !activationPending()
                             && (needsMachine()
                                 || LauncherApi.state === "idle"
                                 || LauncherApi.state === "queued"
                                 || LauncherApi.state === "ready")
                    Layout.fillWidth: true
                    onClicked: primaryAction()
                }

                Button {
                    text: qsTr("End session")
                    visible: LauncherApi.state === "ready"
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: LauncherApi.endSession()
                }

                Button {
                    text: qsTr("Send file to your PC")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: uploadFileDialog.open()
                }

                Button {
                    // Paridade com o Android: teste de rede/latência (mediana +
                    // jitter + veredito pra cloud gaming).
                    text: qsTr("Test network / latency")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: navigateTo("qrc:/gui/LatencyTestView.qml", "LatencyTestView")
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 10

                    Button {
                        // Amigos (beta): username, pedidos, permissões e máquinas
                        // compartilhadas — estilo Parsec.
                        text: qsTr("Friends")
                        Layout.fillWidth: true
                        onClicked: navigateTo("qrc:/gui/FriendsView.qml", "FriendsView")
                    }

                    Button {
                        // Emuladores: RetroArch e cia instaláveis na VM com 1 clique.
                        text: qsTr("Emuladores")
                        Layout.fillWidth: true
                        onClicked: navigateTo("qrc:/gui/EmulatorsView.qml", "EmulatorsView")
                    }

                    Button {
                        // Downloads: biblioteca de arquivos/links do admin.
                        text: qsTr("Downloads")
                        Layout.fillWidth: true
                        onClicked: navigateTo("qrc:/gui/DownloadsView.qml", "DownloadsView")
                    }

                    Button {
                        // Transferir disco entre VMs (só com 2+ máquinas Proxmox).
                        visible: countProxmoxMachines() >= 2
                        text: qsTr("Transferir disco")
                        Layout.fillWidth: true
                        onClicked: transferDiskDialog.open()
                    }

                    Button {
                        text: qsTr("Report a problem")
                        enabled: !LauncherApi.busy
                        Layout.fillWidth: true
                        onClicked: bugReportDialog.open()
                    }
                }

                Button {
                    // SpaceUSB: usa um dispositivo USB deste PC dentro da VM
                    // (volante, controle, dongle). Só faz sentido com a VM pronta.
                    visible: LauncherApi.state === "ready"
                    text: qsTr("USB Passthrough")
                    enabled: !LauncherApi.busy
                    Layout.fillWidth: true
                    onClicked: LauncherApi.startUsbPassthrough()
                }
            }
        }

        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            Button {
                text: qsTr("Refresh")
                onClicked: LauncherApi.refreshStatus()
            }
            Button {
                text: qsTr("Sign out")
                onClicked: LauncherApi.logout()
            }
        }
    }

    }

    Dialog {
        id: createMachineDialog
        title: qsTr("Criar sua VM")
        standardButtons: Dialog.Cancel
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        modal: true

        // balanceId do plano escolhido (ou do único plano criável). Vazio = deixa
        // o backend decidir (mantém compat. com o fluxo antigo de plano único).
        function selectedBalanceId() {
            var plans = LauncherApi.creatablePlans
            if (!plans || plans.length === 0) return ""
            if (plans.length === 1) return plans[0].balanceId
            var idx = planSelector.currentIndex
            if (idx >= 0 && idx < plans.length) return plans[idx].balanceId
            return plans[0].balanceId
        }

        ColumnLayout {
            width: 340
            spacing: 12

            // Seletor de plano: aparece quando há 2+ planos sem VM (ex.: mensal +
            // demo). Sem isso o cliente não escolhia de qual plano criar a VM — era
            // o que faltava pro plano demo aparecer no app.
            Label {
                visible: LauncherApi.creatablePlans.length > 1
                text: qsTr("De qual plano criar a VM?")
                color: "#9793aa"
                Layout.fillWidth: true
            }
            SpaceComboBox {
                id: planSelector
                visible: LauncherApi.creatablePlans.length > 1
                model: LauncherApi.creatablePlans
                textRole: "display"
                currentIndex: 0
                Layout.fillWidth: true
            }
            // Com um único plano criável, mostramos qual é (sem seletor) pra dar contexto.
            Label {
                visible: LauncherApi.creatablePlans.length === 1
                text: LauncherApi.creatablePlans.length === 1
                      ? qsTr("Plano: %1").arg(LauncherApi.creatablePlans[0].display)
                      : ""
                color: "#e8c85a"
                font.bold: true
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Label {
                text: qsTr("Escolha uma senha para o seu PC (também é usada no login do Windows).")
                wrapMode: Text.WordWrap
                color: "#9793aa"
                Layout.fillWidth: true
            }

            TextField {
                id: machinePasswordField
                echoMode: TextInput.Password
                placeholderText: qsTr("Senha (5-64 caracteres)")
                Layout.fillWidth: true
            }

            Button {
                text: qsTr("Criar VM")
                highlighted: true
                enabled: machinePasswordField.text.length >= 5 && machinePasswordField.text.length <= 64
                Layout.fillWidth: true
                onClicked: {
                    LauncherApi.createMachine(machinePasswordField.text, createMachineDialog.selectedBalanceId())
                    machinePasswordField.text = ""
                    createMachineDialog.close()
                }
            }
        }
    }

    // Quantas máquinas Proxmox o usuário tem (a transferência de disco só faz
    // sentido com 2+, e só entre Proxmox).
    function countProxmoxMachines() {
        var n = 0
        for (var i = 0; i < LauncherApi.machines.length; i++) {
            if (LauncherApi.machines[i].provider === "proxmox") n++
        }
        return n
    }

    // Dialog de transferência de disco entre VMs: exclui a de origem e soma o
    // disco dela (inteiro) na de destino. Avisa que é irreversível.
    Dialog {
        id: transferDiskDialog
        title: qsTr("Transferir disco entre máquinas")
        standardButtons: Dialog.Cancel
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        modal: true

        property string sourceId: ""
        property string targetId: ""

        function proxmoxMachines() {
            var out = []
            for (var i = 0; i < LauncherApi.machines.length; i++) {
                var m = LauncherApi.machines[i]
                if (m.provider === "proxmox") out.push(m)
            }
            return out
        }

        ColumnLayout {
            width: 400
            spacing: 12

            Label {
                text: qsTr("Excluir uma máquina e aproveitar o espaço dela na outra. A de origem é EXCLUÍDA pra sempre (jogos, arquivos, tudo) e a de destino ganha o espaço.")
                wrapMode: Text.WordWrap
                color: "#cfc8e3"
                Layout.fillWidth: true
            }

            Label { text: qsTr("Excluir esta máquina (o disco dela vai embora):"); color: "#9793aa"; font.pixelSize: 11 }
            SpaceComboBox {
                id: transferSourceCombo
                Layout.fillWidth: true
                textRole: "text"
                model: ListModel { id: transferSourceModel }
                function rebuild() {
                    transferSourceModel.clear()
                    transferSourceModel.append({"text": qsTr("Escolher…"), "machineId": ""})
                    var ms = transferDiskDialog.proxmoxMachines()
                    for (var i = 0; i < ms.length; i++) {
                        transferSourceModel.append({
                            "text": (ms[i].name || ms[i].id) + " (" + (ms[i].diskGb || "?") + " GB)",
                            "machineId": ms[i].id
                        })
                    }
                    currentIndex = 0
                    recalculateWidth()
                }
                onActivated: transferDiskDialog.sourceId = transferSourceModel.get(currentIndex).machineId
                Component.onCompleted: rebuild()
            }

            Label { text: qsTr("Receber o espaço nesta máquina:"); color: "#9793aa"; font.pixelSize: 11 }
            SpaceComboBox {
                id: transferTargetCombo
                Layout.fillWidth: true
                textRole: "text"
                model: ListModel { id: transferTargetModel }
                function rebuild() {
                    transferTargetModel.clear()
                    transferTargetModel.append({"text": qsTr("Escolher…"), "machineId": ""})
                    var ms = transferDiskDialog.proxmoxMachines()
                    for (var i = 0; i < ms.length; i++) {
                        transferTargetModel.append({
                            "text": (ms[i].name || ms[i].id) + " (" + (ms[i].diskGb || "?") + " GB)",
                            "machineId": ms[i].id
                        })
                    }
                    currentIndex = 0
                    recalculateWidth()
                }
                onActivated: transferDiskDialog.targetId = transferTargetModel.get(currentIndex).machineId
                Component.onCompleted: rebuild()
            }

            Label {
                id: transferConfirmLabel
                visible: false
                text: qsTr("⚠️ Isso é irreversível. A máquina de origem vai ser excluída pra sempre. Confirma?")
                wrapMode: Text.WordWrap
                color: "#f87171"
                Layout.fillWidth: true
            }

            Button {
                id: transferConfirmButton
                highlighted: true
                Layout.fillWidth: true
                enabled: transferDiskDialog.sourceId !== "" && transferDiskDialog.targetId !== "" && transferDiskDialog.sourceId !== transferDiskDialog.targetId
                property bool armed: false
                text: armed ? qsTr("Confirmar exclusão e transferência") : qsTr("Transferir disco")
                onClicked: {
                    if (!armed) {
                        armed = true
                        transferConfirmLabel.visible = true
                    } else {
                        LauncherApi.transferDisk(transferDiskDialog.sourceId, transferDiskDialog.targetId)
                        armed = false
                        transferConfirmLabel.visible = false
                        transferDiskDialog.close()
                    }
                }
            }
        }

        onOpened: {
            transferSourceCombo.rebuild()
            transferTargetCombo.rebuild()
            sourceId = ""
            targetId = ""
            transferConfirmLabel.visible = false
            transferConfirmButton.armed = false
        }
    }

    Dialog {
        id: errorDialog
        title: qsTr("Falha na conexão")
        standardButtons: Dialog.Ok
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        Label {
            text: connectionErrorText
            wrapMode: Text.WordWrap
        }
    }

    Dialog {
        id: bugReportDialog
        title: qsTr("Report a bug")
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok | Dialog.Cancel
        onOpened: bugReportText.forceActiveFocus()
        onAccepted: {
            LauncherApi.reportBug(bugReportText.text, LauncherApi.email)
            bugReportText.text = ""
        }

        ColumnLayout {
            width: 340
            spacing: 10

            Label {
                text: qsTr("Tell us what happened. We include your app version and machine status automatically.")
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            TextArea {
                id: bugReportText
                placeholderText: qsTr("Describe the problem (what you did, what happened)...")
                wrapMode: TextArea.Wrap
                Layout.fillWidth: true
                Layout.preferredHeight: 120
            }
        }
    }

    Dialog {
        id: bugResultDialog
        property bool isError: false
        property string customTitle: ""
        property alias text: launcherBugResultLabel.text
        title: customTitle.length > 0 ? customTitle : (isError ? qsTr("Could not send") : qsTr("Report sent"))
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok

        Label {
            id: launcherBugResultLabel
            wrapMode: Text.WordWrap
            width: 300
        }
    }

    Labs.FileDialog {
        id: uploadFileDialog
        title: qsTr("Choose a file to send")
        fileMode: Labs.FileDialog.OpenFile
        onAccepted: LauncherApi.uploadFileToVm(file.toString())
    }

    // Oferecido quando o usuário clica em "USB Passthrough" sem o helper instalado.
    Dialog {
        id: usbSetupDialog
        title: qsTr("USB Passthrough — SpaceUSB")
        modal: true
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        standardButtons: Dialog.Ok | Dialog.Cancel
        onAccepted: Qt.openUrlExternally("https://downloads.spacecloud.gg/SpaceUSB.exe")

        ColumnLayout {
            width: 360
            spacing: 10

            Label {
                text: qsTr("To use a USB device from this PC (wheel, controller, dongle) inside your cloud machine, install the free SpaceUSB helper once.")
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            Label {
                text: qsTr("It installs to AppData (no admin needed day to day). After installing, click USB Passthrough again with your machine running.")
                wrapMode: Text.WordWrap
                color: "#9793aa"
                Layout.fillWidth: true
            }
            Label {
                text: qsTr("OK = download SpaceUSB now")
                color: "#4572fa"
                Layout.fillWidth: true
            }
        }
    }

    Dialog {
        id: uploadSuccessDialog
        title: qsTr("File sent")
        standardButtons: Dialog.Ok
        parent: Overlay.overlay
        x: Math.round((parent.width - width) / 2)
        y: Math.round((parent.height - height) / 2)
        property string fileName: ""
        Label {
            text: qsTr("%1 was sent to the Downloads folder on your PC.").arg(uploadSuccessDialog.fileName)
            wrapMode: Text.WordWrap
        }
    }

    Connections {
        target: LauncherApi
        function onFileUploadSucceeded(fileName) {
            uploadSuccessDialog.fileName = fileName
            uploadSuccessDialog.open()
        }
    }
}
