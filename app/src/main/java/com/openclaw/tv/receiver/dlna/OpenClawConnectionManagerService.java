package com.openclaw.tv.receiver.dlna;

import org.fourthline.cling.support.connectionmanager.ConnectionManagerService;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.ProtocolInfos;

public class OpenClawConnectionManagerService extends ConnectionManagerService {

    public OpenClawConnectionManagerService() {
        super(new ProtocolInfos(), new ProtocolInfos(
                new ProtocolInfo("http-get:*:video/mp4:*"),
                new ProtocolInfo("http-get:*:video/mpeg:*"),
                new ProtocolInfo("http-get:*:video/quicktime:*"),
                new ProtocolInfo("http-get:*:video/x-matroska:*"),
                new ProtocolInfo("http-get:*:application/vnd.apple.mpegurl:*"),
                new ProtocolInfo("http-get:*:application/x-mpegurl:*"),
                new ProtocolInfo("http-get:*:audio/mpeg:*"),
                new ProtocolInfo("http-get:*:audio/mp4:*"),
                new ProtocolInfo("http-get:*:audio/x-m4a:*"),
                new ProtocolInfo("http-get:*:audio/aac:*"),
                new ProtocolInfo("http-get:*:image/jpeg:*"),
                new ProtocolInfo("http-get:*:image/png:*"),
                new ProtocolInfo("http-get:*:image/gif:*")
        ));
    }
}
