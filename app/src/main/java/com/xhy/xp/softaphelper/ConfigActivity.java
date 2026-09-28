package com.xhy.xp.softaphelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 单个共享方式的网段配置界面；WiFi 热点额外可以配频段和信道。 */
public class ConfigActivity extends Activity {

    public static final String EXTRA_TETHERING_TYPE = "tethering_type";

    private static final int[] BAND_VALUES = {
            AppSettings.BAND_FOLLOW_SYSTEM,
            AppSettings.BAND_2GHZ,
            AppSettings.BAND_5GHZ,
            AppSettings.BAND_2GHZ_5GHZ,
    };
    private static final int[] BAND_LABELS = {
            R.string.band_follow_system,
            R.string.band_2ghz,
            R.string.band_5ghz,
            R.string.band_2ghz_5ghz,
    };

    private int tetheringType;
    private EditText addressInput;
    private TextView statusText;

    private LinearLayout bandGroup;
    private Spinner bandSpinner;
    private Spinner channelSpinner;
    private TextView bandHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        tetheringType = getIntent().getIntExtra(EXTRA_TETHERING_TYPE, AppSettings.TYPE_WIFI);
        if (!AppSettings.isSupported(tetheringType)) {
            finish();
            return;
        }

        addressInput = findViewById(R.id.address_input);
        statusText = findViewById(R.id.status_text);

        setTitle(getString(AppSettings.titleRes(tetheringType)));
        ((TextView) findViewById(R.id.config_hint))
                .setText(getString(R.string.config_hint, AppSettings.defaultAddress(tetheringType)));

        addressInput.setText(AppSettings.getAddress(this, tetheringType));
        addressInput.setSelection(addressInput.getText().length());

        ((Button) findViewById(R.id.save_button)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                save();
            }
        });

        setupBandControls();
    }

    // ---------------- 频段 / 信道（只有 WiFi 热点有） ----------------

    private void setupBandControls() {
        bandGroup = findViewById(R.id.wifi_band_group);
        if (tetheringType != AppSettings.TYPE_WIFI) {
            bandGroup.setVisibility(View.GONE);
            return;
        }

        bandSpinner = findViewById(R.id.band_spinner);
        channelSpinner = findViewById(R.id.channel_spinner);
        bandHint = findViewById(R.id.band_hint);

        List<String> bandLabels = new ArrayList<>();
        for (int res : BAND_LABELS) bandLabels.add(getString(res));
        bandSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, bandLabels));
        bandSpinner.setSelection(indexOfBand(AppSettings.getWifiBand(this)));
        bandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateChannelSpinner(BAND_VALUES[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        updateChannelSpinner(selectedBand());
    }

    private int selectedBand() {
        return BAND_VALUES[bandSpinner.getSelectedItemPosition()];
    }

    private static int indexOfBand(int band) {
        for (int i = 0; i < BAND_VALUES.length; i++) {
            if (BAND_VALUES[i] == band) return i;
        }
        return 0;
    }

    private void updateChannelSpinner(int band) {
        int[] channels = AppSettings.channelsFor(band);
        boolean singleBand = channels.length > 0;

        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.channel_auto));
        for (int channel : channels) labels.add(String.valueOf(channel));
        channelSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        channelSpinner.setEnabled(singleBand);

        // 保持已经选中的信道
        int selected = AppSettings.getWifiChannel(this);
        int position = 0;
        for (int i = 0; i < channels.length; i++) {
            if (channels[i] == selected) position = i + 1;
        }
        channelSpinner.setSelection(singleBand ? position : 0);
        channelSpinner.setAlpha(singleBand ? 1f : 0.5f);

        String bandName = getString(BAND_LABELS[indexOfBand(band)]);
        bandHint.setText(singleBand
                ? getString(R.string.band_hint_single, bandName)
                : getString(R.string.band_hint_multi, bandName));
    }

    /** 保存频段/信道，返回给人看的摘要（非 WiFi 返回 null）。 */
    private String saveBandSettings() {
        if (tetheringType != AppSettings.TYPE_WIFI) return null;

        int band = selectedBand();
        AppSettings.setWifiBand(this, band);
        if (band == AppSettings.BAND_FOLLOW_SYSTEM) return null;

        if (band == AppSettings.BAND_2GHZ_5GHZ) {
            AppSettings.setWifiChannel(this, AppSettings.CHANNEL_AUTO);
            return getString(R.string.band_summary_dual);
        }

        int position = channelSpinner.getSelectedItemPosition();
        int channel = position <= 0 ? AppSettings.CHANNEL_AUTO
                : AppSettings.channelsFor(band)[position - 1];
        AppSettings.setWifiChannel(this, channel);

        String bandName = getString(BAND_LABELS[indexOfBand(band)]);
        return channel == AppSettings.CHANNEL_AUTO
                ? getString(R.string.band_summary_auto, bandName)
                : getString(R.string.band_summary_single, bandName, String.valueOf(channel));
    }

    // ---------------- 保存 ----------------

    private void save() {
        String input = addressInput.getText().toString();

        String error = CidrUtils.validate(input);
        if (error != null) {
            showStatus(error, true);
            return;
        }

        String normalized = CidrUtils.normalize(input);
        String bandSummary;
        try {
            AppSettings.setAddress(this, tetheringType, normalized);
            bandSummary = saveBandSettings();
        } catch (Throwable throwable) {
            showStatus("保存失败：" + throwable, true);
            return;
        }

        addressInput.setText(normalized);
        hideKeyboard();

        String summary = bandSummary == null ? normalized : normalized + "，" + bandSummary;

        // 请 system_server 里的模块代码把对应的共享关掉再打开一次，新配置立刻生效
        if (requestRestart()) {
            showStatus(getString(R.string.saved_and_restarting, summary), false);
        } else {
            showStatus(getString(R.string.saved_hint, summary), false);
        }
    }

    private boolean requestRestart() {
        try {
            Intent intent = new Intent(AppSettings.ACTION_RESTART_TETHERING);
            intent.putExtra(AppSettings.EXTRA_TETHERING_TYPE, tetheringType);
            sendBroadcast(intent);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void showStatus(String message, boolean isError) {
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.error : R.color.success));
    }

    private void hideKeyboard() {
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && addressInput != null) {
            manager.hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
        }
    }
}
