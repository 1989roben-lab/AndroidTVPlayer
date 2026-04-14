package com.openclaw.tv.receiver.dlna;

import android.util.Log;
import com.openclaw.tv.receiver.PlaybackManager;
import com.openclaw.tv.receiver.ReceiverMediaKind;
import com.openclaw.tv.receiver.ReceiverState;
import org.fourthline.cling.binding.annotations.UpnpAction;
import org.fourthline.cling.binding.annotations.UpnpInputArgument;
import org.fourthline.cling.binding.annotations.UpnpOutputArgument;
import org.fourthline.cling.binding.annotations.UpnpService;
import org.fourthline.cling.binding.annotations.UpnpServiceId;
import org.fourthline.cling.binding.annotations.UpnpServiceType;
import org.fourthline.cling.binding.annotations.UpnpStateVariable;
import org.fourthline.cling.binding.annotations.UpnpStateVariables;
import org.fourthline.cling.model.ModelUtil;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.AVTransportException;
import org.fourthline.cling.support.model.DeviceCapabilities;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PlayMode;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.RecordQualityMode;
import org.fourthline.cling.support.model.StorageMedium;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportSettings;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.model.TransportStatus;

@UpnpService(
        serviceId = @UpnpServiceId("AVTransport"),
        serviceType = @UpnpServiceType(value = "AVTransport", version = 1)
)
@UpnpStateVariables({
        @UpnpStateVariable(name = "TransportState", sendEvents = false, allowedValuesEnum = TransportState.class),
        @UpnpStateVariable(name = "TransportStatus", sendEvents = false, allowedValuesEnum = TransportStatus.class),
        @UpnpStateVariable(name = "PlaybackStorageMedium", sendEvents = false, defaultValue = "NONE", allowedValuesEnum = StorageMedium.class),
        @UpnpStateVariable(name = "RecordStorageMedium", sendEvents = false, defaultValue = "NOT_IMPLEMENTED", allowedValuesEnum = StorageMedium.class),
        @UpnpStateVariable(name = "PossiblePlaybackStorageMedia", sendEvents = false, datatype = "string", defaultValue = "NETWORK"),
        @UpnpStateVariable(name = "PossibleRecordStorageMedia", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "CurrentPlayMode", sendEvents = false, defaultValue = "NORMAL", allowedValuesEnum = PlayMode.class),
        @UpnpStateVariable(name = "TransportPlaySpeed", sendEvents = false, datatype = "string", defaultValue = "1"),
        @UpnpStateVariable(name = "RecordMediumWriteStatus", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "CurrentRecordQualityMode", sendEvents = false, defaultValue = "NOT_IMPLEMENTED", allowedValuesEnum = RecordQualityMode.class),
        @UpnpStateVariable(name = "PossibleRecordQualityModes", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "NumberOfTracks", sendEvents = false, datatype = "ui4", defaultValue = "0"),
        @UpnpStateVariable(name = "CurrentTrack", sendEvents = false, datatype = "ui4", defaultValue = "0"),
        @UpnpStateVariable(name = "CurrentTrackDuration", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "CurrentMediaDuration", sendEvents = false, datatype = "string", defaultValue = "00:00:00"),
        @UpnpStateVariable(name = "CurrentTrackMetaData", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "CurrentTrackURI", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "AVTransportURI", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "AVTransportURIMetaData", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "NextAVTransportURI", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "NextAVTransportURIMetaData", sendEvents = false, datatype = "string", defaultValue = "NOT_IMPLEMENTED"),
        @UpnpStateVariable(name = "RelativeTimePosition", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "AbsoluteTimePosition", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "RelativeCounterPosition", sendEvents = false, datatype = "i4", defaultValue = "2147483647"),
        @UpnpStateVariable(name = "AbsoluteCounterPosition", sendEvents = false, datatype = "i4", defaultValue = "2147483647"),
        @UpnpStateVariable(name = "CurrentTransportActions", sendEvents = false, datatype = "string", defaultValue = "Play,Stop,Pause,Seek,Next"),
        @UpnpStateVariable(name = "A_ARG_TYPE_SeekMode", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "A_ARG_TYPE_SeekTarget", sendEvents = false, datatype = "string"),
        @UpnpStateVariable(name = "A_ARG_TYPE_InstanceID", sendEvents = false, datatype = "ui4"),
        @UpnpStateVariable(name = "LastChange", datatype = "string", defaultValue = "", sendEvents = true)
})
public class OpenClawAVTransportService {
    private static final String TAG = "OpenClawAVTransport";
    private String lastChange = "";

