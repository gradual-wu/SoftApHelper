package com.xhy.xp.softaphelper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** 主界面：每个共享方式一个按钮，点进去配置该方式的网段。 */
public class MainActivity extends Activity {

    private LinearLayout buttonContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        buttonContainer = findViewById(R.id.config_buttons);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从配置界面返回时刷新按钮上的当前值
        rebuildButtons();
    }

    private void rebuildButtons() {
        buttonContainer.removeAllViews();
        for (final int type : AppSettings.types()) {
            buttonContainer.addView(createButton(type), buttonLayoutParams());
        }
    }

    private Button createButton(final int type) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(buildButtonText(type));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, ConfigActivity.class);
                intent.putExtra(ConfigActivity.EXTRA_TETHERING_TYPE, type);
                startActivity(intent);
            }
        });
        return button;
    }

    /** 按钮上是两行：第一行是名字，第二行小一号、灰色，显示当前网段。 */
    private SpannableString buildButtonText(int type) {
        String title = getString(AppSettings.titleRes(type));
        String address = AppSettings.getAddress(this, type);
        String text = title + "\n" + address;
        SpannableString spannable = new SpannableString(text);
        int start = title.length() + 1;
        spannable.setSpan(new RelativeSizeSpan(0.8f), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(Color.GRAY), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    private LinearLayout.LayoutParams buttonLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int margin = (int) (getResources().getDisplayMetrics().density * 6);
        params.setMargins(0, margin, 0, margin);
        return params;
    }
}
