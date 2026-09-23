package com.limelight.account;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.AddComputerManually;
import com.limelight.utils.UiHelper;

/**
 * Amigos (beta) — estilo Parsec: username público, pedidos de amizade, permissões
 * por amigo ("mostrar minha máquina" / "deixar conectar") e máquinas que amigos
 * compartilham comigo (conecto se tiver permissão — input via stream).
 * Mesma base do site (spacecloud.gg → Social): /launcher/v1/friends.
 */
public class FriendsActivity extends Activity {

    private static final int ADD_COMPUTER_REQUEST = 1;

    private LinearLayout incomingBox, friendsBox, machinesBox;
    private TextView myUsernameText, emptyText;
    private EditText addField;
    private ProgressBar progress;
    private boolean busy;
    private String pendingFriendHost;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ADD_COMPUTER_REQUEST) {
            return;
        }
        if (resultCode != RESULT_OK || pendingFriendHost == null) {
            // Não conseguiu registrar o host (VM ainda subindo, porta bloqueada, etc.):
            // não abre o PcView pra não cair na grade com o PC errado.
            pendingFriendHost = null;
            return;
        }
        Intent pcView = new Intent(FriendsActivity.this, PcView.class);
        pcView.putExtra(PcView.AUTO_OPEN_ADDRESS_EXTRA, pendingFriendHost);
        pendingFriendHost = null;
        startActivity(pcView);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friends);
        UiHelper.applyStatusBarInset(findViewById(R.id.friendsRoot));

        incomingBox = findViewById(R.id.friendsIncoming);
        friendsBox = findViewById(R.id.friendsList);
        machinesBox = findViewById(R.id.friendsMachines);
        myUsernameText = findViewById(R.id.friendsMyUsername);
        emptyText = findViewById(R.id.friendsEmpty);
        addField = findViewById(R.id.friendsAddField);
        progress = findViewById(R.id.friendsProgress);

        findViewById(R.id.friendsBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.friendsAddButton).setOnClickListener(v -> addFriend());
        findViewById(R.id.friendsEditUsername).setOnClickListener(v -> promptUsername());

        reload();
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        setBusy(true);
        AccountManager.getFriends(this, new AccountManager.ResultCallback<SpaceConnectApiClient.FriendsResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.FriendsResponse result) {
                renderFriends(result);
                loadMachines();
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(FriendsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadMachines() {
        AccountManager.getFriendMachines(this, new AccountManager.ResultCallback<SpaceConnectApiClient.FriendMachinesResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.FriendMachinesResponse result) {
                setBusy(false);
                renderMachines(result != null ? result.machines : null);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
            }
        });
    }

    private static String profileLabel(SpaceConnectApiClient.PublicProfile p) {
        if (p == null) return "?";
        return p.username != null && !p.username.isEmpty() ? "@" + p.username
                : (p.name != null && !p.name.isEmpty() ? p.name : "Sem nome");
    }

    private void renderFriends(SpaceConnectApiClient.FriendsResponse data) {
        myUsernameText.setText(data != null && data.me != null && data.me.username != null
                ? "@" + data.me.username
                : getString(R.string.friends_no_username));

        incomingBox.removeAllViews();
        friendsBox.removeAllViews();

        int friendCount = data != null && data.friends != null ? data.friends.length : 0;
        int incomingCount = data != null && data.incoming != null ? data.incoming.length : 0;
        int outgoingCount = data != null && data.outgoing != null ? data.outgoing.length : 0;
        emptyText.setVisibility(friendCount == 0 && incomingCount == 0 ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = getLayoutInflater();

        if (data != null && data.incoming != null) {
            for (final SpaceConnectApiClient.FriendRequestEntry req : data.incoming) {
                View row = inflater.inflate(R.layout.item_friend_request, incomingBox, false);
                ((TextView) row.findViewById(R.id.friendRequestName)).setText(profileLabel(req));
                row.findViewById(R.id.friendRequestAccept).setOnClickListener(v -> act(() ->
                        AccountManager.acceptFriendRequest(FriendsActivity.this, req.requestId, simpleCb(getString(R.string.friends_accepted_toast)))));
                row.findViewById(R.id.friendRequestDecline).setOnClickListener(v -> act(() ->
                        AccountManager.declineFriendRequest(FriendsActivity.this, req.requestId, simpleCb(null))));
                incomingBox.addView(row);
            }
        }

        if (data != null && data.friends != null) {
            for (final SpaceConnectApiClient.FriendEntry friend : data.friends) {
                View row = inflater.inflate(R.layout.item_friend, friendsBox, false);
                ((TextView) row.findViewById(R.id.friendName)).setText(profileLabel(friend));

                final Button showBtn = row.findViewById(R.id.friendToggleShow);
                final Button connectBtn = row.findViewById(R.id.friendToggleConnect);
                renderPermButtons(showBtn, connectBtn, friend);

                showBtn.setOnClickListener(v -> act(() -> AccountManager.setFriendPermissions(
                        FriendsActivity.this, friend.userId, !friend.showMachine, false, simpleCb(null))));
                connectBtn.setOnClickListener(v -> {
                    if (!friend.showMachine) {
                        Toast.makeText(this, R.string.friends_show_first, Toast.LENGTH_LONG).show();
                        return;
                    }
                    act(() -> AccountManager.setFriendPermissions(
                            FriendsActivity.this, friend.userId, true, !friend.allowConnect, simpleCb(null)));
                });
                row.findViewById(R.id.friendRemove).setOnClickListener(v ->
                        new AlertDialog.Builder(this)
                                .setMessage(getString(R.string.friends_remove_confirm, profileLabel(friend)))
                                .setNegativeButton(R.string.game_menu_cancel, null)
                                .setPositiveButton(R.string.friends_remove, (d, w) -> act(() ->
                                        AccountManager.removeFriend(FriendsActivity.this, friend.userId, simpleCb(null))))
                                .show());

                // Por máquina (override vence o global) — só quando eu tenho 2+ VMs.
                LinearLayout perMachineBox = row.findViewById(R.id.friendPerMachine);
                if (data.myMachines != null && data.myMachines.length > 1 && perMachineBox != null) {
                    perMachineBox.setVisibility(View.VISIBLE);
                    perMachineBox.removeAllViews();
                    for (final SpaceConnectApiClient.MyMachine mm : data.myMachines) {
                        SpaceConnectApiClient.PerMachine per = friend.perMachine != null ? friend.perMachine.get(mm.machineId) : null;
                        boolean showOn = per != null && per.showMachine != null ? per.showMachine : friend.showMachine;
                        boolean connectOn = per != null && per.allowConnect != null ? per.allowConnect : friend.allowConnect;

                        View mrow = inflater.inflate(R.layout.item_friend_machine_perm, perMachineBox, false);
                        ((TextView) mrow.findViewById(R.id.permMachineName)).setText(
                                (mm.name != null ? mm.name : "VM") + (mm.running ? "" : " (" + getString(R.string.friends_machine_off_short) + ")"));
                        final Button mShow = mrow.findViewById(R.id.permShow);
                        final Button mConnect = mrow.findViewById(R.id.permConnect);
                        mShow.setText(showOn ? R.string.friends_show_on_short : R.string.friends_show_off_short);
                        mConnect.setText(connectOn ? R.string.friends_connect_on_short : R.string.friends_connect_off_short);
                        mConnect.setEnabled(showOn);
                        mConnect.setAlpha(showOn ? 1.0f : 0.4f);
                        mShow.setOnClickListener(v -> act(() -> AccountManager.setFriendMachinePermission(
                                FriendsActivity.this, friend.userId, mm.machineId, !showOn, false, simpleCb(null))));
                        mConnect.setOnClickListener(v -> act(() -> AccountManager.setFriendMachinePermission(
                                FriendsActivity.this, friend.userId, mm.machineId, true, !connectOn, simpleCb(null))));
                        perMachineBox.addView(mrow);
                    }
                }

                friendsBox.addView(row);
            }
        }

        if (data != null && data.outgoing != null && data.outgoing.length > 0) {
            TextView sent = new TextView(this);
            StringBuilder sb = new StringBuilder(getString(R.string.friends_sent_title) + " ");
            for (int i = 0; i < data.outgoing.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(profileLabel(data.outgoing[i]));
            }
            sent.setText(sb.toString());
            sent.setTextSize(12);
            sent.setTextColor(0xFF9793AA);
            friendsBox.addView(sent);
        }
    }

    private void renderPermButtons(Button showBtn, Button connectBtn, SpaceConnectApiClient.FriendEntry friend) {
        showBtn.setText(friend.showMachine ? R.string.friends_show_on : R.string.friends_show_off);
        connectBtn.setText(friend.allowConnect ? R.string.friends_connect_on : R.string.friends_connect_off);
        connectBtn.setEnabled(friend.showMachine);
        connectBtn.setAlpha(friend.showMachine ? 1.0f : 0.4f);
    }

    private void renderMachines(SpaceConnectApiClient.FriendMachine[] machines) {
        machinesBox.removeAllViews();
        if (machines == null || machines.length == 0) return;

        LayoutInflater inflater = getLayoutInflater();
        for (final SpaceConnectApiClient.FriendMachine m : machines) {
            View row = inflater.inflate(R.layout.item_friend_machine, machinesBox, false);
            ((TextView) row.findViewById(R.id.friendMachineName)).setText(m.name != null ? m.name : "VM");
            ((TextView) row.findViewById(R.id.friendMachineMeta)).setText(getString(
                    m.running ? R.string.friends_machine_on : R.string.friends_machine_off,
                    profileLabel(m.owner)));
            Button connectBtn = row.findViewById(R.id.friendMachineConnect);
            connectBtn.setEnabled(m.canConnect);
            connectBtn.setAlpha(m.canConnect ? 1.0f : 0.4f);
            connectBtn.setOnClickListener(v -> connectFriend(m));
            machinesBox.addView(row);
        }
    }

    // Conecta na VM do amigo: o host:porta vem da rota friend-aware e o PIN de
    // pareamento também (valida permissão no backend). O stream dá mouse/teclado/
    // gamepad pro amigo — estilo Parsec.
    private void connectFriend(final SpaceConnectApiClient.FriendMachine m) {
        if (busy) return;
        setBusy(true);
        AccountManager.getFriendConnection(this, m.machineId,
                new AccountManager.ResultCallback<SpaceConnectApiClient.ConnectionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.ConnectionResponse connection) {
                setBusy(false);
                String address = connection.host;
                if (TextUtils.isEmpty(address)) address = connection.ipv6;
                if (TextUtils.isEmpty(address) || connection.port <= 0) {
                    Toast.makeText(FriendsActivity.this, R.string.launcher_connection_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                address = address.trim();
                if (address.indexOf(':') >= 0 && !address.startsWith("[")) {
                    address = "[" + address + "]";
                }
                String host = address + ":" + connection.port;

                SharedPreferences prefs = getSharedPreferences("space_connect_launcher", MODE_PRIVATE);
                prefs.edit()
                        .putString("pending_friend_machine", m.machineId)
                        .putString("last_host", host)
                        .apply();

                if (connection.maxBitrateKbps > 0) {
                    android.preference.PreferenceManager.getDefaultSharedPreferences(FriendsActivity.this)
                            .edit()
                            .putInt("launcher_max_bitrate_kbps", connection.maxBitrateKbps)
                            .putInt("launcher_recommended_bitrate_kbps", connection.recommendedBitrateKbps)
                            .apply();
                }

                // Antes isso abria o PcView direto, mas o PC do amigo nunca tinha sido
                // registrado no ComputerManagerService: a tela mostrava "Nenhum PC ainda"
                // (ou a grade com o PRÓPRIO PC do usuário). Mesmo fluxo já usado no
                // desktop (FriendsView.qml): registra o host e só então abre, direto nele.
                pendingFriendHost = host;
                Intent addComputer = new Intent(FriendsActivity.this, AddComputerManually.class);
                addComputer.putExtra(AddComputerManually.EXTRA_AUTO_HOST, host);
                startActivityForResult(addComputer, ADD_COMPUTER_REQUEST);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(FriendsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void addFriend() {
        final String username = addField.getText().toString().trim().toLowerCase();
        if (username.length() < 3) {
            Toast.makeText(this, R.string.friends_username_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        act(() -> AccountManager.addFriend(FriendsActivity.this, username,
                new AccountManager.ResultCallback<SpaceConnectApiClient.FriendActionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.FriendActionResponse result) {
                setBusy(false);
                addField.setText("");
                Toast.makeText(FriendsActivity.this,
                        result != null && "accepted".equals(result.status)
                                ? R.string.friends_added_instant : R.string.friends_added_sent,
                        Toast.LENGTH_LONG).show();
                reload();
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(FriendsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void promptUsername() {
        final EditText input = new EditText(this);
        input.setHint(R.string.friends_username_hint);
        input.setSingleLine(true);
        input.setPadding(32, 24, 32, 24);
        new AlertDialog.Builder(this)
                .setTitle(R.string.friends_username_title)
                .setMessage(R.string.friends_username_message)
                .setView(input)
                .setNegativeButton(R.string.game_menu_cancel, null)
                .setPositiveButton(R.string.launcher_confirm, (d, w) -> {
                    String username = input.getText().toString().trim().toLowerCase();
                    if (username.length() < 3) {
                        Toast.makeText(this, R.string.friends_username_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    act(() -> AccountManager.setUsername(FriendsActivity.this, username, simpleCb(null)));
                })
                .show();
    }

    private interface FriendAction {
        void run();
    }

    private void act(FriendAction action) {
        if (busy) return;
        setBusy(true);
        action.run();
    }

    private AccountManager.ResultCallback<SpaceConnectApiClient.FriendActionResponse> simpleCb(final String toastText) {
        return new AccountManager.ResultCallback<SpaceConnectApiClient.FriendActionResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.FriendActionResponse result) {
                setBusy(false);
                if (toastText != null) Toast.makeText(FriendsActivity.this, toastText, Toast.LENGTH_LONG).show();
                reload();
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                Toast.makeText(FriendsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
    }
}
