package org.easydarwin.easyrtsplive.activity;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.easydarwin.easyrtsplive.BuildConfig;
import org.easydarwin.easyrtsplive.R;
import org.easydarwin.easyrtsplive.TheApp;
import org.easydarwin.easyrtsplive.data.VideoSource;
import org.easydarwin.easyrtsplive.databinding.ActivityPlayListBinding;
import org.easydarwin.easyrtsplive.databinding.VideoSourceItemBinding;

/**
 * 视频广场
 * */
public class PlayListActivity extends AppCompatActivity implements View.OnClickListener, View.OnLongClickListener {

    public static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_RTSP_SCAN_TEXT_URL = 1002;
    private static final int REQUEST_RTMP_SCAN_TEXT_URL = 1003;
    private int requestScanTextTag;

    public static final String EXTRA_BOOLEAN_SELECT_ITEM_TO_PLAY = "extra-boolean-select-item-to-play";

    private int mPos;
    private ActivityPlayListBinding mBinding;
    private RecyclerView mRecyclerView;
    private EditText rtspEdit;
    private EditText rtmpEdit;

    private Cursor mCursor;

    private long mExitTime;//声明一个long类型变量：用于存放上一点击“返回键”的时刻

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }

        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_play_list);

        setSupportActionBar(mBinding.toolbar);
        notifyAboutColorChange();

        // 添加默认地址
        mCursor = TheApp.sDB.query(VideoSource.TABLE_NAME, null, null, null, null, null, null);
        if (!mCursor.moveToFirst()) {
            ContentValues cv = new ContentValues();
            cv.put(VideoSource.RTSP_URL, VideoSource.RTSP);
            cv.put(VideoSource.RTMP_URL, VideoSource.RTMP);
            cv.put(VideoSource.TRANSPORT_MODE, VideoSource.TRANSPORT_MODE_TCP);
            cv.put(VideoSource.SEND_OPTION, VideoSource.SEND_OPTION_TRUE);

            TheApp.sDB.insert(VideoSource.TABLE_NAME, null, cv);

            mCursor.close();
            mCursor = TheApp.sDB.query(VideoSource.TABLE_NAME, null, null, null, null, null, null);
        }

        mRecyclerView = mBinding.recycler;
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(new RecyclerView.Adapter() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new PlayListViewHolder((VideoSourceItemBinding) DataBindingUtil.inflate(getLayoutInflater(), R.layout.video_source_item, parent, false));
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                PlayListViewHolder plvh = (PlayListViewHolder) holder;
                mCursor.moveToPosition(position);
                String rtsp_url = mCursor.getString(mCursor.getColumnIndex(VideoSource.RTSP_URL));
                String rtmp_url = mCursor.getString(mCursor.getColumnIndex(VideoSource.RTMP_URL));

                plvh.tv_rtsp.setText(rtsp_url);
                plvh.tv_rtmp.setText(rtmp_url);
            }

            @Override
            public int getItemCount() {
                return mCursor.getCount();
            }
        });

        // 如果当前进程挂起，则进入启动页
        if (savedInstanceState == null) {
            if (!getIntent().getBooleanExtra(EXTRA_BOOLEAN_SELECT_ITEM_TO_PLAY, false)) {
                startActivity(new Intent(this, SplashActivity.class));
            }
        }

        if (!isPro()) {
            mBinding.pullToRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    mBinding.pullToRefresh.setRefreshing(false);
                }
            });
        } else {
            mBinding.pullToRefresh.setEnabled(false);
        }

        mBinding.toolbarAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                displayDialog(-1);
            }
        });

        mBinding.toolbarAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PlayListActivity.this, AboutActivity.class));
            }
        });
    }

    @Override
    protected void onDestroy() {
        mCursor.close();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    toScanQRActivity();
                }

                break;
            }
        }
    }

    @Override
    public void onClick(View view) {
        PlayListViewHolder holder = (PlayListViewHolder) view.getTag();
        int pos = holder.getAdapterPosition();

        if (pos != -1) {
            mCursor.moveToPosition(pos);
            String rtsp = mCursor.getString(mCursor.getColumnIndex(VideoSource.RTSP_URL));
            String rtmp = mCursor.getString(mCursor.getColumnIndex(VideoSource.RTMP_URL));
            int sendOption = mCursor.getInt(mCursor.getColumnIndex(VideoSource.SEND_OPTION));
            int transportMode = mCursor.getInt(mCursor.getColumnIndex(VideoSource.TRANSPORT_MODE));

            if (!TextUtils.isEmpty(rtsp)) {
                Intent i = new Intent(PlayListActivity.this, PushActivity.class);
                i.putExtra("rtsp_url", rtsp);
                i.putExtra("rtmp_url", rtmp);
                i.putExtra(VideoSource.SEND_OPTION, sendOption);
                i.putExtra(VideoSource.TRANSPORT_MODE, transportMode);
                mPos = pos;
                startActivity(i);
            }
        }
    }

    @Override
    public boolean onLongClick(View view) {
        PlayListViewHolder holder = (PlayListViewHolder) view.getTag();
        final int pos = holder.getAdapterPosition();

        if (pos != -1) {
            new AlertDialog.Builder(this).setItems(new CharSequence[]{"修改", "删除"}, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    if (i == 0) {
                        displayDialog(pos);
                    } else {
                        new AlertDialog
                                .Builder(PlayListActivity.this)
                                .setMessage("确定要删除该地址吗？")
                                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        mCursor.moveToPosition(pos);
                                        TheApp.sDB.delete(VideoSource.TABLE_NAME, VideoSource._ID + "=?", new String[]{String.valueOf(mCursor.getInt(mCursor.getColumnIndex(VideoSource._ID)))});
                                        mCursor.close();
                                        mCursor = TheApp.sDB.query(VideoSource.TABLE_NAME, null, null, null, null, null, null);
                                        mRecyclerView.getAdapter().notifyItemRemoved(pos);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }
            }).show();
        }

        return true;
    }

    @Override
    public void onBackPressed() {
        //与上次点击返回键时刻作差
        if ((System.currentTimeMillis() - mExitTime) > 2000) {
            //大于2000ms则认为是误操作，使用Toast进行提示
            Toast.makeText(this, "再按一次退出程序", Toast.LENGTH_SHORT).show();
            //并记录下本次点击“返回键”的时刻，以便下次进行判断
            mExitTime = System.currentTimeMillis();
        } else {
            super.onBackPressed();
        }
    }

    private void displayDialog(final int pos) {
        String rtsp_url = "rtsp://";
        String rtmp_url = "rtmp://";

        View view = getLayoutInflater().inflate(R.layout.new_media_source_dialog, null);
        rtspEdit = view.findViewById(R.id.rtsp_et);
        rtmpEdit = view.findViewById(R.id.rtmp_et);

        if (pos > -1) {
            mCursor.moveToPosition(pos);
            rtsp_url = mCursor.getString(mCursor.getColumnIndex(VideoSource.RTSP_URL));
            rtmp_url = mCursor.getString(mCursor.getColumnIndex(VideoSource.RTMP_URL));
        }

        rtspEdit.setText(rtsp_url);
        rtspEdit.setSelection(rtsp_url.length());
        rtmpEdit.setText(rtmp_url);
        rtmpEdit.setSelection(rtmp_url.length());

        // 去扫描二维码
        final ImageButton rtsp_scan_ib = view.findViewById(R.id.rtsp_scan_ib);
        final ImageButton rtmp_scan_ib = view.findViewById(R.id.rtmp_scan_ib);

        rtsp_scan_ib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestScanTextTag = REQUEST_RTSP_SCAN_TEXT_URL;
                // 动态获取camera和audio权限
                if (ActivityCompat.checkSelfPermission(PlayListActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        || ActivityCompat.checkSelfPermission(PlayListActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(PlayListActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
                } else {
                    toScanQRActivity();
                }
            }
        });

        rtmp_scan_ib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestScanTextTag = REQUEST_RTMP_SCAN_TEXT_URL;
                // 动态获取camera和audio权限
                if (ActivityCompat.checkSelfPermission(PlayListActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        || ActivityCompat.checkSelfPermission(PlayListActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(PlayListActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
                } else {
                    toScanQRActivity();
                }
            }
        });

        final AlertDialog dlg = new AlertDialog.Builder(PlayListActivity.this)
                .setView(view)
                .setTitle("请输入流地址")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String rtsp_url = String.valueOf(rtspEdit.getText());
                        String rtmp_url = String.valueOf(rtmpEdit.getText());

                        if (TextUtils.isEmpty(rtsp_url) || TextUtils.isEmpty(rtmp_url)) {
                            return;
                        }

                        if (rtsp_url.toLowerCase().indexOf("rtsp://") != 0) {
                            Toast.makeText(PlayListActivity.this,"不是合法的RTSP地址，请重新添加.",Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (rtmp_url.toLowerCase().indexOf("rtmp://") != 0) {
                            Toast.makeText(PlayListActivity.this,"不是合法的RTMP地址，请重新添加.",Toast.LENGTH_SHORT).show();
                            return;
                        }

                        ContentValues cv = new ContentValues();
                        cv.put(VideoSource.RTSP_URL, rtsp_url);
                        cv.put(VideoSource.RTMP_URL, rtmp_url);
                        cv.put(VideoSource.TRANSPORT_MODE, VideoSource.TRANSPORT_MODE_TCP);
                        cv.put(VideoSource.SEND_OPTION, VideoSource.SEND_OPTION_TRUE);

                        if (pos > -1) {
                            final int _id = mCursor.getInt(mCursor.getColumnIndex(VideoSource._ID));

                            TheApp.sDB.update(VideoSource.TABLE_NAME, cv, VideoSource._ID + "=?", new String[]{String.valueOf(_id)});

                            mCursor.close();
                            mCursor = TheApp.sDB.query(VideoSource.TABLE_NAME, null, null, null, null, null, null);
                            mRecyclerView.getAdapter().notifyItemChanged(pos);
                        } else {
                            TheApp.sDB.insert(VideoSource.TABLE_NAME, null, cv);

                            mCursor.close();
                            mCursor = TheApp.sDB.query(VideoSource.TABLE_NAME, null, null, null, null, null, null);
                            mRecyclerView.getAdapter().notifyItemInserted(mCursor.getCount() - 1);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create();

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(rtspEdit, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        dlg.show();
    }

    private void toScanQRActivity() {
        Intent intent = new Intent(PlayListActivity.this, ScanQRActivity.class);
        startActivityForResult(intent, requestScanTextTag);
        overridePendingTransition(R.anim.slide_bottom_in, R.anim.slide_top_out);
    }

    /*
     * 显示key有限期
     * */
    private void notifyAboutColorChange() {
        ImageView iv = findViewById(R.id.toolbar_about);

        if (TheApp.activeDays >= 9999) {
            iv.setImageResource(R.drawable.new_version1);
        } else if (TheApp.activeDays > 0) {
            iv.setImageResource(R.drawable.new_version2);
        } else {
            iv.setImageResource(R.drawable.new_version3);
        }
    }

    public static boolean isPro() {
        return BuildConfig.FLAVOR.equals("pro");
    }

    /**
     * 视频源的item
     * */
    class PlayListViewHolder extends RecyclerView.ViewHolder {
        private final TextView tv_rtsp;
        private final TextView tv_rtmp;

        public PlayListViewHolder(VideoSourceItemBinding binding) {
            super(binding.getRoot());

            tv_rtsp = binding.rtspTv;
            tv_rtmp = binding.rtmpTv;

            itemView.setOnClickListener(PlayListActivity.this);
            itemView.setOnLongClickListener(PlayListActivity.this);
            itemView.setTag(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_RTSP_SCAN_TEXT_URL) {
            if (resultCode == RESULT_OK) {
                String url = data.getStringExtra("text");
                rtspEdit.setText(url);
            }
        } else if (requestCode == REQUEST_RTMP_SCAN_TEXT_URL) {
            if (resultCode == RESULT_OK) {
                String url = data.getStringExtra("text");
                rtmpEdit.setText(url);
            }
        } else {
            mRecyclerView.getAdapter().notifyItemChanged(mPos);
        }
    }
}