package com.limelight.account;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class LoginActivity extends Activity {

    private static final String ACCOUNT_WEB_URL = "https://spacecloud.gg/panel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);

        // If we already have a saved session on this device, skip straight to the PC list.
        if (AccountManager.isLoggedIn(this)) {
            goToLauncher();
            return;
        }

        setContentView(R.layout.activity_login);

        Typeface displayFont = UiHelper.getDisplayTypeface(this);
        Typeface bodyFont = UiHelper.getBodyTypeface(this);

        final EditText emailField = findViewById(R.id.emailField);
        final EditText passwordField = findViewById(R.id.passwordField);
        Button loginButton = findViewById(R.id.loginButton);
        TextView createAccountLink = findViewById(R.id.createAccountLink);
        TextView forgotPasswordLink = findViewById(R.id.forgotPasswordLink);

        ((TextView) findViewById(R.id.loginWordmark)).setTypeface(displayFont);
        ((TextView) findViewById(R.id.loginSubtitle)).setTypeface(bodyFont);
        emailField.setTypeface(bodyFont);
        passwordField.setTypeface(bodyFont);
        loginButton.setTypeface(bodyFont, Typeface.BOLD);
        createAccountLink.setTypeface(bodyFont, Typeface.BOLD);
        forgotPasswordLink.setTypeface(bodyFont);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String email = emailField.getText().toString().trim();
                String password = passwordField.getText().toString();

                if (!AccountManager.isSupported()) {
                    Toast.makeText(
                            LoginActivity.this,
                            R.string.launcher_android_version_required,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                loginButton.setEnabled(false);
                AccountManager.login(
                        LoginActivity.this,
                        email,
                        password,
                        loginCallback(loginButton));
            }
        });

        createAccountLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccountWebPage("/register");
            }
        });

        forgotPasswordLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccountWebPage("/forgot-password");
            }
        });
    }

    private void openAccountWebPage(String path) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_WEB_URL + path)));
        } catch (Exception e) {
            Toast.makeText(this, ACCOUNT_WEB_URL, Toast.LENGTH_LONG).show();
        }
    }

    private AccountManager.LoginCallback loginCallback(Button loginButton) {
        return new AccountManager.LoginCallback() {
            @Override
            public void onSuccess() {
                loginButton.setEnabled(true);
                goToLauncher();
            }

            @Override
            public void onTwoFactorRequired(String tempToken) {
                loginButton.setEnabled(true);
                showTwoFactorDialog(tempToken, loginButton);
            }

            @Override
            public void onError(String message) {
                loginButton.setEnabled(true);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
    }

    private void showTwoFactorDialog(String tempToken, Button loginButton) {
        final EditText codeField = new EditText(this);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setHint(R.string.launcher_two_factor_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_two_factor_title)
                .setView(codeField)
                .setNegativeButton(R.string.game_menu_cancel, null)
                .setPositiveButton(R.string.launcher_confirm, (dialog, which) -> {
                    loginButton.setEnabled(false);
                    AccountManager.verifyTwoFactor(
                            LoginActivity.this,
                            tempToken,
                            codeField.getText().toString().trim(),
                            loginCallback(loginButton));
                })
                .show();
    }

    private void goToLauncher() {
        startActivity(new Intent(this, LauncherActivity.class));
        finish();
    }
}
