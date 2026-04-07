package com.openclaw.tv.receiver.dlna;

import com.openclaw.tv.receiver.PlaybackManager;
import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.Playing;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.SeekMode;

import java.net.URI;

public class OpenClawPlaying extends Playing {

    public OpenClawPlaying(AVTransport transport) {
        super(transport);
    }

    @Override
    public void onEntry() {
        super.onEntry();
        PlaybackManager.INSTANCE.resume();
        OpenClawDlnaSupport.syncTransport(getTransport());
    }

    @Override
    public Class<? extends AbstractState> setTransportURI(URI uri, String metaData) {
        PlaybackManager.INSTANCE.stop();
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
        PlaybackManager.INSTANCE.resume();
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawPlaying.class;
    }

    @Override
    public Class<? extends AbstractState> pause() {
        PlaybackManager.INSTANCE.pause();
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawStopped.class;
    }

    @Override
    public Class<? extends AbstractState> next() {
        return OpenClawPlaying.class;
    }

    @Override
    public Class<? extends AbstractState> previous() {
        return OpenClawPlaying.class;
    }

    @Override
    public Class<? extends AbstractState> seek(SeekMode unit, String target) {
        PlaybackManager.INSTANCE.seekTo(OpenClawDlnaSupport.parseTargetMillis(target));
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawPlaying.class;
    }
}