    public String getLastChange() {
        return lastChange;
    }

    @UpnpAction
    public void setAVTransportURI(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                  @UpnpInputArgument(name = "CurrentURI", stateVariable = "AVTransportURI") String currentURI,
                                  @UpnpInputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData") String currentURIMetaData)
            throws AVTransportException {
        Log.d(TAG, "setAVTransportURI uri=" + currentURI);
        OpenClawDlnaSupport.prepareRequest(currentURI, currentURIMetaData);
    }

    @UpnpAction
    public void setNextAVTransportURI(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                      @UpnpInputArgument(name = "NextURI", stateVariable = "AVTransportURI") String nextURI,
                                      @UpnpInputArgument(name = "NextURIMetaData", stateVariable = "AVTransportURIMetaData") String nextURIMetaData) {
        Log.d(TAG, "setNextAVTransportURI uri=" + nextURI);
        OpenClawDlnaSupport.prepareNextRequest(nextURI, nextURIMetaData);
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "NrTracks", stateVariable = "NumberOfTracks", getterName = "getNumberOfTracks"),
            @UpnpOutputArgument(name = "MediaDuration", stateVariable = "CurrentMediaDuration", getterName = "getMediaDuration"),
            @UpnpOutputArgument(name = "CurrentURI", stateVariable = "AVTransportURI", getterName = "getCurrentURI"),
            @UpnpOutputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData", getterName = "getCurrentURIMetaData"),
            @UpnpOutputArgument(name = "NextURI", stateVariable = "NextAVTransportURI", getterName = "getNextURI"),
            @UpnpOutputArgument(name = "NextURIMetaData", stateVariable = "NextAVTransportURIMetaData", getterName = "getNextURIMetaData"),
            @UpnpOutputArgument(name = "PlayMedium", stateVariable = "PlaybackStorageMedium", getterName = "getPlayMedium"),
            @UpnpOutputArgument(name = "RecordMedium", stateVariable = "RecordStorageMedium", getterName = "getRecordMedium"),
            @UpnpOutputArgument(name = "WriteStatus", stateVariable = "RecordMediumWriteStatus", getterName = "getWriteStatus")
    })
    public MediaInfo getMediaInfo(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        return snapshotMediaInfo();
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "CurrentTransportState", stateVariable = "TransportState", getterName = "getCurrentTransportState"),
            @UpnpOutputArgument(name = "CurrentTransportStatus", stateVariable = "TransportStatus", getterName = "getCurrentTransportStatus"),
            @UpnpOutputArgument(name = "CurrentSpeed", stateVariable = "TransportPlaySpeed", getterName = "getCurrentSpeed")
    })
    public TransportInfo getTransportInfo(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        return new TransportInfo(OpenClawDlnaSupport.currentTransportState(PlaybackManager.INSTANCE.currentStateSnapshot()));
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "Track", stateVariable = "CurrentTrack", getterName = "getTrack"),
            @UpnpOutputArgument(name = "TrackDuration", stateVariable = "CurrentTrackDuration", getterName = "getTrackDuration"),
            @UpnpOutputArgument(name = "TrackMetaData", stateVariable = "CurrentTrackMetaData", getterName = "getTrackMetaData"),
            @UpnpOutputArgument(name = "TrackURI", stateVariable = "CurrentTrackURI", getterName = "getTrackURI"),
            @UpnpOutputArgument(name = "RelTime", stateVariable = "RelativeTimePosition", getterName = "getRelTime"),
            @UpnpOutputArgument(name = "AbsTime", stateVariable = "AbsoluteTimePosition", getterName = "getAbsTime"),
            @UpnpOutputArgument(name = "RelCount", stateVariable = "RelativeCounterPosition", getterName = "getRelCount"),
            @UpnpOutputArgument(name = "AbsCount", stateVariable = "AbsoluteCounterPosition", getterName = "getAbsCount")
    })
    public PositionInfo getPositionInfo(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        ReceiverState state = PlaybackManager.INSTANCE.currentStateSnapshot();
        String uri = state.getUri() != null ? state.getUri() : "";
        String metadata = "NOT_IMPLEMENTED";
        long durationSeconds = Math.max(0L, state.getDurationMs() / 1000L);
        long positionSeconds = Math.max(0L, state.getPositionMs() / 1000L);
        return new PositionInfo(
                uri.isEmpty() ? 0 : 1,
                ModelUtil.toTimeString(durationSeconds),
                metadata,
                uri,
                ModelUtil.toTimeString(positionSeconds),
                ModelUtil.toTimeString(positionSeconds),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
        );
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "PlayMedia", stateVariable = "PossiblePlaybackStorageMedia", getterName = "getPlayMediaString"),
            @UpnpOutputArgument(name = "RecMedia", stateVariable = "PossibleRecordStorageMedia", getterName = "getRecMediaString"),
            @UpnpOutputArgument(name = "RecQualityModes", stateVariable = "PossibleRecordQualityModes", getterName = "getRecQualityModesString")
    })
    public DeviceCapabilities getDeviceCapabilities(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        return new DeviceCapabilities(new StorageMedium[]{StorageMedium.NETWORK});
    }

    @UpnpAction(out = {
            @UpnpOutputArgument(name = "PlayMode", stateVariable = "CurrentPlayMode", getterName = "getPlayMode"),
            @UpnpOutputArgument(name = "RecQualityMode", stateVariable = "CurrentRecordQualityMode", getterName = "getRecQualityMode")
    })
    public TransportSettings getTransportSettings(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        return new TransportSettings(PlayMode.NORMAL, RecordQualityMode.NOT_IMPLEMENTED);
    }

    @UpnpAction
    public void stop(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        Log.d(TAG, "stop");
        PlaybackManager.INSTANCE.stop();
    }

    @UpnpAction
    public void play(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                     @UpnpInputArgument(name = "Speed", stateVariable = "TransportPlaySpeed") String speed) {
        Log.d(TAG, "play speed=" + speed + " pending=" + PlaybackManager.INSTANCE.hasPendingDlnaRequest());
        ReceiverState state = PlaybackManager.INSTANCE.currentStateSnapshot();
        if (PlaybackManager.INSTANCE.hasPendingDlnaRequest() &&
                (state.getMediaKind() == ReceiverMediaKind.Idle || !state.isPlaying())) {
            PlaybackManager.INSTANCE.playPreparedDlnaRequest();
        } else {
            PlaybackManager.INSTANCE.resume();
        }
    }

    @UpnpAction
    public void pause(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        Log.d(TAG, "pause");
        PlaybackManager.INSTANCE.pause();
    }

    @UpnpAction
    public void record(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
    }

    @UpnpAction
    public void seek(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                     @UpnpInputArgument(name = "Unit", stateVariable = "A_ARG_TYPE_SeekMode") String unit,
                     @UpnpInputArgument(name = "Target", stateVariable = "A_ARG_TYPE_SeekTarget") String target) {
        Log.d(TAG, "seek unit=" + unit + " target=" + target);
        PlaybackManager.INSTANCE.seekTo(OpenClawDlnaSupport.parseTargetMillis(target));
    }

    @UpnpAction
    public void next(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        Log.d(TAG, "next pending=" + PlaybackManager.INSTANCE.hasPendingNextDlnaRequest());
        PlaybackManager.INSTANCE.playNextDlnaRequestIfAvailable();
    }

    @UpnpAction
    public void previous(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
    }

    @UpnpAction(out = @UpnpOutputArgument(name = "Actions", stateVariable = "CurrentTransportActions"))
    public String getCurrentTransportActions(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId) {
        return "Play,Stop,Pause,Seek,Next";
    }

    @UpnpAction
    public void setPlayMode(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                            @UpnpInputArgument(name = "NewPlayMode", stateVariable = "CurrentPlayMode") String newPlayMode) {
    }

    @UpnpAction
    public void setRecordQualityMode(@UpnpInputArgument(name = "InstanceID") UnsignedIntegerFourBytes instanceId,
                                     @UpnpInputArgument(name = "NewRecordQualityMode", stateVariable = "CurrentRecordQualityMode") String newRecordQualityMode) {
    }

    private MediaInfo snapshotMediaInfo() {
        ReceiverState state = PlaybackManager.INSTANCE.currentStateSnapshot();
        String uri = state.getUri() != null ? state.getUri() : "";
        String metadata = OpenClawDlnaSupport.currentTrackMetadata();
        String nextUri = OpenClawDlnaSupport.nextTrackUri();
        String nextMetadata = OpenClawDlnaSupport.nextTrackMetadata();
        long durationSeconds = Math.max(0L, state.getDurationMs() / 1000L);
        return new MediaInfo(
                uri,
                metadata,
                nextUri,
                nextMetadata,
                new UnsignedIntegerFourBytes(uri.isEmpty() ? 0 : 1),
                ModelUtil.toTimeString(durationSeconds),
                StorageMedium.NETWORK
        );
    }
}
