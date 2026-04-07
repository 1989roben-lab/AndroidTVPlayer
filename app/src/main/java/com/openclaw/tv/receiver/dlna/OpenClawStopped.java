package com.openclaw.tv.receiver.dlna;

import com.openclaw.tv.receiver.PlaybackManager;
import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.Stopped;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.SeekMode;

import java.net.URI;

public class OpenClawStopped extends Stopped {

    public OpenClawStopped(AVTransport transport) {
        super(transport);
    }

    @Override
    public void onEntry() {
        super.onEntry();
        OpenClawDlnaSupport.syncTransport(getTransport());
    }

    @Override
    public Class<? extends AbstractState> setTransportURI(URI uri, String metaData) {
        OpenClawDlnaSupport.prepareRequest(uri.toString(), metaData);
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawStopped.class;
    }

    @Override
    public Class<? extends AbstractState> stop() {
        PlaybackManager.INSTANCE.stop();
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawStopped.class;
    }

    @Override
    public Class<? extends AbstractState> play(String speed) {
        PlaybackManager.INSTANCE.playPreparedDlnaRequest();
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawPlaying.class;
    }

    @Override
    public Class<? extends AbstractState> next() {
        return OpenClawStopped.class;
    }

    @Override
    public Class<? extends AbstractState> previous() {
        return OpenClawStopped.class;
    }

    @Override
    public Class<? extends AbstractState> seek(SeekMode unit, String target) {
        PlaybackManager.INSTANCE.seekTo(OpenClawDlnaSupport.parseTargetMillis(target));
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawStopped.class;
    }
}
