package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;

/** Configures the Camera2 round-video implementation. */
public class RoundVideoSettingsActivity extends BaseFragment {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    private static final int ROW_GENERAL_HEADER = 0;
    private static final int ROW_ENABLED = 1;
    private static final int ROW_OUTPUT_RESOLUTION = 2;
    private static final int ROW_CAMERA_RESOLUTION = 3;
    private static final int ROW_FRAME_RATE = 4;
    private static final int ROW_BITRATE = 5;
    private static final int ROW_GENERAL_INFO = 6;
    private static final int ROW_CONTROLS_HEADER = 7;
    private static final int ROW_START_CAMERA = 8;
    private static final int ROW_CONTROLS = 9;
    private static final int ROW_DEFAULT_LENS = 10;
    private static final int ROW_RESET_ZOOM = 11;
    private static final int ROW_CONTROLS_INFO = 12;
    private static final int ROW_COMPOSITION_HEADER = 13;
    private static final int ROW_COMPOSITION = 14;
    private static final int ROW_COMPOSITION_INFO = 15;
    private static final int ROW_COUNT = BuildConfig.DEBUG_PRIVATE_VERSION ? 16 : 13;

    private static final int[] START_CAMERA_TITLES = {
            R.string.CameraInVideoMessagesFront,
            R.string.CameraInVideoMessagesRear,
            R.string.CameraInVideoMessagesAsk,
            R.string.CameraInVideoMessagesLast
    };

