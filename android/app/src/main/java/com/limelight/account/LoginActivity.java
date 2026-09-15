package com.limelight.account;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
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
        setupPasswordVisibilityToggle(passwordField, bodyFont);
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

    // Eye toggle at the end of the password field: switches between masked and
    // visible text, keeping the brand typeface and cursor position afterwards.
    private void setupPasswordVisibilityToggle(final EditText passwordField, final Typeface bodyFont) {
        passwordField.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_UP) {
                    return false;
                }
                Drawable endDrawable = passwordField.getCompoundDrawablesRelative()[2];
                if (endDrawable == null) {
                    return false;
                }
                int toggleStart = passwordField.getWidth() - passwordField.getTotalPaddingEnd();
                if (event.getX() < toggleStart) {
                    return false;
                }
                v.performClick();

                boolean visible = (passwordField.getInputType()
                        & InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                        == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
                if (visible) {
                    passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    passwordField.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_lock, 0, R.drawable.ic_eye, 0);
                } else {
                    passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    passwordField.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_lock, 0, R.drawable.ic_eye_off, 0);
                }
                // Changing inputType resets the font to the default one.
                passwordField.setTypeface(bodyFont);
                passwordField.setSelection(passwordField.getText().length());
                return true;
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
