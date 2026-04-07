package com.openclaw.tv.receiver.dlna;

import com.openclaw.tv.receiver.PlaybackManager;
import com.openclaw.tv.receiver.ReceiverState;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes;
import org.fourthline.cling.support.model.Channel;
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl;
import org.fourthline.cling.support.renderingcontrol.RenderingControlException;

public class OpenClawAudioRenderingControl extends AbstractAudioRenderingControl {

    @Override
    public boolean getMute(UnsignedIntegerFourBytes instanceId, String channelName) throws RenderingControlException {
        return PlaybackManager.INSTANCE.currentStateSnapshot().getMuted();
    }

    @Override
    public void setMute(UnsignedIntegerFourBytes instanceId, String channelName, boolean desiredMute) throws RenderingControlException {
        PlaybackManager.INSTANCE.setMuted(desiredMute);
    }

    @Override
    public UnsignedIntegerTwoBytes getVolume(UnsignedIntegerFourBytes instanceId, String channelName) throws RenderingControlException {
        ReceiverState state = PlaybackManager.INSTANCE.currentStateSnapshot();
        return new UnsignedIntegerTwoBytes(state.getVolumePercent());
    }

    @Override
    public void setVolume(UnsignedIntegerFourBytes instanceId, String channelName, UnsignedIntegerTwoBytes desiredVolume) throws RenderingControlException {
        PlaybackManager.INSTANCE.setVolume(desiredVolume.getValue().intValue());
    }

    @Override
    protected Channel[] getCurrentChannels() {
        return new Channel[]{Channel.Master};
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        return new UnsignedIntegerFourBytes[]{getDefaultInstanceID()};
    }
}
