const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../gui/LauncherView.qml"), "utf8");
const context = vm.createContext({
  remoteBootTexts: {
    queueTitle: "Fila {position}/{total}",
    queueSearching: "Buscando a sua VM",
    bootingTitle: "Iniciando texto editado",
    phases: { starting: "Windows inicializando" },
    reasons: { capacity: "Aguarde uma vaga" },
    takingLonger: "{reason} Continuamos tentando.",
  },
  LauncherApi: { state: "queued", queuePosition: 2, queueTotal: 4, bootElapsedSeconds: 10, queueBlockedReason: "capacity", creationPhase: "starting" },
  bootTips: [], bootTipIndex: 50,
  activationPending: () => false, needsMachine: () => false,
});
vm.runInContext('function qsTr(text) { return text; } String.prototype.arg = function(value) { return this.replace(/%\\d/, value); };', context);
for (const name of ["bootText", "queueTitle", "statusTitle", "creationPhaseLabel", "statusDetails", "currentBootTip", "primaryAction", "primaryText"]) {
  const found = source.match(new RegExp(`    function ${name}\\([^]*?\\n    }`));
  assert.ok(found, `missing QML handler ${name}`);
  vm.runInContext(found[0], context);
}
assert.equal(context.statusTitle(), "Fila 2/4");
assert.equal(context.statusDetails(), "Aguarde uma vaga");
context.LauncherApi.bootElapsedSeconds = 61;
assert.equal(context.statusDetails(), "Aguarde uma vaga Continuamos tentando.");
context.LauncherApi.state = "starting";
assert.equal(context.statusTitle(), "Iniciando texto editado");
assert.equal(context.statusDetails(), "Windows inicializando Continuamos tentando.");
assert.equal(context.currentBootTip(), "", "empty tips hide instead of displaying undefined");
context.bootTips = ["Uma dica"];
assert.equal(context.currentBootTip(), "Uma dica", "index is bounded after config refresh");
context.remoteBootTexts = {};
assert.equal(context.creationPhaseLabel("starting"), "Starting the operating system", "old server retains translated fallback");
let opened = 0;
context.sessionOrchestrator = { startDesktopSession: () => opened++ };
context.LauncherApi.leaveQueue = () => { throw new Error("Open machine must not cancel an existing queue"); };
for (const state of ["idle", "queued", "starting", "ready"]) {
  context.LauncherApi.state = state;
  assert.equal(context.primaryText(), "Open machine");
  context.primaryAction();
}
assert.equal(opened, 4, "queued/booting dashboards resume the loading flow");
console.log("PASS: actual QML handlers use admin titles, phases, reasons and empty tips safely");