    private static final int[] BITRATES = {
            750_000,
            1_000_000,
            1_200_000,
            2_000_000,
            3_000_000,
            4_000_000,
            6_000_000
    };

    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.RoundVideoSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        return fragmentView;
    }

    private void onRowClicked(int position) {
        if (position == ROW_START_CAMERA) {
            CharSequence[] labels = new CharSequence[START_CAMERA_TITLES.length];
            for (int i = 0; i < labels.length; i++) labels[i] = LocaleController.getString(START_CAMERA_TITLES[i]);
            showChoice(
                    R.string.MoggoRoundStartCamera,
                    labels,
                    index -> xyz.nextalone.nagram.NaConfig.INSTANCE.getCameraInVideoMessages().setConfigInt(index)
            );
            return;
        }
        if (position == ROW_ENABLED) {
            SharedSettings.roundVideoCamera2Enabled.set(!SharedSettings.roundVideoCamera2Enabled.get());
            adapter.notifyDataSetChanged();
            return;
        }
        if (!SharedSettings.roundVideoCamera2Enabled.get()) {
            return;
        }
        if (position == ROW_OUTPUT_RESOLUTION) {
            showChoice(
                    R.string.RoundVideoOutputResolution,
                    new CharSequence[]{"640p", "480p", "360p"},
                    index -> SharedSettings.roundVideoOutputResolution.set(
                            RoundVideoSession.OutputResolution.values()[index]
                    )
            );
        } else if (position == ROW_CAMERA_RESOLUTION) {
            showChoice(
                    R.string.RoundVideoCameraResolution,
                    new CharSequence[]{
                            LocaleController.getString(R.string.RoundVideoCameraResolutionHigh),
                            LocaleController.getString(R.string.RoundVideoCameraResolutionMedium),
                            LocaleController.getString(R.string.RoundVideoCameraResolutionLow)
                    },
                    index -> SharedSettings.roundVideoCameraResolution.set(
                            RoundVideoSession.CameraResolution.values()[index]
                    )
            );
        } else if (position == ROW_FRAME_RATE) {
            showChoice(
                    R.string.RoundVideoFrameRate,
                    new CharSequence[]{"30 FPS", "60 FPS"},
                    index -> SharedSettings.roundVideoFrameRate.set(index == 0
                            ? RoundVideoSession.FrameRate.FPS_30
                            : RoundVideoSession.FrameRate.FPS_60)
            );
        } else if (position == ROW_BITRATE) {
            CharSequence[] labels = new CharSequence[BITRATES.length];
            for (int i = 0; i < BITRATES.length; i++) {
                labels[i] = formatBitrate(BITRATES[i]);
            }
            showChoice(
                    R.string.RoundVideoBitrate,
                    labels,
                    index -> SharedSettings.roundVideoVideoBitrate.set(BITRATES[index])
            );
        } else if (position == ROW_COMPOSITION) {
            SharedSettings.roundVideoComposition.set(!SharedSettings.roundVideoComposition.get());
            adapter.notifyItemChanged(position);
        } else if (position == ROW_CONTROLS) {
            showChoice(
                    R.string.MoggoRoundCameraControls,
                    new CharSequence[]{
                            LocaleController.getString(R.string.MoggoRoundControlsLenses),
                            LocaleController.getString(R.string.MoggoRoundControlsSlider),
                            LocaleController.getString(R.string.MoggoRoundControlsTelegram)
                    },
                    index -> SharedSettings.roundVideoCameraControls.set(
                            RoundVideoSession.CameraControls.values()[index]
                    )
            );
        } else if (position == ROW_DEFAULT_LENS) {
            if (!zoomControlsEnabled()) return;
            showChoice(
                    R.string.MoggoRoundDefaultLens,
                    new CharSequence[]{
                            LocaleController.getString(R.string.MoggoRoundLensWide),
                            LocaleController.getString(R.string.MoggoRoundLensMain),
                            LocaleController.getString(R.string.MoggoRoundLensTele)
                    },
                    index -> SharedSettings.roundVideoDefaultLens.set(
                            RoundVideoSession.DefaultLens.values()[index]
                    )
            );
        } else if (position == ROW_RESET_ZOOM) {
            if (!zoomControlsEnabled()) return;
            SharedSettings.roundVideoResetZoomAfterPinch.set(!SharedSettings.roundVideoResetZoomAfterPinch.get());
            adapter.notifyItemChanged(position);
        }
    }

    private static boolean zoomControlsEnabled() {
        return SharedSettings.roundVideoCameraControls.get() != RoundVideoSession.CameraControls.TELEGRAM;
    }

    private static String controlsLabel(RoundVideoSession.CameraControls controls) {
        if (controls == RoundVideoSession.CameraControls.LENSES) {
            return LocaleController.getString(R.string.MoggoRoundControlsLenses);
        } else if (controls == RoundVideoSession.CameraControls.ZOOM_SLIDER) {
            return LocaleController.getString(R.string.MoggoRoundControlsSlider);
        }
        return LocaleController.getString(R.string.MoggoRoundControlsTelegram);
    }

    private static String lensLabel(RoundVideoSession.DefaultLens lens) {
        if (lens == RoundVideoSession.DefaultLens.WIDE) {
            return LocaleController.getString(R.string.MoggoRoundLensWide);
        } else if (lens == RoundVideoSession.DefaultLens.TELE) {
            return LocaleController.getString(R.string.MoggoRoundLensTele);
        }
        return LocaleController.getString(R.string.MoggoRoundLensMain);
    }

    private void showChoice(int titleResId, CharSequence[] choices, ChoiceHandler handler) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(titleResId));
        builder.setItems(choices, (dialog, index) -> {
            handler.onChoice(index);
            adapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    private static String formatBitrate(int bitrate) {
        if (bitrate % 1_000_000 == 0) {
            return (bitrate / 1_000_000) + " Mbps";
        }
        if (bitrate > 1_000_000) {
            return String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f);
        }
        return (bitrate / 1_000) + " kbps";
    }

    private interface ChoiceHandler {
        void onChoice(int index);
    }

    private static String cameraResolutionLabel(RoundVideoSession.CameraResolution resolution) {
        if (resolution == RoundVideoSession.CameraResolution.HIGH) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionHigh);
        } else if (resolution == RoundVideoSession.CameraResolution.MEDIUM) {
            return LocaleController.getString(R.string.RoundVideoCameraResolutionMedium);
        }
        return LocaleController.getString(R.string.RoundVideoCameraResolutionLow);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == ROW_ENABLED || position == ROW_START_CAMERA) {
                return true;
            }
            if (!SharedSettings.roundVideoCamera2Enabled.get()) {
                return false;
            }
            if (position == ROW_DEFAULT_LENS || position == ROW_RESET_ZOOM) {
                return zoomControlsEnabled();
            }
            return position == ROW_OUTPUT_RESOLUTION
                    || position == ROW_CAMERA_RESOLUTION
                    || position == ROW_FRAME_RATE
                    || position == ROW_BITRATE
                    || position == ROW_CONTROLS
                    || position == ROW_COMPOSITION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_VALUE) {
                TextSettingsCell cell = new TextSettingsCell(context);
                cell.setCanDisable(true);
                view = cell;
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            boolean enabled = SharedSettings.roundVideoCamera2Enabled.get();
            if (holder.getItemViewType() == TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                cell.setText(position == ROW_GENERAL_HEADER
                        ? LocaleController.getString(R.string.RoundVideoGeneral)
                        : position == ROW_CONTROLS_HEADER
                        ? LocaleController.getString(R.string.MoggoRoundCameraControls)
                        : LocaleController.getString(R.string.RoundVideoComposition));
            } else if (holder.getItemViewType() == TYPE_CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setEnabled(position == ROW_ENABLED || enabled);
                if (position == ROW_ENABLED) {
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.RoundVideoUseNewRecorder),
                            enabled,
                            false
                    );
                } else if (position == ROW_RESET_ZOOM) {
                    cell.setEnabled(enabled && zoomControlsEnabled());
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.MoggoRoundResetZoom),
                            SharedSettings.roundVideoResetZoomAfterPinch.get(),
                            false
                    );
                } else {
                    cell.setTextAndCheck(
                            LocaleController.getString(R.string.RoundVideoCompositionEnabled),
                            SharedSettings.roundVideoComposition.get(),
                            false
                    );
                }
            } else if (holder.getItemViewType() == TYPE_VALUE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setEnabled(enabled);
                if (position == ROW_OUTPUT_RESOLUTION) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoOutputResolution),
                            SharedSettings.roundVideoOutputResolution.get().getSize() + "p",
                            true
                    );
                } else if (position == ROW_CAMERA_RESOLUTION) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoCameraResolution),
                            cameraResolutionLabel(SharedSettings.roundVideoCameraResolution.get()),
                            true
                    );
                } else if (position == ROW_FRAME_RATE) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoFrameRate),
                            SharedSettings.roundVideoFrameRate.get().getValue() + " FPS",
                            true
                    );
                } else if (position == ROW_START_CAMERA) {
                    cell.setEnabled(true);
                    int mode = xyz.nextalone.nagram.NaConfig.INSTANCE.getCameraInVideoMessages().Int();
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.MoggoRoundStartCamera),
                            LocaleController.getString(START_CAMERA_TITLES[Math.max(0, Math.min(START_CAMERA_TITLES.length - 1, mode))]),
                            true
                    );
                } else if (position == ROW_CONTROLS) {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.MoggoRoundCameraControls),
                            controlsLabel(SharedSettings.roundVideoCameraControls.get()),
                            true
                    );
                } else if (position == ROW_DEFAULT_LENS) {
                    cell.setEnabled(enabled && zoomControlsEnabled());
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.MoggoRoundDefaultLens),
                            lensLabel(SharedSettings.roundVideoDefaultLens.get()),
                            true
                    );
                } else {
                    cell.setTextAndValue(
                            LocaleController.getString(R.string.RoundVideoBitrate),
                            formatBitrate(SharedSettings.roundVideoVideoBitrate.get()),
                            false
                    );
                }
            } else {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(position == ROW_GENERAL_INFO
                        ? LocaleController.getString(R.string.RoundVideoGeneralInfo)
                        : position == ROW_CONTROLS_INFO
                        ? LocaleController.getString(R.string.MoggoRoundControlsInfo)
                        : LocaleController.getString(R.string.RoundVideoCompositionInfo));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_GENERAL_HEADER || position == ROW_CONTROLS_HEADER
                    || position == ROW_COMPOSITION_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_ENABLED
                    || position == ROW_RESET_ZOOM
                    || position == ROW_COMPOSITION) {
                return TYPE_CHECK;
            }
            if (position == ROW_GENERAL_INFO || position == ROW_CONTROLS_INFO
                    || position == ROW_COMPOSITION_INFO) {
                return TYPE_INFO;
            }
            return TYPE_VALUE;
        }
    }
}
