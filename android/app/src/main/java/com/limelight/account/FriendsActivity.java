package com.limelight.account;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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

    private LinearLayout incomingBox, friendsBox, machinesBox, searchResultBox;
    private TextView myNameText, myUsernameText, emptyText, searchNameText, searchUsernameText, searchHintText;
    private ImageView myAvatar, searchAvatar;
    private Button searchAction, requestToggle;
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
        searchResultBox = findViewById(R.id.friendsSearchResult);
        myNameText = findViewById(R.id.friendsMyName);
        myUsernameText = findViewById(R.id.friendsMyUsername);
        myAvatar = findViewById(R.id.friendsMyAvatar);
        searchNameText = findViewById(R.id.friendsSearchName);
        searchUsernameText = findViewById(R.id.friendsSearchUsername);
        searchHintText = findViewById(R.id.friendsSearchHint);
        searchAvatar = findViewById(R.id.friendsSearchAvatar);
        searchAction = findViewById(R.id.friendsSearchAction);
        requestToggle = findViewById(R.id.friendsRequestToggle);
        emptyText = findViewById(R.id.friendsEmpty);
        addField = findViewById(R.id.friendsAddField);
        progress = findViewById(R.id.friendsProgress);

        findViewById(R.id.friendsBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.friendsAddButton).setOnClickListener(v -> searchFriend());
        findViewById(R.id.friendsEditUsername).setOnClickListener(v -> promptUsername());
        requestToggle.setOnClickListener(v -> updateFriendRequestSettings(
                !Boolean.TRUE.equals(requestToggle.getTag())));
        addField.setSingleLine(true);
        addField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        addField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchFriend();
                return true;
            }
            return false;
        });
        addField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchResult();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

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

    private String profileName(SpaceConnectApiClient.PublicProfile p) {
        if (p == null) return getString(R.string.friends_no_name);
        if (!TextUtils.isEmpty(p.name)) return p.name.trim();
        if (!TextUtils.isEmpty(p.username)) return "@" + p.username.trim();
        return getString(R.string.friends_no_name);
    }

    private static String profileUsername(SpaceConnectApiClient.PublicProfile p) {
        return p != null && !TextUtils.isEmpty(p.username) ? "@" + p.username.trim() : "";
    }

    private static boolean acceptsFriendRequests(SpaceConnectApiClient.PublicProfile profile) {
        return profile == null || profile.acceptsFriendRequests == null || profile.acceptsFriendRequests;
    }

    private String profileLabel(SpaceConnectApiClient.PublicProfile p) {
        String username = profileUsername(p);
        String name = profileName(p);
        return !username.isEmpty() && !username.equals(name) ? name + " (" + username + ")" : name;
    }

    private void bindProfile(View root, SpaceConnectApiClient.PublicProfile profile,
                             int avatarId, int nameId, int usernameId, int roleId) {
        AvatarLoader.load((ImageView) root.findViewById(avatarId), profile);
        String name = profileName(profile);
        ((TextView) root.findViewById(nameId)).setText(name);
        TextView username = root.findViewById(usernameId);
        String value = profileUsername(profile);
        username.setText(value);
        username.setVisibility(value.isEmpty() || value.equals(name) ? View.GONE : View.VISIBLE);
        ((RgbRoleBadgeView) root.findViewById(roleId)).setRole(profile != null ? profile.staffRole : null);
    }

    private void renderFriends(SpaceConnectApiClient.FriendsResponse data) {
        SpaceConnectApiClient.PublicProfile me = data != null ? data.me : null;
        myNameText.setText(me != null && !TextUtils.isEmpty(me.name)
                ? me.name.trim()
                : getString(R.string.friends_your_profile));
        myUsernameText.setText(data != null && data.me != null && data.me.username != null
                ? "@" + data.me.username
                : getString(R.string.friends_no_username));
        AvatarLoader.load(myAvatar, me);
        ((RgbRoleBadgeView) findViewById(R.id.friendsMyRole)).setRole(me != null ? me.staffRole : null);
        boolean staff = me != null && !TextUtils.isEmpty(me.staffRole);
        boolean acceptsRequests = acceptsFriendRequests(me);
        requestToggle.setVisibility(staff ? View.VISIBLE : View.GONE);
        requestToggle.setTag(acceptsRequests);
        requestToggle.setText(acceptsRequests
                ? R.string.friends_receive_requests_on
                : R.string.friends_receive_requests_off);

        incomingBox.removeAllViews();
        friendsBox.removeAllViews();

        int friendCount = data != null && data.friends != null ? data.friends.length : 0;
        int incomingCount = data != null && data.incoming != null ? data.incoming.length : 0;
        emptyText.setVisibility(friendCount == 0 && incomingCount == 0 ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = getLayoutInflater();

        if (data != null && data.incoming != null) {
            for (final SpaceConnectApiClient.FriendRequestEntry req : data.incoming) {
                View row = inflater.inflate(R.layout.item_friend_request, incomingBox, false);
                bindProfile(row, req, R.id.friendRequestAvatar,
                        R.id.friendRequestName, R.id.friendRequestUsername, R.id.friendRequestRole);
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
                bindProfile(row, friend, R.id.friendAvatar, R.id.friendName,
                        R.id.friendUsername, R.id.friendRole);

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
            TextView sentTitle = new TextView(this);
            sentTitle.setText(R.string.friends_sent_title);
            sentTitle.setTextSize(13);
            sentTitle.setTextColor(0xFFF8F5FF);
            sentTitle.setPadding(0, 12, 0, 8);
            friendsBox.addView(sentTitle);
            for (SpaceConnectApiClient.FriendRequestEntry outgoing : data.outgoing) {
                View row = inflater.inflate(R.layout.item_friend_outgoing, friendsBox, false);
                bindProfile(row, outgoing, R.id.friendOutgoingAvatar,
                        R.id.friendOutgoingName, R.id.friendOutgoingUsername, R.id.friendOutgoingRole);
                friendsBox.addView(row);
            }
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
            AvatarLoader.load((ImageView) row.findViewById(R.id.friendMachineAvatar), m.owner);
            ((RgbRoleBadgeView) row.findViewById(R.id.friendMachineRole))
                    .setRole(m.owner != null ? m.owner.staffRole : null);
            String displayName = (m.accountName != null && !m.accountName.trim().isEmpty())
                    ? m.accountName : (m.name != null ? m.name : "VM");
            ((TextView) row.findViewById(R.id.friendMachineName)).setText(displayName);
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
                // Guarda o vínculo host -> (machineId, apelido) igual ao fluxo da
                // própria VM — o PcView usa isso pra mostrar o mesmo nome que o
                // dono definiu em vez do hostname genérico do Apollo.
                AccountManager.rememberMachineLink(host, m.machineId, m.accountName);

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

    private String normalizedSearchUsername() {
        String username = addField.getText().toString().trim().toLowerCase();
        return username.startsWith("@") ? username.substring(1) : username;
    }

    private void searchFriend() {
        final String username = normalizedSearchUsername();
        if (username.length() < 3) {
            Toast.makeText(this, R.string.friends_username_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        act(() -> AccountManager.searchFriend(FriendsActivity.this, username,
                new AccountManager.ResultCallback<SpaceConnectApiClient.FriendSearchResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.FriendSearchResponse result) {
                setBusy(false);
                renderSearchResult(result);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                clearSearchResult();
                Toast.makeText(FriendsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void renderSearchResult(SpaceConnectApiClient.FriendSearchResponse result) {
        if (result == null || result.profile == null) {
            clearSearchResult();
            return;
        }

        searchResultBox.setVisibility(View.VISIBLE);
        AvatarLoader.load(searchAvatar, result.profile);
        String searchName = profileName(result.profile);
        String searchUsername = profileUsername(result.profile);
        searchNameText.setText(searchName);
        searchUsernameText.setText(searchUsername);
        searchUsernameText.setVisibility(searchUsername.isEmpty() || searchUsername.equals(searchName)
                ? View.GONE : View.VISIBLE);
        ((RgbRoleBadgeView) findViewById(R.id.friendsSearchRole)).setRole(result.profile.staffRole);

        String relationship = result.relationship != null ? result.relationship : "none";
        boolean requestsBlocked = "none".equals(relationship) && !acceptsFriendRequests(result.profile);
        boolean actionable = ("none".equals(relationship) && !requestsBlocked) || "incoming".equals(relationship);
        int label;
        if (requestsBlocked) {
            label = R.string.friends_search_blocked;
        } else {
            switch (relationship) {
                case "self":
                    label = R.string.friends_search_self;
                    break;
                case "friends":
                    label = R.string.friends_search_friends;
                    break;
                case "outgoing":
                    label = R.string.friends_search_outgoing;
                    break;
                case "incoming":
                    label = R.string.friends_search_incoming;
                    break;
                default:
                    label = R.string.friends_add;
                    break;
            }
        }
        searchHintText.setText(requestsBlocked
                ? R.string.friends_requests_blocked_message
                : R.string.friends_search_hint);
        searchAction.setText(label);
        searchAction.setEnabled(actionable);
        searchAction.setAlpha(actionable ? 1.0f : 0.55f);
        searchAction.setOnClickListener(actionable ? v -> addFriend(result.profile.username) : null);

        if (actionable && getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            searchAction.requestFocus();
        }
    }

    private void updateFriendRequestSettings(boolean enabled) {
        act(() -> AccountManager.setFriendRequestSettings(
                FriendsActivity.this,
                enabled,
                new AccountManager.ResultCallback<SpaceConnectApiClient.FriendSettingsResponse>() {
                    @Override
                    public void onSuccess(SpaceConnectApiClient.FriendSettingsResponse result) {
                        setBusy(false);
                        Toast.makeText(
                                FriendsActivity.this,
                                enabled
                                        ? R.string.friends_receive_requests_enabled
                                        : R.string.friends_receive_requests_disabled,
                                Toast.LENGTH_LONG
                        ).show();
                        reload();
                    }

                    @Override
                    public void onError(String message) {
                        setBusy(false);
                        Toast.makeText(FriendsActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                }));
    }

    private void clearSearchResult() {
        if (searchResultBox != null) {
            searchResultBox.setVisibility(View.GONE);
        }
    }

    private void addFriend(final String username) {
        if (TextUtils.isEmpty(username)) {
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
