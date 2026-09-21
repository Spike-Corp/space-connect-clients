package com.limelight.account;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.utils.UiHelper;

/**
 * Relato de bug dentro do app — vai pra página "Bugs app" do painel admin.
 * Abre tanto logado (launcher) quanto deslogado (tela de login, onde o próprio
 * login pode ser o bug). Versão do app, dispositivo e SO são anexados
 * automaticamente pra equipe conseguir reproduzir.
 */
public class BugReportActivity extends Activity {

    private EditText emailField;
    private EditText descriptionField;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiHelper.applyPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bug_report);

        emailField = findViewById(R.id.bugReportEmail);
        descriptionField = findViewById(R.id.bugReportDescription);
        sendButton = findViewById(R.id.bugReportSend);

        TextView metaText = findViewById(R.id.bugReportMeta);
        metaText.setText(getString(R.string.bug_report_meta, BuildConfig.VERSION_NAME));

        String loggedEmail = AccountManager.getLoggedInEmail(this);
        if (!TextUtils.isEmpty(loggedEmail)) {
            // Logado: o relato já vai amarrado à conta — esconde o campo de e-mail.
            emailField.setVisibility(View.GONE);
        }

        sendButton.setOnClickListener(v -> send());
        descriptionField.setTypeface(UiHelper.getBodyTypeface(this));
    }

    private void send() {
        final String description = descriptionField.getText().toString().trim();
        final String email = emailField.getText().toString().trim();

        if (description.length() < 10) {
            Toast.makeText(this, R.string.bug_report_too_short, Toast.LENGTH_LONG).show();
            return;
        }
        if (emailField.getVisibility() == View.VISIBLE
                && (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches())) {
            Toast.makeText(this, R.string.bug_report_invalid_email, Toast.LENGTH_LONG).show();
            return;
        }

        sendButton.setEnabled(false);
        AccountManager.reportBug(this, description, email,
                new AccountManager.ResultCallback<SpaceConnectApiClient.SimpleResponse>() {
            @Override
            public void onSuccess(SpaceConnectApiClient.SimpleResponse result) {
                Toast.makeText(BugReportActivity.this, R.string.bug_report_sent, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onError(String message) {
                sendButton.setEnabled(true);
                Toast.makeText(BugReportActivity.this,
                        message != null && !message.isEmpty()
                                ? message : getString(R.string.bug_report_failed),
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
