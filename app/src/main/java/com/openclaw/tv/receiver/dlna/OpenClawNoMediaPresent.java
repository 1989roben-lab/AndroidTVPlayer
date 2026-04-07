package com.openclaw.tv.receiver.dlna;

import org.fourthline.cling.support.avtransport.impl.state.AbstractState;
import org.fourthline.cling.support.avtransport.impl.state.NoMediaPresent;
import org.fourthline.cling.support.model.AVTransport;

import java.net.URI;

public class OpenClawNoMediaPresent extends NoMediaPresent {

    public OpenClawNoMediaPresent(AVTransport transport) {
        super(transport);
    }

    @Override
    public Class<? extends AbstractState> setTransportURI(URI uri, String metaData) {
        OpenClawDlnaSupport.prepareRequest(uri.toString(), metaData);
        OpenClawDlnaSupport.syncTransport(getTransport());
        return OpenClawStopped.class;
    }
}
