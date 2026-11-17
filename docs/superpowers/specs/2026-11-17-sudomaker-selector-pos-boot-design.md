# Design — Seletor de modo de vídeo (Sudo Maker) disponível pós-boot + banner

Data: 2026-11-17
Autor: SpaceOS (a pedido do Davi)
Tipo: small change (bugfix de paridade com o site + banner informativo)

## Intenção / por quê
Davi: "a parte do sudomaker pra mobile e pra pc não dá pra selecionar depois, não tem
como abrir com ele após a máquina ser ligada, eu quero que seja possível selecionar ele
ou o outro tranquilamente... assim como tem no site que fica lá sempre, se não tiver
ficando lá, faça ficar, coloca o banner dele aí."

"Sudo Maker" = modo **Desempenho** (`displayMode: 'performance'`) = monitor virtual
Apollo/SudoVDA → +120 FPS, 4K e resoluções custom. "O outro" = **Padrão**
(`displayMode: 'standard'`) = NVIDIA VGX fixo ~1080p (default, zero risco).

## Causa raiz (systematic-debugging)
O seletor **já existe** nos dois apps, mas está travado em "VM desligada":
- **Android** (`MachineManagementActivity.java` L142): `canPickMode = available &&
  !powered && machine.queue == null && !machine.locked` — o `!powered` trava.
- **Desktop** (`LauncherView.qml` L631): `displayModeCard.visible: ... &&
  LauncherApi.state === "idle" && ...` — some quando há sessão (ligada).
- **Backend NÃO trava**: ambas as rotas (`/launcher/v1/machines/:id/display-mode` e
  `/user/machines/:id/display-mode`) só validam o enum e salvam `machine.displayMode`.
- **Site NÃO trava** por `powered` (só desabilita durante operação) → é a referência
  "que fica lá sempre" que o Davi cita.
- Mecanismo real: `applyMachineDisplayMode` roda em TODO `getConnection()`
  (`launcher-runtime.service.ts:352`). Logo, trocar o modo + reconectar aplica o novo
  display — **sem precisar reiniciar a VM**. É NO-OP seguro quando standard+nunca-ativado.

## Goals
- Deixar o usuário trocar entre Padrão/Desempenho a qualquer momento nos dois apps,
  inclusive com a VM ligada (paridade com o site).
- Mensagem clara: "vale quando você (re)conectar à máquina" (não é instantâneo no stream
  em andamento; exige reconectar).
- Banner informativo do Sudo Maker perto do seletor (apps + site).

## Non-goals / anti-goals
- NÃO mudar o backend (já suporta troca com VM ligada).
- NÃO aplicar o modo ao vivo num stream em andamento (impossível sem reconectar — não
  prometer isso).
- NÃO liberar troca durante boot/fila/operação/shutdown (evita corrida com o provisioning).

## Abordagens consideradas
1. **Destravar o gate de UI nos apps + alinhar mensagem (escolhida).** Menor mudança,
   paridade exata com o site, backend intocado. Risco baixo (só remove uma condição de
   visibilidade/enabled; os guards de operação/fila continuam).
2. Aplicar o modo ao vivo no stream em andamento. Rejeitada: Apollo lê
   `always_use_virtual_display` só ao iniciar a sessão de streaming; exigiria recriar a
   sessão no meio do jogo (pior UX, risco alto).
3. Mover o seletor pra dentro da tela de stream. Rejeitada: fora do padrão Moonlight,
   duplicaria UI e o backend já aplica no connect.

## O que muda (decisões)
- **Android:** `canPickMode` perde o `!powered` (mantém `available`, `queue == null`,
  `!locked`). Hint vira: ligada → "reconecte pro novo modo valer"; desligada → "vale
  quando conectar". Banner novo acima do seletor (drawable roxo + texto promo).
- **Desktop:** `displayModeCard.visible` passa a mostrar em `idle` OU `ready` (exclui
  `starting`/`queued`/`ending`). Hint/label atualizado. Banner no topo do card.
- **Site:** o seletor já fica sempre no card expandido (confirmado). Ajustar o texto de
  ajuda pra "a qualquer momento" + adicionar o banner do Sudo Maker.
- **Release:** v0.3.58 (apps). Site = deploy direto. Backend = sem mudança.

## Banner ("banner dele")
Não existe asset de imagem do Sudo Maker em nenhum repo. Default: banner informativo
(texto estilizado com acento roxo da marca) destacando os benefícios. Se o Davi tiver um
gráfico específico, trocar é barato (1 asset).

## Deixado ao builder
Redação final dos textos (PT/EN/ES) e detalhes visuais do banner, dentro da identidade.
