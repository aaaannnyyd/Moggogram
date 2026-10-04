package org.telegram.utils.settings;

import org.telegram.messenger.BuildConfig;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;

public final class SharedSettings {

    public static final BooleanSetting experimentalSettingsAllowed =
        BooleanSetting.of("experimental_settings_allowed", BuildConfig.DEBUG_VERSION);

    public static final BooleanSetting roundVideoCamera2Enabled =
        BooleanSetting.of("round_video_camera2_enabled", BuildConfig.DEBUG_VERSION);

    public static final EnumSetting<RoundVideoSession.OutputResolution> roundVideoOutputResolution =
        EnumSetting.of("round_video_output_resolution", RoundVideoSession.OutputResolution.P480);

    public static final EnumSetting<RoundVideoSession.CameraResolution> roundVideoCameraResolution =
        EnumSetting.of("round_video_camera_resolution", RoundVideoSession.CameraResolution.HIGH);

    public static final EnumSetting<RoundVideoSession.FrameRate> roundVideoFrameRate =
        EnumSetting.of("round_video_frame_rate", RoundVideoSession.FrameRate.FPS_30);

    public static final IntSetting roundVideoVideoBitrate =
        IntSetting.of("round_video_video_bitrate", 1_000_000);

    public static final BooleanSetting roundVideoComposition =
        BooleanSetting.of("round_video_composition", true);

    public static final EnumSetting<RoundVideoSession.CameraFacing> roundVideoLastCamera =
        EnumSetting.of("round_video_last_camera", RoundVideoSession.CameraFacing.FRONT);

    // Moggogram: camera controls on the round video screen
    public static final EnumSetting<RoundVideoSession.CameraControls> roundVideoCameraControls =
        EnumSetting.of("moggo_round_video_camera_controls", RoundVideoSession.CameraControls.LENSES);

    public static final EnumSetting<RoundVideoSession.DefaultLens> roundVideoDefaultLens =
        EnumSetting.of("moggo_round_video_default_lens", RoundVideoSession.DefaultLens.MAIN);

    public static final BooleanSetting roundVideoResetZoomAfterPinch =
        BooleanSetting.of("moggo_round_video_reset_zoom", false);

    private SharedSettings() {
    }
}
