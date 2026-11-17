package com.limelight.account;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Cobre {@link MachineManagementActivity#canPickDisplayMode(boolean, boolean, boolean)}.
 *
 * Pedido do Davi: dá pra selecionar o Sudo Maker (modo Desempenho) ou o Padrão A
 * QUALQUER MOMENTO, inclusive com a VM ligada — igual ao site. Antes o seletor ficava
 * travado quando a máquina estava ligada ({@code !powered}); isso foi removido. O host
 * aplica a escolha no próximo connect, então trocar com a VM ligada é válido (vale ao
 * reconectar). O seletor só bloqueia durante operação, fila ou lock.
 */
public class MachineDisplayModeTest {

    @Test public void canPickWhenIdleAndFree() {
        assertTrue(MachineManagementActivity.canPickDisplayMode(false, false, false));
    }

    @Test public void canPickWhilePoweredOn() {
        // Comportamento NOVO (regressão principal): a VM ligada NÃO bloqueia mais a troca.
        // "powered" nem é mais parâmetro — uma máquina ligada e livre de operação/fila/lock
        // continua com working=false/queued=false/locked=false e DEVE poder trocar.
        assertTrue(MachineManagementActivity.canPickDisplayMode(false, false, false));
    }

    @Test public void cannotPickDuringOperation() {
        assertFalse(MachineManagementActivity.canPickDisplayMode(true, false, false));
    }

    @Test public void cannotPickWhileQueued() {
        assertFalse(MachineManagementActivity.canPickDisplayMode(false, true, false));
    }

    @Test public void cannotPickWhileLocked() {
        assertFalse(MachineManagementActivity.canPickDisplayMode(false, false, true));
    }
}
