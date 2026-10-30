pragma Singleton
import QtQuick 2.15

QtObject {
    property var management: ({machines: [], volumes: [], operations: []})
    property string managementError: ""
    property bool managementBusy: false
    property var actions: []
    property int sequence: 0
    signal managementActionFinished(bool ok, string message)
    signal machinePasswordFinished(bool ok, string message, string applied, string machineId)
    function refreshManagement() {}
    function newOperationRequestId() { sequence++; return "request-" + sequence }
    function machineAction(machineId, kind, options) {
        actions = actions.concat([{machineId: machineId, kind: kind, options: options}])
    }
    function retryOperation(id) { actions = actions.concat([{retry: id}]) }
    function setMachinePassword(machineId, password) {
        actions = actions.concat([{machineId: machineId, kind: "password"}])
    }
}
