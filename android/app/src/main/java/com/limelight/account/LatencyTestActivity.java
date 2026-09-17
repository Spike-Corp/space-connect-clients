package com.limelight.account;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.R;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Teste de latência/rede estilo GeForce Now: mede o tempo de resposta (request +
 * primeira resposta) contra endpoints de borda (Cloudflare + Google) e classifica a
 * qualidade da conexão pra cloud gaming. Não mede a VM em si (essa só existe com
 * sessão ativa) — mede a qualidade do link do usuário até a internet.
 */
public class LatencyTestActivity extends Activity {

    // Endpoints de borda com resposta mínima (generate_204/204 = sem corpo, rede pura).
    private static final String[] TARGETS = {
            "https://www.gstatic.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://cloudflare.com/cdn-cgi/trace",
            "https://www.google.com.br/generate_204",
    };
    private static final int PINGS_PER_TARGET = 4;

    private TextView titleText, resultText, detailText, verdictText;
    private ProgressBar progress;
    private Button startButton;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_latency_test);

        titleText = findViewById(R.id.latencyTitle);
        resultText = findViewById(R.id.latencyResult);
        detailText = findViewById(R.id.latencyDetail);
        verdictText = findViewById(R.id.latencyVerdict);
        progress = findViewById(R.id.latencyProgress);
        startButton = findViewById(R.id.latencyStart);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startTest(); }
        });
    }

    private void startTest() {
        if (running) return;
        running = true;
        startButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        resultText.setText("");
        verdictText.setText("");
        detailText.setText(getString(R.string.latency_measuring));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Long> all = new ArrayList<>();
                final StringBuilder perTarget = new StringBuilder();
                for (String t : TARGETS) {
                    Long best = ping(t);
                    if (best != null) {
                        all.add(best);
                        String host = t.replace("https://", "").split("/")[0];
                        perTarget.append(host).append(": ").append(best).append(" ms\n");
                        publishProgress(host, best);
                    }
                }
                finishTest(all, perTarget.toString());
            }
        }).start();
    }

    private void publishProgress(final String host, final long ms) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                detailText.setText(host + "  →  " + ms + " ms");
            }
        });
    }

    /** Melhor de N pings (HEAD/GET 204). Retorna null se tudo falhar. */
    private Long ping(String urlStr) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < PINGS_PER_TARGET; i++) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(urlStr + (urlStr.contains("?") ? "&" : "?") + "_t=" + System.nanoTime());
                c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                long t0 = System.nanoTime();
                c.connect();
                c.getResponseCode(); // força ler a resposta
                c.getInputStream().close();
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms < best) best = ms;
            } catch (Exception e) {
                // ignora — próxima tentativa
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return best == Long.MAX_VALUE ? null : best;
    }

    private void finishTest(final List<Long> samples, final String perTarget) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                running = false;
                startButton.setEnabled(true);
                progress.setVisibility(View.GONE);

                if (samples.isEmpty()) {
                    resultText.setText("—");
                    verdictText.setText(getString(R.string.latency_no_net));
                    verdictText.setTextColor(Color.parseColor("#F87171"));
                    detailText.setText(perTarget);
                    return;
                }

                long median = median(samples);
                long min = Collections.min(samples);
                long max = Collections.max(samples);
                long jitter = max - min;

                resultText.setText(median + " ms");
                detailText.setText(String.format(Locale.US, "min %d ms · max %d ms · jitter %d ms", min, max, jitter));

                // Classificação orientada a cloud gaming (latência + jitter).
                String verdict;
                int color;
                if (median <= 30 && jitter <= 15) {
                    verdict = getString(R.string.latency_excellent);
                    color = Color.parseColor("#4ADE80");
                } else if (median <= 60 && jitter <= 30) {
                    verdict = getString(R.string.latency_good);
                    color = Color.parseColor("#A3E635");
                } else if (median <= 100 && jitter <= 50) {
                    verdict = getString(R.string.latency_ok);
                    color = Color.parseColor("#FB923C");
                } else {
                    verdict = getString(R.string.latency_bad);
                    color = Color.parseColor("#F87171");
                }
                verdictText.setText(verdict);
                verdictText.setTextColor(color);
            }
        });
    }

    private static long median(List<Long> v) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
